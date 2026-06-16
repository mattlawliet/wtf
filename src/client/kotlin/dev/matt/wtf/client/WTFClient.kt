package dev.matt.wtf.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.matt.wtf.client.mixin.AbstractContainerScreenAccessor
import dev.matt.wtf.client.mixin.ShulkerBoxMenuAccessor
import dev.matt.wtf.client.mixin.EntityAccessor
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtAccounter
import net.minecraft.resources.RegistryOps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import net.minecraft.world.phys.AABB
import java.io.File
import java.io.PrintWriter
import java.security.MessageDigest
import java.util.Base64
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.gson.stream.JsonToken
import com.google.gson.JsonParseException

object WTFClient : ClientModInitializer {
    private val byteArrayAdapter = object : TypeAdapter<ByteArray?>() {
        override fun write(out: JsonWriter, value: ByteArray?) {
            if (value == null) {
                out.nullValue()
                return
            }
            out.value(Base64.getEncoder().encodeToString(value))
        }

        override fun read(`in`: JsonReader): ByteArray? {
            return when (`in`.peek()) {
                JsonToken.NULL -> { `in`.nextNull(); null }
                JsonToken.STRING -> {
                    val encoded = `in`.nextString()
                    try {
                        Base64.getDecoder().decode(encoded)
                    } catch (e: Exception) {
                        log("byteArrayAdapter: failed to decode base64: $e")
                        null
                    }
                }
                JsonToken.BEGIN_ARRAY -> {
                    val bytes = mutableListOf<Byte>()
                    `in`.beginArray()
                    while (`in`.hasNext()) {
                        try {
                            val b = (`in`.nextInt().toByte())
                            bytes.add(b)
                        } catch (e: Exception) {
                            throw JsonParseException("Invalid byte array format", e)
                        }
                    }
                    `in`.endArray()
                    bytes.toByteArray()
                }
                else -> throw JsonParseException("Expected ByteArray as string or array, got: ${`in`.peek()}")
            }
        }
    }
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .registerTypeAdapter(object : com.google.gson.reflect.TypeToken<ByteArray?>() {}.type, byteArrayAdapter)
        .create()
    private val trackedShulkers = HashMap<String, ShulkerState>()
    private val markerIcons = listOf("★", "●", "■", "♦", "▲", "none")
    private val markerColorOptions = listOf(
        "Yellow" to 0xFFFFFF55.toInt(),
        "Red" to 0xFFFF5555.toInt(),
        "Green" to 0xFF55FF55.toInt(),
        "Cyan" to 0xFF55FFFF.toInt(),
        "White" to 0xFFFFFFFF.toInt(),
        "Orange" to 0xFFFFAA00.toInt()
    )
    private var markerIcon: String = markerIcons[0]
    private var markerColorIdx: Int = 0
    private var currentWorldId: String? = null
    private var nextSerial = 1
    private var debugMode = false
    private var debugVerbose = false
    private var logWriter: PrintWriter? = null
    private var logBytesWritten = 0
    private val MAX_LOG_BYTES = 1_000_000
    private val MAX_TRANSIT_SPARE = 20
    private var tickCounter = 0

    private val transitOrder = ArrayDeque<String>()
    private val pendingItemEntities = mutableListOf<PendingItemEntity>()
    private val pendingDropEntities = mutableListOf<Pair<Int, Int>>()
    private val itemVanishTicks = mutableMapOf<String, Int>()
    private val itemVanishInvCount = mutableMapOf<String, Int>()
    private var prevInvShulkerCount = 0

    private fun countInvShulkers(player: Player): Int {
        var n = 0
        for (i in 0 until player.inventoryMenu.slots.size) {
            val stack = player.inventoryMenu.getSlot(i).item
            if (!stack.isEmpty && isShulkerItem(stack)) n++
        }
        return n
    }

    data class PendingItemEntity(
        val uuid: String,
        val pos: BlockPos,
        val tick: Int,
        var found: Boolean = false
    )

    fun onEntityRemoved(entityId: Int, reason: net.minecraft.world.entity.Entity.RemovalReason) {
        val eidStr = entityId.toString()

        // Fast path: entity was in "item" state (tick handler found it).
        // Only treat as pickup if the stack actually landed in the player's
        // inventory - hoppers/unloaders also remove the entity. If it's not in
        // inventory, leave state "item": the tick vanish check will either see
        // it arrive in inventory (pickup packet race; scan:uuid recovers) or
        // mark it ex-inv last-known at the suck location.
        val shulker = trackedShulkers.values.find { it.entity_id == eidStr && it.state == "item" }
        if (shulker != null) {
            val player = Minecraft.getInstance().player
            val inInventory = player != null && entryInPlayerInventory(player, shulker)
            if (inInventory) {
                transferToInv(shulker, "entity_removed")
            } else {
                log("entity_removed: ${shulker.name} (${shulker.uuid.take(8)}) entity gone but not in inventory - deferring to vanish check")
            }
            return
        }

        // Race fallback: entity removed before tick handler scanned for it.
        // PendingItemEntity still exists with state == "block".
        val mc = Minecraft.getInstance()
        val entity = mc.level?.getEntity(entityId) ?: return
        if (entity !is net.minecraft.world.entity.item.ItemEntity) return
        if (!isShulkerItem(entity.item)) return

        val pending = pendingItemEntities.firstOrNull { p ->
            entity.distanceToSqr(p.pos.x + 0.5, p.pos.y + 0.5, p.pos.z + 0.5) < 16.0
        } ?: return

        val entry = trackedShulkers[pending.uuid] ?: return
        pendingItemEntities.remove(pending)
        transferToInv(entry, "entity_removed:pending_fallback")
    }

    // Is this entry's item present in the player's inventory? uuid match is
    // authoritative, but server sync wipes wtf:uuid from freshly picked-up
    // stacks, so fall back to contentHash fingerprint.
    private fun entryInPlayerInventory(player: Player, entry: ShulkerState): Boolean {
        val invMenu = player.inventoryMenu
        for (i in 0 until invMenu.slots.size) {
            val stack = invMenu.getSlot(i).item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            val uuid = getItemUUID(stack)
            if (uuid == entry.uuid) return true
            if (uuid == null && entry.contentHash.isNotEmpty() &&
                fingerprintFromItem(stack) == entry.contentHash
            ) return true
        }
        return false
    }

    private fun applyDropMatch(match: ShulkerState, entity: ItemEntity, level: Level) {
        val oldState = match.state
        match.state = "item"
        match.entity_id = entity.id.toString()
        match.dim = level.dimension().identifier().toString()
        match.coords = "${entity.x},${entity.y},${entity.z}"
        match.last_update_time = System.currentTimeMillis().toString()
        match.from = "spawn:drop"
        log("transition: ${match.name} (${match.uuid.take(8)}) $oldState → item from=spawn:drop")
        if (match.happy) notify("§ainv§f → §7item§f §7(${match.name})§f")
        if (match.happy && isItemGlowEnabled()) (entity as EntityAccessor).invokeSetSharedFlag(6, true)
    }

    // Server's ClientboundTakeItemEntityPacket: authoritative "entity was
    // collected by collector". Fires for player and mob pickup, not hoppers -
    // so a take packet aimed at our player is proof of pickup, no inventory
    // count heuristics needed.
    fun onTakeItemEntity(itemEntityId: Int, collectorId: Int) {
        val mc = Minecraft.getInstance()
        // Packet handlers run once on the network thread before being
        // rescheduled onto the main thread - only act on the main-thread pass.
        if (!mc.isSameThread) return
        val player = mc.player ?: return
        if (collectorId != player.id) return

        // Entity dropped and re-picked before drop matching claimed it: just
        // forget the pending drop, the entry never left "inv".
        pendingDropEntities.removeAll { it.first == itemEntityId }

        val eidStr = itemEntityId.toString()
        val entry = trackedShulkers.values.find { it.entity_id == eidStr && it.state == "item" }
        if (entry != null) {
            itemVanishTicks.remove(entry.uuid)
            itemVanishInvCount.remove(entry.uuid)
            dropExpectations.removeAll { it.uuid == entry.uuid }
            log("take_packet: ${entry.name} (${entry.uuid.take(8)}) collected by player")
            transferToInv(entry, "pickup:take_packet", authoritative = true)
            return
        }

        // Race: picked up before the tick handler bound the entity to a
        // pendingItemEntities block break. Resolve by proximity, same as the
        // onEntityRemoved fallback.
        val entity = mc.level?.getEntity(itemEntityId) as? ItemEntity ?: return
        if (!isShulkerItem(entity.item)) return
        val pending = pendingItemEntities.firstOrNull { p ->
            entity.distanceToSqr(p.pos.x + 0.5, p.pos.y + 0.5, p.pos.z + 0.5) < 16.0
        } ?: return
        val pendingEntry = trackedShulkers[pending.uuid] ?: return
        pendingItemEntities.remove(pending)
        log("take_packet: pending block ${pendingEntry.name} (${pendingEntry.uuid.take(8)}) collected by player")
        transferToInv(pendingEntry, "pickup:take_packet:pending", authoritative = true)
    }

    private fun transferToInv(entry: ShulkerState, from: String, authoritative: Boolean = false) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val coords = entry.coords.split(",").mapNotNull { it.toDoubleOrNull() }
        if (authoritative || coords.size == 3) {
            val distSq = if (coords.size == 3) player.distanceToSqr(coords[0], coords[1], coords[2]) else 0.0
            if (authoritative || distSq < 64.0) {
                entry.state = "inv"
                entry.entity_id = ""
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = from
                transitOrder.remove(entry.uuid)
                transitOrder.addLast(entry.uuid)
                log("transition: ${entry.name} (${entry.uuid.take(8)}) → inv from=$from")
                if (entry.happy) notify("§7→ §ainv§f §7(${entry.name})§f")
                // Refresh contentHash from inventory so ENTITY_LOAD can match on Q-drop
                val invMenu = player.inventoryMenu
                for (i in 0 until invMenu.slots.size) {
                    val stack = invMenu.getSlot(i).item
                    if (isShulkerItem(stack) && getItemUUID(stack) == entry.uuid) {
                        fingerprintFromItem(stack)?.let { entry.contentHash = it }
                        break
                    }
                }
                scanQueued = true
                throttleTicks = THROTTLE_WINDOW
                save()
            }
        }
    }

    private var prevHeldUUID: String? = null
    private var prevSelectedSlot: Int = -1
    private var scanQueued: Boolean = false
    private var throttleTicks: Int = 0
    private val THROTTLE_WINDOW = 5
    // Container contents can arrive a tick or two after the screen opens
    // (sync packet latency). If the chest is closed before that, the slot
    // scan looks empty for not-yet-synced stacks - don't trust a "missing"
    // verdict from a scan that ran too soon after open.
    private val MIN_CHEST_SCAN_TICKS = 6
    // Just after world join, the inventory may not be fully synced yet -
    // running the first scan too early sees an empty/partial inventory and
    // demotes still-held "inv" entries to "ex-inv" (pass 4 cleanup). Hold
    // off the first post-join scan for 40 ticks (2s) to let it sync.
    private var postJoinGraceTicks: Int = 0


    private var openShulkerKey: String? = null
    private var openChestPos: BlockPos? = null
    private var openChestScreenTick: Int = 0
    // Ender chest is a per-player inventory, not a place - contents are
    // identical regardless of which physical ender chest was opened. Tracked
    // with state="enderchest" (own section), dim/coords unused/empty.
    private var openChestIsEnderChest: Boolean = false
    private var openChestInvSnapshot: MutableMap<String, InventorySnapshotEntry>? = null

    data class InventorySnapshotEntry(
        val slotIndex: Int,
        val locKey: String,
        val stack: ItemStack
    )

    // --- Click-transition ledger ---------------------------------------
    // Identity by slot continuity: server-authoritative stack data can't be
    // trusted to keep wtf:uuid alive across resyncs, but every movement of a
    // stack inside a menu goes through clicked(). Shulker boxes never stack,
    // so each click moves whole stacks - a before/after diff of shulker slots
    // deterministically tracks which tracked box sits where. The ledger maps
    // menu position ("s<slotIndex>" or "cursor") -> tracked uuid and is
    // consulted before hash/name heuristics, which only see continuity loss.
    // --- Drop expectations ----------------------------------------------
    // When the local player intentionally drops a tracked box (Q-key or a
    // throw click in a menu), we know which entry left the inventory before
    // the item entity even spawns. The spawn handler consumes these FIFO,
    // binding identity without content-hash guessing (whose components may
    // not have synced yet on the spawn tick).
    // The expectation records the stack's fingerprint at drop time (the local
    // stack is fully known pre-drop) - the spawn handler only binds an entity
    // whose synced hash equals it. Type alone is too loose: breaking an
    // unrelated box of the same color nearby would steal the expectation.
    data class DropExpectation(val uuid: String, val type: String, val hash: String, val timeMs: Long)
    private val dropExpectations = ArrayDeque<DropExpectation>()
    private val DROP_EXPECTATION_TTL_MS = 5000L

    private fun registerDropExpectation(uuid: String, stack: ItemStack, source: String) {
        if (trackedShulkers[uuid] == null) return
        val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val hash = fingerprintFromItem(stack) ?: return
        dropExpectations.addLast(DropExpectation(uuid, type, hash, System.currentTimeMillis()))
        log("drop expect: ${uuid.take(8)} type=$type hash=${hash.take(8)} from=$source")
    }

    private fun pruneDropExpectations() {
        val now = System.currentTimeMillis()
        dropExpectations.removeAll { now - it.timeMs > DROP_EXPECTATION_TTL_MS }
    }

    // Called from LocalPlayerMixin at the head of LocalPlayer.drop(boolean),
    // before the stack leaves the selected hotbar slot.
    fun onLocalDrop(selected: ItemStack) {
        if (selected.isEmpty || !isShulkerItem(selected)) return
        val uuid = getItemUUID(selected)?.takeIf { it in trackedShulkers } ?: return
        registerDropExpectation(uuid, selected, "q_drop")
    }

    private var ledgerMenuId: Int = Int.MIN_VALUE
    private val slotLedger = mutableMapOf<String, String>()
    private var clickPreSnapshot: MutableMap<String, ItemStack>? = null

    // Cross-session slot->uuid map per chest, keyed by chest location string.
    // wtf:uuid stamps and content hashes can both go unstable across a game
    // restart (server resync wipes CUSTOM_DATA, which can also perturb the
    // content hash of boxes containing other stamped boxes). Slot position
    // within a given chest is the most reliable cross-restart identity we have.
    private val persistedChestLedger = mutableMapOf<String, MutableMap<String, String>>()

    private fun ledgerPosKey(slotIndex: Int) = "s$slotIndex"

    private fun captureMenuShulkers(menu: net.minecraft.world.inventory.AbstractContainerMenu, copy: Boolean): MutableMap<String, ItemStack> {
        val snap = mutableMapOf<String, ItemStack>()
        for (slot in menu.slots) {
            val s = slot.item
            if (!s.isEmpty && isShulkerItem(s)) snap[ledgerPosKey(slot.index)] = if (copy) s.copy() else s
        }
        val carried = menu.carried
        if (!carried.isEmpty && isShulkerItem(carried)) snap["cursor"] = if (copy) carried.copy() else carried
        return snap
    }

    private fun seedLedger(menu: net.minecraft.world.inventory.AbstractContainerMenu) {
        slotLedger.clear()
        ledgerMenuId = menu.containerId
        val claimed = mutableSetOf<String>()
        val player = Minecraft.getInstance().player
        for (slot in menu.slots) {
            // Player inventory slots share a slot-index space with chest slots.
            // Including them causes ledgerOwner to point player-inv uuids at
            // player slot keys, which then makes evictOrphanUUIDStamps strip
            // every chest slot carrying the same uuid (e.g. all Kitt boxes).
            if (player != null && slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            val pos = ledgerPosKey(slot.index)
            val uuid = getItemUUID(stack)?.takeIf { it in trackedShulkers && it !in claimed } ?: continue
            slotLedger[pos] = uuid
            claimed.add(uuid)
        }
        log("ledger: seeded ${slotLedger.size} entries for menu ${menu.containerId}")
    }

    fun getMarkerIcon() = if (markerIcon == "none") "—" else markerIcon

    fun getMarkerColor() = markerColorOptions[markerColorIdx].second

    fun getMarkerColorName() = markerColorOptions[markerColorIdx].first

    fun cycleMarkerIcon(): String {
        markerIcon = markerIcons[(markerIcons.indexOf(markerIcon) + 1) % markerIcons.size]
        save()
        return getMarkerIcon()
    }

    fun cycleMarkerColor(): String {
        markerColorIdx = (markerColorIdx + 1) % markerColorOptions.size
        save()
        return markerColorOptions[markerColorIdx].first
    }

    fun repairSlotUUIDs() {
        val player = Minecraft.getInstance().player ?: return
        val menu = player.containerMenu
        if (menu.containerId != ledgerMenuId) return
        val stale = mutableListOf<String>()
        for ((pos, uuid) in slotLedger) {
            val entry = trackedShulkers[uuid] ?: continue
            val idx = pos.removePrefix("s").toIntOrNull() ?: continue
            val slot = menu.slots.getOrNull(idx) ?: continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            if (getItemUUID(stack) == uuid) continue
            // Only re-stamp if this slot still plausibly holds the same
            // shulker (contents match) - otherwise the slot's contents
            // changed underneath the ledger and re-stamping would steal
            // this uuid onto an unrelated stack.
            if (fingerprintFromItem(stack) == entry.contentHash) {
                injectItemUUID(stack, uuid)
            } else {
                stale.add(pos)
            }
        }
        stale.forEach { slotLedger.remove(it) }
        seedChestSlotUUIDs(menu, player)
    }

    fun onMenuClickPre(menu: net.minecraft.world.inventory.AbstractContainerMenu) {
        if (Minecraft.getInstance().player == null) return
        if (menu.containerId != ledgerMenuId) seedLedger(menu)
        clickPreSnapshot = captureMenuShulkers(menu, copy = true)
    }

    fun onMenuClickPost(menu: net.minecraft.world.inventory.AbstractContainerMenu) {
        val pre = clickPreSnapshot ?: return
        clickPreSnapshot = null
        if (menu.containerId != ledgerMenuId) return
        val post = captureMenuShulkers(menu, copy = false)

        fun sameStack(a: ItemStack, b: ItemStack) =
            ItemStack.hashItemAndComponents(a) == ItemStack.hashItemAndComponents(b)

        // Positions whose ledger-tracked shulker left, and positions that
        // gained a (new or different) shulker stack this click.
        val lost = pre.filterKeys { it in slotLedger }
            .filter { (k, old) -> post[k]?.let { !sameStack(old, it) } ?: true }
        val gained = post.filter { (k, now) -> pre[k]?.let { !sameStack(it, now) } ?: true }.toMutableMap()

        for ((fromPos, oldStack) in lost) {
            val uuid = slotLedger[fromPos] ?: continue
            // 1. uuid stamp survived the move
            var toPos = gained.entries.firstOrNull { getItemUUID(it.value) == uuid }?.key
            // 2. exact same stack landed elsewhere
            if (toPos == null) toPos = gained.entries.firstOrNull { sameStack(oldStack, it.value) }?.key
            // 3. single source, single destination
            if (toPos == null && lost.size == 1 && gained.size == 1) toPos = gained.keys.first()

            slotLedger.remove(fromPos)
            if (toPos != null) {
                // A swap can move the displaced stack into fromPos - only drop
                // mappings we are overwriting, not unrelated ones.
                slotLedger.entries.removeAll { it.key == toPos }
                slotLedger[toPos] = uuid
                gained.remove(toPos)
                // Re-stamp: keeps the uuid fast path alive even after server
                // resyncs stripped it from the stack.
                if (getItemUUID(post[toPos]!!) != uuid) injectItemUUID(post[toPos]!!, uuid)
                log("ledger: $fromPos -> $toPos (${uuid.take(8)})")
            } else {
                // Left the menu entirely (thrown out). Register an expectation
                // so the spawn handler binds the item entity by intent, not by
                // content hash.
                log("ledger: $fromPos -> out (${uuid.take(8)})")
                registerDropExpectation(uuid, oldStack, "menu_throw")
            }
        }
    }

    // Resolve a tracked entry's current menu position via the ledger.
    private fun ledgerLookup(menu: net.minecraft.world.inventory.AbstractContainerMenu, uuid: String): Pair<String, ItemStack>? {
        if (menu.containerId != ledgerMenuId) return null
        val pos = slotLedger.entries.firstOrNull { it.value == uuid }?.key ?: return null
        if (pos == "cursor") {
            val carried = menu.carried
            return if (!carried.isEmpty && isShulkerItem(carried)) pos to carried else null
        }
        val idx = pos.removePrefix("s").toIntOrNull() ?: return null
        val slot = menu.slots.getOrNull(idx) ?: return null
        return if (!slot.item.isEmpty && isShulkerItem(slot.item)) pos to slot.item else null
    }

    private fun getContainerBlockPos(menu: net.minecraft.world.inventory.AbstractContainerMenu, level: Level): Pair<BlockPos, String>? {
        for (slot in menu.slots) {
            val container = slot.container
            if (container is BaseContainerBlockEntity) {
                val bePos = container.blockPos
                val blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(bePos).block).toString()
                // Any container block entity counts (chest, barrel, hopper,
                // dropper, dispenser, furnace, ...) - shulkers can be stored in all.
                return bePos to blockId
            }
        }
        return null
    }

    private fun handleChestScreen(mc: Minecraft, screen: Any) {
        val level = mc.level ?: return

        openChestScreenTick = tickCounter
        openChestPos = null
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu
        // Client-side the ender chest menu's container is a plain SimpleContainer
        // (PlayerEnderChestContainer never reaches the client) - the only signal
        // available is what block the player was looking at when it opened.
        openChestIsEnderChest = run {
            val hr = mc.hitResult
            if (hr is net.minecraft.world.phys.BlockHitResult) {
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(hr.blockPos).block).toString() == "minecraft:ender_chest"
            } else false
        }

        if (openChestIsEnderChest) {
            log("handleChestScreen: ender chest")
        } else {
        if (menu != null) {
            val fromContainer = getContainerBlockPos(menu, level)
            if (fromContainer != null) {
                openChestPos = fromContainer.first
                log("handleChestScreen: container at ${blockLocation(fromContainer.first, level)} (from BE)")
            }
        }

        // Fallback: use hit result if container lookup didn't find anything
        if (openChestPos == null) {
            val hr = mc.hitResult
            if (hr is net.minecraft.world.phys.BlockHitResult) {
                val pos = hr.blockPos
                if (level.getBlockEntity(pos) is BaseContainerBlockEntity) {
                    openChestPos = pos
                    log("handleChestScreen: container at ${blockLocation(pos, level)} (from hitResult)")
                }
            }
        }
        }

        openChestInvSnapshot = if (menu != null) captureInventorySnapshot(menu, mc.player) else mutableMapOf()

        if (menu != null) {
            seedLedger(menu)
            seedChestSlotUUIDs(menu, mc.player)
        }
    }

    // One-time heuristic resolve for chest-side stacks without uuid stamps,
    // so renderHappyMarkers (which reads getItemUUID directly) shows the
    // marker while the chest is open - the ender chest in particular strips
    // client-injected tags on every reopen, and its contents may not even be
    // populated yet at handleChestScreen time (arrive via a later resync
    // packet, hence this is also called from repairSlotUUIDs).
    private fun seedChestSlotUUIDs(menu: net.minecraft.world.inventory.AbstractContainerMenu, player: Player?) {
        val level = Minecraft.getInstance()?.level
        val chestState: String
        val chestLoc: String
        val coordStr: String
        val dimStr: String
        if (openChestIsEnderChest) {
            chestState = "enderchest"
            chestLoc = "Ender Chest"
            coordStr = ""
            dimStr = ""
        } else {
            chestState = "ex-inv"
            val pos = openChestPos
            if (pos != null && level != null) {
                chestLoc = blockLocation(pos, level)
                coordStr = "${pos.x},${pos.y},${pos.z}"
                dimStr = level.dimension().identifier().toString()
            } else {
                chestLoc = ""
                coordStr = ""
                dimStr = ""
            }
        }

        val claimed = slotLedger.values.toMutableSet()
        for (slot in menu.slots) {
            if (player != null && slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isTrackableShulker(stack)) continue
            val pos = ledgerPosKey(slot.index)
            val existingUUID = getItemUUID(stack)
            if (existingUUID != null && existingUUID in trackedShulkers) {
                // If this uuid is ALSO stamped on a player-inventory item, the same
                // identity is present in two different locations simultaneously (old
                // shared-uuid bad data). Chest item loses - strip it and block the
                // uuid from being re-assigned to any chest slot this session so that
                // the inv item remains the sole owner.
                val alsoInInv = player != null && menu.slots.any { s ->
                    s.container == player.inventory && getItemUUID(s.item) == existingUUID
                }
                if (alsoInInv) {
                    stripItemUUID(stack)
                    slotLedger.remove(pos)
                    persistedChestLedger[chestLoc]?.let { if (it[pos] == existingUUID) it.remove(pos) }
                    claimed.add(existingUUID)
                    log("evict: uuid ${existingUUID.take(8)} also in player inv, stripped from chest slot ${slot.index}")
                    continue
                }
                // Already stamped and no conflict — add to ledger so evictOrphanUUIDStamps
                // knows the owner slot and doesn't strip it as an unclaimed duplicate.
                if (pos !in slotLedger && existingUUID !in claimed) {
                    slotLedger[pos] = existingUUID
                    claimed.add(existingUUID)
                }
                continue
            }
            if (pos in slotLedger) continue
            val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val hash = fingerprintFromItem(stack) ?: continue
            val hasCustomName = stack.has(DataComponents.CUSTOM_NAME)
            val name = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            val persistedHint = persistedChestLedger[chestLoc]?.get(pos)
            val uuid = resolveTrackedChestStack(hash, name, type, hasCustomName, claimed,
                    preferHint = persistedHint, slotIndex = slot.index)
                ?: persistedHint?.takeIf {
                    it in trackedShulkers && it !in claimed &&
                        ledgerEntryMatchesStack(trackedShulkers[it]!!, hash, name, type, hasCustomName)
                }
                ?: resolveBySlotIndex(chestState, slot.index, type, name, hasCustomName, claimed)
                ?: continue
            slotLedger[pos] = uuid
            claimed.add(uuid)
            injectItemUUID(stack, uuid)
            trackedShulkers[uuid]?.let { it.contentHash = hash }
            log("ledger: chest seed $pos -> ${uuid.take(8)} (hash)")
        }
        evictOrphanUUIDStamps(menu, player)
    }

    private fun handleChestClosed(screen: Any) {
        val mc = Minecraft.getInstance() ?: return
        val level = mc.level ?: return
        val player = mc.player ?: return

        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu ?: return

        val chestLoc: String
        val coordStr: String
        val dimStr: String
        if (openChestIsEnderChest) {
            chestLoc = "Ender Chest"
            coordStr = ""
            dimStr = ""
        } else {
            // Derive block position from the container's block entity (reliable, not hitResult-dependent)
            val containerInfo = getContainerBlockPos(menu, level)
            val containerPos = containerInfo?.first ?: openChestPos ?: return
            chestLoc = blockLocation(containerPos, level)
            coordStr = "${containerPos.x},${containerPos.y},${containerPos.z}"
            dimStr = level.dimension().identifier().toString()
        }

        val chestState = if (openChestIsEnderChest) "enderchest" else "ex-inv"
        val notifyTag = if (openChestIsEnderChest) "§dender§f" else "§echest§f"

        // 1. Scan all slots in the container to find which shulkers are currently inside.
        // Two passes: first reserve every confident match (uuid/ledger/hash), then
        // resolve leftover unnamed slots against leftover tracked entries. Doing
        // this in one pass let an early ambiguous duplicate steal the tracked
        // identity that a later slot would otherwise have hash-matched correctly.
        data class ScanItem(
            val slot: Slot,
            val stack: ItemStack,
            val stackUUID: String?,
            val stackHash: String,
            val hasCustomName: Boolean,
            val stackName: String,
            val stackType: String
        )

        val shulkersInChest = mutableSetOf<String>()
        val pending = mutableListOf<ScanItem>()
        val resolved = mutableListOf<Pair<ScanItem, String>>()

        for (slot in menu.slots) {
            if (slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isTrackableShulker(stack)) continue

            val stackUUID = getItemUUID(stack)
            val stackHash = fingerprintFromItem(stack) ?: ""
            val hasCustomName = stack.has(DataComponents.CUSTOM_NAME)
            val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            val stackType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val item = ScanItem(slot, stack, stackUUID, stackHash, hasCustomName, stackName, stackType)

            val posKey = ledgerPosKey(slot.index)
            // A slot->uuid mapping is only trustworthy if the entry it points to
            // still plausibly matches the stack now in that slot. Validate before
            // claiming, and evict the mapping when it's gone stale so a different
            // box in the same slot doesn't inherit the old identity.
            fun validateLedger(uuid: String?): String? {
                val u = uuid?.takeIf { it in trackedShulkers && it !in shulkersInChest } ?: return null
                val entry = trackedShulkers[u] ?: return null
                if (ledgerEntryMatchesStack(entry, stackHash, stackName, stackType, hasCustomName)) return u
                log("chest resolve: evicting stale ledger $posKey -> ${u.take(8)} (stack mismatch)")
                if (slotLedger[posKey] == u) slotLedger.remove(posKey)
                persistedChestLedger[chestLoc]?.let { if (it[posKey] == u) it.remove(posKey) }
                return null
            }
            val ledgerUUID = if (menu.containerId == ledgerMenuId) validateLedger(slotLedger[posKey]) else null
            val persistedUUID = validateLedger(persistedChestLedger[chestLoc]?.get(posKey))
            val matchedUUID = stackUUID?.takeIf { it in trackedShulkers }
                ?: ledgerUUID
                ?: persistedUUID
                ?: resolveBySlotIndex(chestState, slot.index, stackType, stackName, hasCustomName, shulkersInChest)
                ?: resolveTrackedChestStack(stackHash, stackName, stackType, hasCustomName, shulkersInChest,
                    preferHint = persistedChestLedger[chestLoc]?.get(posKey), slotIndex = slot.index)
            if (matchedUUID != null) {
                shulkersInChest.add(matchedUUID)
                resolved.add(item to matchedUUID)
            } else {
                pending.add(item)
            }
        }

        for (item in pending) {
            val orphanUUID: String? = null
            val uuid = if (orphanUUID != null) {
                orphanUUID
            } else {
                // Assign UUID to untracked shulker found in chest
                val newUuid = ensureItemUUID(item.stack)
                // Create a tracking entry if not already tracked (discovered in chest)
                if (newUuid !in trackedShulkers) {
                    trackedShulkers[newUuid] = ShulkerState(
                        uuid = newUuid,
                        state = chestState,
                        dim = dimStr,
                        coords = coordStr,
                        slotIndex = item.slot.index,
                        firstSeen = System.currentTimeMillis(),
                        last_update_time = System.currentTimeMillis().toString(),
                        name = item.stackName,
                        happy = false,
                        contentHash = item.stackHash,
                        from = "chest:new_discovery",
                        type = item.stackType,
                        cachedContents = serializeShulkerContents(item.stack)
                    )
                    log("handleChestClosed: new untracked shulker discovered in chest at $chestLoc, uuid=$newUuid name=${item.stackName}")
                }
                newUuid
            }
            shulkersInChest.add(uuid)
            resolved.add(item to uuid)
        }

        // Rewrite the persisted ledger from scratch so stale slot→uuid entries
        // from previous sessions (e.g. boxes that moved slots) can't accumulate
        // and later be mistaken for valid hints.
        persistedChestLedger[chestLoc] = resolved
            .associate { (item, uuid) -> ledgerPosKey(item.slot.index) to uuid }
            .toMutableMap()

        for ((item, uuid) in resolved) {
            val slot = item.slot
            val stack = item.stack
            val stackUUID = item.stackUUID
            val stackHash = item.stackHash
            val stackType = item.stackType
            if (stackUUID != uuid) {
                injectItemUUID(stack, uuid)
            }

            val entry = trackedShulkers[uuid]
            if (entry != null) {
                val cachedNbt = serializeShulkerContents(stack)
                val oldState = entry.state
                val oldCoords = entry.coords
                val oldFrom = entry.from

                if (!openChestIsEnderChest && (oldCoords != coordStr || entry.dim != dimStr)) {
                    entry.lastLocation = "${entry.dim}:${oldCoords}"
                }
                entry.state = chestState
                entry.lastKnown = false
                entry.entity_id = ""
                entry.dim = dimStr
                entry.coords = coordStr
                entry.slotIndex = slot.index
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = if (stackUUID == uuid) "chest:uuid" else "chest:matched"
                entry.cachedContents = cachedNbt
                if (stackHash.isNotEmpty()) entry.contentHash = stackHash
                entry.type = stackType

                if (oldState != chestState || oldCoords != coordStr) {
                    if (entry.happy) notify("$notifyTag ← §a${oldState}§f §7(${entry.name})§f")
                    log("handleChestClosed: tracked shulker placed/found in $chestLoc, uuid=$uuid from=${entry.from} oldFrom=$oldFrom")
                }
            }
        }

        var corrected = false

        // If a hopper pulls the shulker out before close, it never appears in the final chest scan.
        // Use the open/close inventory delta to still record the chest as the last known external location.
        // Ender chests have no hoppers - this whole recovery path is moot there.
        val currentInventoryByUuid = captureInventorySnapshot(menu, player)
        val currentInventoryBySlot = captureInventoryBySlot(menu, player)
        for ((uuid, snapshot) in if (openChestIsEnderChest) emptyMap() else (openChestInvSnapshot ?: emptyMap())) {
            if (uuid in shulkersInChest) continue
            val entry = trackedShulkers[uuid] ?: continue
            if (entry.state != "inv") continue
            if (uuid in currentInventoryByUuid) continue

            val currentSourceStack = currentInventoryBySlot[snapshot.slotIndex]?.stack
            if (currentSourceStack != null) {
                if (getItemUUID(currentSourceStack) == uuid) continue
                val snapFp = fingerprintFromItem(snapshot.stack)
                val currFp = fingerprintFromItem(currentSourceStack)
                if (snapFp != null && currFp != null && snapFp == currFp) continue
            }

            val oldCoords = entry.coords
            entry.lastLocation = "${entry.dim}:$oldCoords"
            entry.state = "ex-inv"
            entry.lastKnown = true
            entry.entity_id = ""
            entry.dim = dimStr
            entry.coords = coordStr
            entry.last_update_time = System.currentTimeMillis().toString()
            entry.from = "chest:vanished_on_insert"
            entry.cachedContents = chooseRicherCache(serializeShulkerContents(snapshot.stack), entry.cachedContents)
            fingerprintFromItem(snapshot.stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
            log("handleChestClosed: inv -> chest last-known ${entry.name} uuid=$uuid from=${snapshot.locKey} to=$chestLoc; source slot changed and not present in final chest scan")
            if (entry.happy) notify("§ainv§f → §cchest LK§f §7(${entry.name})§f")
            corrected = true
        }

        // 2. Self-Correction: Find shulkers previously marked in THIS chest/enderchest that are now MISSING
        for (entry in trackedShulkers.values) {
            if (entry.state == chestState && entry.coords == coordStr && entry.dim == dimStr) {
                if (entry.uuid !in shulkersInChest) {
                    val invMatch = findInventoryStackForEntry(entry, menu, player)
                    if (invMatch != null) {
                        val (locKey, stack) = invMatch
                        val wasLK = entry.lastKnown
                        injectItemUUID(stack, entry.uuid)
                        entry.lastLocation = "${entry.dim}:${entry.coords}"
                        entry.state = "inv"
                        entry.lastKnown = false
                        entry.entity_id = ""
                        entry.coords = locKey
                        entry.dim = dimStr
                        entry.last_update_time = System.currentTimeMillis().toString()
                        entry.from = "chest:take_to_inv"
                        entry.cachedContents = chooseRicherCache(serializeShulkerContents(stack), entry.cachedContents)
                        fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
                        log("handleChestClosed: chest -> inv ${entry.name} uuid=${entry.uuid} from=$chestLoc to=$locKey lastKnown=$wasLK")
                        if (entry.happy) {
                            if (wasLK) notify("§c[LK]§f → §ainv§f §7(${entry.name})§f") else notify("§echest§f → §ainv§f §7(${entry.name})§f")
                        }
                        corrected = true
                    } else if (!entry.lastKnown && !openChestIsEnderChest && tickCounter - openChestScreenTick >= MIN_CHEST_SCAN_TICKS &&
                        persistedChestLedger[chestLoc]?.containsValue(entry.uuid) == true) {
                        // Only declare "missing" when we've previously pinned this uuid
                        // to a specific slot via the persisted ledger - otherwise we may
                        // just not have been able to identify it (ambiguous unnamed box),
                        // so leave state unchanged rather than marking it last-known.
                        log("handleChestClosed self-correction: shulker ${entry.name} (uuid=${entry.uuid}) is no longer in chest at $chestLoc. Marking as external last-known.")
                        entry.lastKnown = true
                        entry.lastLocation = "${entry.dim}:${entry.coords}"
                        entry.last_update_time = System.currentTimeMillis().toString()
                        entry.from = "chest:missing_on_close"
                        corrected = true
                    }
                }
            }
        }

        openChestPos = null
        openChestInvSnapshot = null
        save()
    }

    // Unnamed shulker boxes carry no identifying NBT, and a server resync
    // (e.g. rejoining after a restart) can wipe wtf:uuid and/or perturb
    // content-hash fingerprints (CUSTOM_DATA on nested boxes, etc). When
    // none of that resolves a slot, re-link to the one previously-tracked
    // entry for this chest of matching type — BUT only when there is exactly
    // one such candidate. If there are two or more same-type unnamed boxes
    // and only one candidate remains, picking randomly would swap identities
    // across restarts; instead do nothing and let the persisted slot ledger
    // accumulate correct mappings over time via manual opens.
    private fun resolveOrphanedChestSlot(
        stackType: String,
        hasCustomName: Boolean,
        chestState: String,
        coordStr: String,
        dimStr: String,
        alreadyResolved: Set<String>
    ): String? {
        if (hasCustomName) return null
        val candidates = trackedShulkers.values.filter {
            it.uuid !in alreadyResolved &&
                it.type == stackType &&
                it.state == chestState &&
                it.coords == coordStr &&
                it.dim == dimStr
        }
        if (candidates.size != 1) return null
        return candidates[0].uuid.also { log("chest resolve: orphan-slot re-linked unnamed $stackType to tracked UUID $it (unambiguous)") }
    }

    private fun resolveTrackedChestStack(
        stackHash: String,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean,
        alreadyResolved: Set<String>,
        preferHint: String? = null,
        slotIndex: Int = -1
    ): String? {
        // Deterministic disambiguation when several candidates are equally
        // valid: the persisted-ledger hint for this slot wins, then a candidate
        // already pinned to this slot, then happy entries, then the oldest by
        // firstSeen. Map iteration order is NOT stable across reloads, so never
        // fall back to a bare firstOrNull - that's what swapped identities.
        fun pick(candidates: List<ShulkerState>): ShulkerState? {
            if (candidates.isEmpty()) return null
            candidates.firstOrNull { it.uuid == preferHint }?.let { return it }
            if (slotIndex >= 0) candidates.firstOrNull { it.slotIndex == slotIndex }?.let { return it }
            val happy = candidates.filter { it.happy }.ifEmpty { candidates }
            return happy.minByOrNull { if (it.firstSeen > 0) it.firstSeen else Long.MAX_VALUE }
                ?: happy.minByOrNull { it.uuid }
        }

        // When resolving a specific slot, a candidate already pinned to a
        // DIFFERENT slot is not this box - slot position is authoritative for
        // container-held boxes (esp. ender, where the item uuid stamp is wiped
        // on every reopen so slot is the only durable identity). Without this an
        // identical-content box pinned to slot 20 gets claimed by slot 5 simply
        // because it was the only hash candidate left.
        fun slotEligible(c: ShulkerState): Boolean =
            slotIndex < 0 || c.slotIndex < 0 || c.slotIndex == slotIndex

        if (stackHash.isNotEmpty() && stackHash != genericEmptyHash(stackType)) {
            val hashMatches = trackedShulkers.values.filter {
                it.uuid !in alreadyResolved &&
                    it.type == stackType &&
                    it.contentHash == stackHash &&
                    slotEligible(it) &&
                    (it.state == "inv" || it.state == "item" || it.state == "block" || it.state == "ex-inv" || it.state == "enderchest")
            }
            val hashMatch = pick(hashMatches)
            if (hashMatch != null) {
                log("chest resolve: hash-matched $stackName to tracked UUID ${hashMatch.uuid}")
                return hashMatch.uuid
            }
        }

        return null
    }

    // True when a tracked entry plausibly IS this stack - used to validate a
    // ledger/persisted slot->uuid mapping before trusting it. A box that left
    // the slot leaves a stale mapping behind; without this check the next
    // (different) box in that slot inherits the old identity.
    private fun ledgerEntryMatchesStack(
        entry: ShulkerState,
        stackHash: String,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean
    ): Boolean {
        if (entry.type != stackType) return false
        // A named box carries a stable identity in its name - it does NOT drift
        // across restart, so it must agree. (Also rejects a now-named box sitting
        // where an unnamed one was tracked, and vice-versa.)
        val entryNamed = entry.name.isNotEmpty() && entry.name != "Shulker Box" && entry.name != "???"
        if (hasCustomName || entryNamed) return entry.name == stackName
        // Unnamed box: the content-hash is NOT reliable here - ender strips the
        // uuid stamp on every reopen and server resync perturbs nested NBT, so
        // the hash drifts across restart. Slot continuity (this mapping was
        // recorded for this exact slot) is the only durable signal, so trust it
        // by slot+type. A genuinely different identified box would carry its own
        // tracked uuid and be resolved before this fallback is ever consulted.
        return true
    }

    // Authoritative slot fallback for container-held boxes: a tracked entry that
    // records THIS exact slot in THIS container state is this box. Uses the
    // entry's own persisted slotIndex (always saved on the entry) rather than
    // the separate persistedChestLedger map - so it still resolves when that map
    // is incomplete. That gap is what orphaned default/unnamed ender boxes: their
    // generic hash is unmatchable and, with no ledger entry, they were
    // re-discovered as new non-happy entries on every reopen, losing the marker
    // on all but the one box whose ledger row happened to survive.
    private fun resolveBySlotIndex(
        chestState: String,
        slotIndex: Int,
        stackType: String,
        stackName: String,
        hasCustomName: Boolean,
        alreadyResolved: Set<String>
    ): String? {
        if (slotIndex < 0) return null
        val match = trackedShulkers.values.firstOrNull {
            it.uuid !in alreadyResolved &&
                it.state == chestState &&
                it.slotIndex == slotIndex &&
                it.type == stackType &&
                ledgerEntryMatchesStack(it, "", stackName, stackType, hasCustomName)
        } ?: return null
        log("chest resolve: slot-matched $stackName ($chestState s$slotIndex) -> ${match.uuid}")
        return match.uuid
    }

    private fun findInventoryStackForEntry(
        entry: ShulkerState,
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player
    ): Pair<String, ItemStack>? {
        // Ledger first: slot continuity beats every content heuristic below.
        if (menu.containerId == ledgerMenuId) {
            val pos = slotLedger.entries.firstOrNull { it.value == entry.uuid }?.key
            if (pos == "cursor" && !menu.carried.isEmpty && isShulkerItem(menu.carried)) {
                return "cursor" to menu.carried
            }
            val idx = pos?.removePrefix("s")?.toIntOrNull()
            if (idx != null) {
                val slot = menu.slots.getOrNull(idx)
                if (slot != null && slot.container == player.inventory &&
                    !slot.item.isEmpty && isShulkerItem(slot.item)
                ) {
                    return inventorySlotToKey(slot.index) to slot.item
                }
            }
        }

        val snapshot = openChestInvSnapshot ?: emptyMap()
        val candidates = mutableListOf<Pair<String, ItemStack>>()

        for (slot in menu.slots) {
            if (slot.container != player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            candidates.add(inventorySlotToKey(slot.index) to stack)
        }

        if (!menu.carried.isEmpty && isShulkerItem(menu.carried)) {
            val carried = menu.carried
            candidates.add("cursor" to carried)
        }

        candidates.firstOrNull { getItemUUID(it.second) == entry.uuid }?.let { return it }

        val newOrChanged = candidates.filter { (_, stack) ->
            val uuid = getItemUUID(stack)
            val snapshotStack = uuid?.let { snapshot[it]?.stack }
            snapshotStack == null || ItemStack.hashItemAndComponents(snapshotStack) != ItemStack.hashItemAndComponents(stack)
        }

        newOrChanged.firstOrNull { (_, stack) ->
            val stackType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            stackType == entry.type && fingerprintFromItem(stack) == entry.contentHash
        }?.let { return it }

        val nameMatch = newOrChanged.firstOrNull { (_, stack) ->
            val stackType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            entry.happy && stackType == entry.type && stackName == entry.name && fingerprintFromItem(stack) == entry.contentHash
        }
        if (nameMatch == null && newOrChanged.isNotEmpty()) {
            log("findInventoryStackForEntry: no match for ${entry.name} (${entry.uuid.take(8)}) among ${newOrChanged.size} candidates")
        }
        return nameMatch
    }

    private fun captureInventorySnapshot(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player?
    ): MutableMap<String, InventorySnapshotEntry> {
        if (player == null) return mutableMapOf()
        val snapshot = mutableMapOf<String, InventorySnapshotEntry>()
        for (slot in menu.slots) {
            if (slot.container != player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            val uuid = getItemUUID(stack) ?: continue
            snapshot[uuid] = InventorySnapshotEntry(
                slotIndex = slot.index,
                locKey = inventorySlotToKey(slot.index),
                stack = stack.copy()
            )
        }
        return snapshot
    }

    private fun captureInventoryBySlot(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player?
    ): Map<Int, InventorySnapshotEntry> {
        if (player == null) return emptyMap()
        val snapshot = mutableMapOf<Int, InventorySnapshotEntry>()
        for (slot in menu.slots) {
            if (slot.container != player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            snapshot[slot.index] = InventorySnapshotEntry(
                slotIndex = slot.index,
                locKey = inventorySlotToKey(slot.index),
                stack = stack.copy()
            )
        }
        return snapshot
    }

    data class ShulkerState(
        val uuid: String,
        var state: String, // "block", "item", "inv", "ex-inv"
        var entity_id: String = "",
        var dim: String = "",
        var coords: String = "", // "x,y,z"
        var last_update_time: String = "",
        
        // Internal metadata
        var name: String = "",
        var happy: Boolean = false,
        var contentHash: String = "",
        var type: String = "minecraft:shulker_box",
        var cachedContents: ByteArray? = null,
        var from: String = "",
        var lastLocation: String? = null,
        var lastKnown: Boolean = false,
        // In-container slot position for chest/ender states (-1 = n/a). Kept
        // separate from coords (which identifies the container, empty for ender)
        // so the synthetic ender identity (slot + content) survives wtf:uuid wipes.
        var slotIndex: Int = -1,
        // First time this entry was created; deterministic tiebreak when multiple
        // identical-content boxes collide on hash so identities don't swap.
        var firstSeen: Long = 0
    )

    data class ShulkerSave(
        val tracked_shulkers: Map<String, ShulkerState> = emptyMap(),
        val version: Int = 15,
        val nextSerial: Int = 1,
        val markerIcon: String? = null,
        val markerColorIdx: Int? = null,
        val chestSlotLedger: Map<String, Map<String, String>>? = null
    )

    data class UiSettings(
        val previewBlur: Boolean = true,
        val showMatchPercent: Boolean = true,
        val itemGlow: Boolean = true
    )

    private var uiSettings = UiSettings()
    var toggleHappyKeyBinding: net.minecraft.client.KeyMapping? = null

    fun getToggleHappyKeyDisplayName(): String? {
        val kb = toggleHappyKeyBinding ?: return null
        val s = kb.saveString()
        if (s == "NONE" || s.isEmpty()) return null
        return try { InputConstants.getKey(s).displayName.string } catch (_: Exception) { null }
    }

    private fun getUiSettingsFile(): File {
        val baseDir = Minecraft.getInstance().gameDirectory
        return File(baseDir, "config/wtf/ui_settings.json")
    }

    private fun loadUiSettings() {
        val file = getUiSettingsFile()
        if (!file.exists()) return
        try {
            uiSettings = gson.fromJson(file.readText(), UiSettings::class.java) ?: UiSettings()
        } catch (e: Exception) {
            log("loadUiSettings failed: $e")
        }
    }

    private fun saveUiSettings() {
        val file = getUiSettingsFile()
        file.parentFile.mkdirs()
        file.writeText(gson.toJson(uiSettings))
    }

    fun isPreviewBlurEnabled(): Boolean = uiSettings.previewBlur

    fun setPreviewBlurEnabled(value: Boolean) {
        uiSettings = uiSettings.copy(previewBlur = value)
        saveUiSettings()
    }

    fun isShowMatchPercentEnabled(): Boolean = uiSettings.showMatchPercent

    fun isDebugModeEnabled(): Boolean = debugMode
    fun isDebugMode(): Boolean = debugMode

    fun clearAllRecords() {
        trackedShulkers.clear()
        slotLedger.clear()
        persistedChestLedger.clear()
        transitOrder.clear()
        save()
        log("clearAllRecords: all records wiped")
    }

    fun setShowMatchPercentEnabled(value: Boolean) {
        uiSettings = uiSettings.copy(showMatchPercent = value)
        saveUiSettings()
    }

    fun isItemGlowEnabled(): Boolean = uiSettings.itemGlow

    fun setItemGlowEnabled(value: Boolean) {
        uiSettings = uiSettings.copy(itemGlow = value)
        saveUiSettings()
    }

    override fun onInitializeClient() {
        val category = net.minecraft.client.KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("wtf", "mod")
        )
        val keyBinding = net.minecraft.client.KeyMapping(
            "key.wtf.shulker_list", InputConstants.Type.KEYSYM, -1, category
        )
        KeyMappingHelper.registerKeyMapping(keyBinding)

        val toggleHappyKeyBindingLocal = net.minecraft.client.KeyMapping(
            "key.wtf.toggle_happy", InputConstants.Type.KEYSYM, -1, category
        )
        toggleHappyKeyBinding = toggleHappyKeyBindingLocal
        KeyMappingHelper.registerKeyMapping(toggleHappyKeyBindingLocal)

        // KeyMapping.click()/isDown are never updated while any Screen is open
        // (Minecraft's KeyboardHandler returns early for non-debug keys in that
        // case), so the toggle needs to hook the screen's raw key events directly
        // to fire while hovering a slot in an open container.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
                ScreenKeyboardEvents.afterKeyPress(screen).register { _, keyEvent ->
                    if (toggleHappyKeyBinding?.matches(keyEvent) == true) {
                        toggleHappyForHoveredSlot()
                    }
                }
            }
        }

        loadUiSettings()

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (keyBinding.consumeClick()) {
                val entries = resolveHappyShulkers()
                client.setScreen(ShulkerGridScreen(entries))
            }

            while (toggleHappyKeyBinding?.consumeClick() == true) {
                toggleHappyForHoveredSlot()
            }

            val player = client.player ?: return@register
            val level = client.level ?: return@register
            tickCounter++

            // 1. Handle pending item entities (block -> item)
            if (pendingItemEntities.isNotEmpty()) {
            val iterator = pendingItemEntities.iterator()
            val claimedEntityIds = trackedShulkers.values
                .asSequence()
                .filter { it.state == "item" }
                .mapNotNull { it.entity_id.toIntOrNull() }
                .toMutableSet()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (tickCounter - pending.tick > 200) {
                    iterator.remove()
                    continue
                }

                val center = pending.pos.center
                val items = level.getEntitiesOfClass(ItemEntity::class.java, AABB.ofSize(center, 4.0, 4.0, 4.0))
                val found = items
                    .asSequence()
                    .filter { isShulkerItem(it.item) }
                    .filter { it.id !in claimedEntityIds }
                    .minByOrNull { it.distanceToSqr(center.x, center.y, center.z) }
                if (found != null) {
                    val shulker = trackedShulkers[pending.uuid]
                    if (shulker != null) {
                        shulker.state = "item"
                        shulker.entity_id = found.id.toString()
                        shulker.dim = level.dimension().identifier().toString()
                        shulker.coords = "${found.x},${found.y},${found.z}"
                        shulker.last_update_time = System.currentTimeMillis().toString()
                        shulker.from = "tick:found_entity"
                        log("block -> item: found entity ${found.id} for shulker ${shulker.name} (uuid=${pending.uuid})")
                        if (shulker.happy) notify("§eblock§f → §7item§f §7(${shulker.name})§f")
                        claimedEntityIds.add(found.id)
                        if (shulker.happy && isItemGlowEnabled()) (found as EntityAccessor).invokeSetSharedFlag(6, true)
                    }
                    iterator.remove()
                }
            }
            }

            // 1b. Handle pending drop entities (Q-drop): item data may not have
            // synced when ENTITY_LOAD fired, so retry for a few ticks.
            run {
                val dropIterator = pendingDropEntities.iterator()
                val readyEntities = mutableListOf<Pair<ItemEntity, Int>>()
                while (dropIterator.hasNext()) {
                    val (entityId, spawnTick) = dropIterator.next()
                    if (tickCounter - spawnTick > 200) {
                        dropIterator.remove()
                        continue
                    }
                    val entity = level.getEntity(entityId) as? ItemEntity ?: continue
                    if (!isShulkerItem(entity.item)) continue
                    dropIterator.remove()
                    readyEntities.add(entity to spawnTick)
                }

                pruneDropExpectations()
                if (readyEntities.isNotEmpty()) {
                    val claimedUUIDs = mutableSetOf<String>()
                    var anyMatched = false

                    // A "block" entry can also match if the drop spawned at its
                    // block position (broken by another player, explosion, ...).
                    fun matchableState(e: ShulkerState, entity: ItemEntity): Boolean {
                        if (e.state == "inv" || e.state == "ex-inv" || e.state == "enderchest") return true
                        if (e.state == "block") {
                            val c = parseVec3(e.coords) ?: return false
                            return entity.distanceToSqr(c.first + 0.5, c.second + 0.5, c.third + 0.5) < 9.0
                        }
                        return false
                    }

                    // Pass 0: intentional drops announced by LocalPlayer.drop /
                    // menu throw clicks. FIFO by drop order - own drops spawn
                    // near the player, so require proximity. Binds identity
                    // before content components have even synced.
                    val afterExpect = mutableListOf<Pair<ItemEntity, Int>>()
                    for (re in readyEntities) {
                        val entity = re.first
                        val entityType = BuiltInRegistries.ITEM.getKey(entity.item.item).toString()
                        val entityHash = fingerprintFromItem(entity.item)
                        if (entityHash == null && dropExpectations.any { it.type == entityType }) {
                            // Components not synced yet - can't verify against the
                            // expectation's fingerprint. Requeue instead of binding
                            // blind (a broken unrelated box would steal identity).
                            pendingDropEntities.add(entity.id to re.second)
                            continue
                        }
                        val expect = if (entity.distanceToSqr(player) < 36.0) {
                            dropExpectations.firstOrNull {
                                it.type == entityType && it.hash == entityHash && it.uuid !in claimedUUIDs &&
                                    trackedShulkers[it.uuid]?.let { e -> matchableState(e, entity) } == true
                            }
                        } else null
                        if (expect != null) {
                            dropExpectations.remove(expect)
                            val match = trackedShulkers[expect.uuid]!!
                            claimedUUIDs.add(match.uuid)
                            injectItemUUID(entity.item, match.uuid)
                            applyDropMatch(match, entity, level)
                            log("drop expect: bound entity ${entity.id} to ${match.uuid.take(8)}")
                            anyMatched = true
                        } else {
                            afterExpect.add(re)
                        }
                    }

                    // Pass 1: identity match via wtf:uuid (survives if uuid is still
                    // present in CUSTOM_DATA on the dropped stack).
                    val unmatched = mutableListOf<Pair<ItemEntity, Int>>()
                    for (re in afterExpect) {
                        val entity = re.first
                        val itemUUID = getItemUUID(entity.item)
                        val match = itemUUID?.let { trackedShulkers[it] }
                            ?.takeIf { matchableState(it, entity) && it.uuid !in claimedUUIDs }
                        if (match != null) {
                            claimedUUIDs.add(match.uuid)
                            applyDropMatch(match, entity, level)
                            anyMatched = true
                        } else {
                            unmatched.add(re)
                        }
                    }

                    // Pass 2: content-hash match, but only when the hash is
                    // distinguishing (non-empty/named box). A generic empty/unnamed
                    // box's hash is shared by every box of that color (tracked or
                    // not), so it's never used to match - that's what let untracked
                    // Y steal a tracked entry's identity before.
                    for (re in unmatched) {
                        val entity = re.first
                        // CONTAINER/CUSTOM_NAME components may not have synced yet at
                        // this exact tick - if the hash is unusable, requeue and retry
                        // on a later tick rather than giving up permanently.
                        val hash = fingerprintFromItem(entity.item)
                        val itemId = BuiltInRegistries.ITEM.getKey(entity.item.item).toString()
                        if (hash == null || hash == genericEmptyHash(itemId)) {
                            pendingDropEntities.add(entity.id to re.second)
                            continue
                        }
                        val match = trackedShulkers.values.singleOrNull {
                            matchableState(it, entity) && it.uuid !in claimedUUIDs && it.contentHash == hash
                        }
                        if (match != null) {
                            claimedUUIDs.add(match.uuid)
                            applyDropMatch(match, entity, level)
                            anyMatched = true
                        } else {
                            pendingDropEntities.add(entity.id to re.second)
                        }
                    }

                    if (anyMatched) save()
                }
            }

            // 2. Track item entity positions; detect vanished item entities
            // (sucked by hopper / shulker unloader / despawn) and mark last-known.
            run {
                var vanishChanged = false
                for (shulker in trackedShulkers.values) {
                    if (shulker.state != "item" || shulker.entity_id.isEmpty()) {
                        itemVanishTicks.remove(shulker.uuid)
                        itemVanishInvCount.remove(shulker.uuid)
                        continue
                    }
                    val entityId = shulker.entity_id.toIntOrNull() ?: continue
                    val entity = level.getEntity(entityId)
                    if (entity != null) {
                        itemVanishTicks.remove(shulker.uuid)
                        itemVanishInvCount.remove(shulker.uuid)
                        shulker.coords = "${entity.x},${entity.y},${entity.z}"
                        shulker.last_update_time = System.currentTimeMillis().toString()
                        if (shulker.happy) {
                            val glowing = isItemGlowEnabled()
                            (entity as EntityAccessor).invokeSetSharedFlag(6, glowing)
                        }
                        continue
                    }
                    // Entity gone. If the player picked it up, the inventory scan
                    // will flip the entry to inv shortly - give it a grace window.
                    val coords = parseVec3(shulker.coords)
                    val pos = coords?.let { BlockPos(it.first.toInt(), it.second.toInt(), it.third.toInt()) }
                    if (pos == null || !level.hasChunkAt(pos)) {
                        itemVanishTicks.remove(shulker.uuid)
                        itemVanishInvCount.remove(shulker.uuid)
                        continue
                    }
                    val firstMiss = itemVanishTicks.getOrPut(shulker.uuid) { tickCounter }
                    if (firstMiss == tickCounter) {
                        // Baseline: shulker stacks in inventory before the vanish.
                        // Hash comparison is unreliable across the sync boundary
                        // (hashItemAndComponents differs), so pickup is detected
                        // by the inventory shulker count increasing instead. Use
                        // the previous tick's count - the pickup slot update may
                        // already have landed this tick.
                        itemVanishInvCount[shulker.uuid] = prevInvShulkerCount
                    }
                    // Pickup signal can be confirmed early, every tick of the
                    // grace window - only the LK verdict needs the full window.
                    val baseline = itemVanishInvCount[shulker.uuid] ?: Int.MAX_VALUE
                    if (entryInPlayerInventory(player, shulker) || countInvShulkers(player) > baseline) {
                        // Picked up (uuid possibly wiped by sync). Flip to inv and
                        // queue a scan to re-link the entry to the stack.
                        itemVanishTicks.remove(shulker.uuid)
                        itemVanishInvCount.remove(shulker.uuid)
                        transferToInv(shulker, "vanish:pickup")
                        continue
                    }
                    if (tickCounter - firstMiss < 10) continue
                    itemVanishTicks.remove(shulker.uuid)
                    itemVanishInvCount.remove(shulker.uuid)
                    shulker.lastLocation = "${shulker.dim}:${shulker.coords}"
                    shulker.state = "ex-inv"
                    shulker.lastKnown = true
                    shulker.entity_id = ""
                    shulker.last_update_time = System.currentTimeMillis().toString()
                    shulker.from = "tick:item_vanished"
                    log("transition: ${shulker.name} (${shulker.uuid.take(8)}) item → ex-inv from=tick:item_vanished lastKnown=true coords=${shulker.coords}")
                    if (shulker.happy) notify("§7item§f → §c[LK]§f §7(${shulker.name})§f")
                    vanishChanged = true
                }
                if (vanishChanged) save()
                // Baseline only matters while an entry is in "item" state — skip
                // the full inventory sweep otherwise.
                if (trackedShulkers.values.any { it.state == "item" }) {
                    prevInvShulkerCount = countInvShulkers(player)
                }
            }

            // 3. Periodically verify blocks in loaded chunks to avoid stale position data.
            // Placed blocks are checked every 5 ticks (cheap blockstate read, and
            // piston/external breaks need fast reaction); ex-inv containers every 40.
            if (tickCounter % 5 == 0 && postJoinGraceTicks <= 0) {
                var changed = false
                val currentDim = level.dimension().identifier().toString()
                for (shulker in trackedShulkers.values) {
                    val isPlacedBlock = shulker.state == "block"
                    val isExInvVerified = shulker.state == "ex-inv" && !shulker.lastKnown && tickCounter % 40 == 0

                    if ((isPlacedBlock || isExInvVerified) && shulker.dim == currentDim && shulker.coords.isNotEmpty()) {
                        val coords = parseVec3(shulker.coords)
                        if (coords != null) {
                            val pos = BlockPos(coords.first.toInt(), coords.second.toInt(), coords.third.toInt())
                            if (level.hasChunkAt(pos)) {
                                val blockState = level.getBlockState(pos)
                                val blockId = BuiltInRegistries.BLOCK.getKey(blockState.block).toString()
                                val stillExists = when {
                                    shulker.state == "ex-inv" -> {
                                        // Block entities sync a few ticks AFTER their chunk
                                        // (separate packets), so right after relog/chunk-load
                                        // getBlockEntity is transiently null even though the
                                        // chest is there. Only treat it as gone when the block
                                        // itself no longer has any block entity - otherwise the
                                        // BE just hasn't synced yet and we'd false-stale it.
                                        level.getBlockEntity(pos) is BaseContainerBlockEntity ||
                                            blockState.hasBlockEntity()
                                    }
                                    else -> {
                                        blockId.contains("shulker_box")
                                    }
                                }
                                // Mid-push: block is a moving_piston entity for a few
                                // ticks. Defer judgment to the next sweep.
                                if (!stillExists && blockId == "minecraft:moving_piston") continue
                                if (!stillExists && shulker.state == "block") {
                                    // Pistons can push shulker boxes (movable block
                                    // entity exception). Check the 6 neighbors for a
                                    // same-type shulker block not claimed by another
                                    // tracked entry before declaring it lost.
                                    val pushed = listOf(
                                        pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()
                                    ).firstOrNull { np ->
                                        val nbId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(np).block).toString()
                                        nbId.contains("shulker_box") && nbId == shulker.type &&
                                            trackedShulkers.values.none { o ->
                                                o.uuid != shulker.uuid && o.state == "block" && o.dim == currentDim &&
                                                    o.coords == "${np.x},${np.y},${np.z}"
                                            }
                                    }
                                    if (pushed != null) {
                                        shulker.lastLocation = "${shulker.dim}:${shulker.coords}"
                                        shulker.coords = "${pushed.x},${pushed.y},${pushed.z}"
                                        shulker.last_update_time = System.currentTimeMillis().toString()
                                        shulker.from = "tick:piston_moved"
                                        log("transition: ${shulker.name} (${shulker.uuid.take(8)}) block → block from=tick:piston_moved coords=${shulker.coords}")
                                        changed = true
                                        continue
                                    }
                                }
                                if (!stillExists && shulker.state == "block") {
                                    // Block gone but a matching shulker item entity is
                                    // lying nearby (piston break, explosion): uuid/hash
                                    // matching can fail across the sync boundary, so
                                    // fall back to proximity + item type.
                                    val claimedIds = trackedShulkers.values
                                        .filter { it.state == "item" }
                                        .mapNotNull { it.entity_id.toIntOrNull() }
                                        .toSet()
                                    val dropped = level.getEntitiesOfClass(
                                        ItemEntity::class.java,
                                        net.minecraft.world.phys.AABB(pos).inflate(4.0)
                                    ).firstOrNull { e ->
                                        e.id !in claimedIds && isShulkerItem(e.item) &&
                                            BuiltInRegistries.ITEM.getKey(e.item.item).toString() == shulker.type &&
                                            (getItemUUID(e.item) ?: shulker.uuid) == shulker.uuid
                                    }
                                    if (dropped != null) {
                                        injectItemUUID(dropped.item, shulker.uuid)
                                        applyDropMatch(shulker, dropped, level)
                                        changed = true
                                        continue
                                    }
                                }
                                // Block break was just detected and is awaiting
                                // resolution to "item" via pendingItemEntities -
                                // don't race ahead and stale-clear it here.
                                if (!stillExists && shulker.state == "block" && pendingItemEntities.any { it.uuid == shulker.uuid }) continue
                                if (!stillExists && entryInPlayerInventory(player, shulker)) {
                                    transferToInv(shulker, "tick:block_in_inv")
                                    changed = true
                                    continue
                                }
                                if (!stillExists) {
                                    log("tick verify: block at ${shulker.coords} is now $blockId (was expected to hold shulker ${shulker.name}). Marking as last-known.")
                                    shulker.lastLocation = "${shulker.dim}:${shulker.coords}"
                                    shulker.state = "ex-inv"
                                    shulker.lastKnown = true
                                    shulker.last_update_time = System.currentTimeMillis().toString()
                                    shulker.from = "tick:stale_clear"
                                    changed = true
                                }
                            }
                        }
                    }
                }
                if (changed) {
                    save()
                }
            }

            val currentSlot = player.inventory.selectedSlot
            val currentStack = player.inventoryMenu.getSlot(currentSlot).item
            val currentUUID = getItemUUID(currentStack)

            if (currentSlot != prevSelectedSlot || currentUUID != prevHeldUUID) {
                prevSelectedSlot = currentSlot
                prevHeldUUID = currentUUID
                scanQueued = true
                throttleTicks = THROTTLE_WINDOW
            }

            if (postJoinGraceTicks > 0) postJoinGraceTicks--

            if (scanQueued) {
                if (throttleTicks > 0 || postJoinGraceTicks > 0) {
                    if (throttleTicks > 0) throttleTicks--
                } else {
                    performInventoryScan(level, player)
                    scanQueued = false
                }
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommands.literal("wtf")
                .executes {
                    val entries = resolveHappyShulkers()
                    Minecraft.getInstance().setScreen(ShulkerGridScreen(entries))
                    1
                }
                .then(ClientCommands.literal("debug")
                    .executes {
                        debugMode = !debugMode
                        debugVerbose = false
                        val status = if (debugMode) "§aon§f" else "§coff§f"
                        Minecraft.getInstance().gui.chat.addClientSystemMessage(
                            Component.literal("§7[§fWTF§f] Debug $status")
                        )
                        if (debugMode) {
                            val id = getWorldId()
                            if (id != null) {
                                val file = getLogFile(id)
                                file.parentFile.mkdirs()
                                file.delete()
                                logWriter = file.bufferedWriter().let { PrintWriter(it) }
                                logBytesWritten = 0
                                log("--- debug logging started ---")
                            }
                        } else {
                            logWriter?.close()
                            logWriter = null
                        }
                        1
                    }
                    .then(ClientCommands.literal("verbose")
                        .executes {
                            if (!debugMode) {
                                debugMode = true
                                debugVerbose = true
                                val id = getWorldId()
                                if (id != null) {
                                    val file = getLogFile(id)
                                    file.parentFile.mkdirs()
                                    file.delete()
                                    logWriter = file.bufferedWriter().let { PrintWriter(it) }
                                    logBytesWritten = 0
                                    log("--- debug logging started ---")
                                }
                            } else {
                                debugVerbose = !debugVerbose
                            }
                            val status = if (debugVerbose) "§averbose§f" else if (debugMode) "§aon (standard)§f" else "§coff§f"
                            Minecraft.getInstance().gui.chat.addClientSystemMessage(
                                Component.literal("§7[§fWTF§f] Debug $status")
                            )
                            1
                        }
                    )
                )
            )
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            load()
        }

        // Detect items being dropped (inv -> item)
        // ItemEntity's DATA_ITEM (the actual ItemStack) is not guaranteed to be
        // synced yet at ENTITY_LOAD time, so just queue the entity id and check
        // its item over the next few ticks (see pendingDropEntities below).
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is ItemEntity) {
                val player = Minecraft.getInstance().player
                // 2 blocks is too tight under latency - by ENTITY_LOAD time the
                // player may have moved past the drop's synced spawn position.
                val nearPlayer = player != null && entity.distanceToSqr(player) < 36.0
                // Also catch drops near a tracked placed block: covers breaks by
                // other players, explosions, etc. while we're in render distance.
                val nearTrackedBlock = !nearPlayer && trackedShulkers.values.any { e ->
                    e.state == "block" && parseVec3(e.coords)?.let { c ->
                        entity.distanceToSqr(c.first + 0.5, c.second + 0.5, c.third + 0.5) < 9.0
                    } == true
                }
                if (isShulkerItem(entity.item) || entity.item.isEmpty) {
                    log("ENTITY_LOAD item entity ${entity.id}: distSq=${if (player != null) entity.distanceToSqr(player) else -1.0} nearPlayer=$nearPlayer nearTrackedBlock=$nearTrackedBlock item=${entity.item}")
                }
                // Self-drops can be picked back up (auto-pickup while standing
                // still) before DATA_ITEM ever syncs as non-empty - the entity
                // is gone again by the next tick, so the normal pendingDropEntities
                // hash-match never gets a chance to run. If we're holding a
                // matching drop expectation and this is an empty-item entity
                // spawning right at the player, bind it immediately (we already
                // know identity/type from the expectation, recorded pre-drop).
                if (entity.item.isEmpty && nearPlayer && dropExpectations.isNotEmpty()) {
                    val expect = dropExpectations.firstOrNull {
                        trackedShulkers[it.uuid]?.state == "inv"
                    }
                    val lvl = entity.level() as? Level
                    if (expect != null && lvl != null) {
                        dropExpectations.remove(expect)
                        val match = trackedShulkers[expect.uuid]!!
                        applyDropMatch(match, entity, lvl)
                        log("drop expect: immediate-bound entity ${entity.id} to ${match.uuid.take(8)} (empty-item, ENTITY_LOAD)")
                        save()
                        return@register
                    }
                }
                if (nearPlayer || nearTrackedBlock) {
                    pendingDropEntities.add(entity.id to tickCounter)
                }
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            currentWorldId = null
            trackedShulkers.clear()
            nextSerial = 1
            prevSelectedSlot = -1
            prevHeldUUID = null
            scanQueued = false
            throttleTicks = 0
            tickCounter = 0
            transitOrder.clear()
            pendingItemEntities.clear()
            logWriter?.close()
            logWriter = null
            logBytesWritten = 0
        }

        ClientPlayerBlockBreakEvents.AFTER.register { world, _, pos, state ->
            val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            if (!blockId.contains("shulker_box")) return@register
            
            val coordStr = "${pos.x},${pos.y},${pos.z}"
            val dimStr = world.dimension().identifier().toString()
            
            val entry = trackedShulkers.values.firstOrNull { it.state == "block" && it.coords == coordStr && it.dim == dimStr } ?: return@register
            
            pendingItemEntities.add(PendingItemEntity(entry.uuid, pos, tickCounter))
            log("block break: $coordStr ($dimStr) → pending tracking for ${entry.name} (uuid=${entry.uuid})")
        }

        ScreenEvents.AFTER_INIT.register { mc, screen, _, _ ->
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            performInventoryScan(level, player)
            if (screen is ShulkerBoxScreen) {
                handleShulkerScreen(mc, level, player, screen)
                ScreenEvents.remove(screen).register {
                    handleShulkerScreenClosed(screen)
                }
            }
            if (screen is AbstractContainerScreenAccessor && screen !is ShulkerBoxScreen) {
                handleChestScreen(mc, screen)
                ScreenEvents.remove(screen).register {
                    handleChestClosed(screen)
                    val l = Minecraft.getInstance().level
                    val p = Minecraft.getInstance().player
                    if (l != null && p != null) {
                        performInventoryScan(l, p)
                    }
                }
            }
            if (screen is AbstractContainerScreenAccessor) {
                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                    renderHappyMarkers(screen, graphics)
                }
            }
        }
    }

    // Single source of truth for "should this slot show the marker icon".
    // Check stamp first (fast path), then slotLedger fallback (covers cases
    // where the stamp was stripped or never applied, e.g. multiplayer reopen
    // before ContainerSetContent, or eviction edge-cases).
    fun isSlotMarked(stack: ItemStack, slotIndex: Int): Boolean {
        if (stack.isEmpty || !isTrackableShulker(stack)) return false
        val uuid = getItemUUID(stack) ?: slotLedger[ledgerPosKey(slotIndex)] ?: return false
        return trackedShulkers[uuid]?.happy == true
    }

    private fun renderHappyMarkers(screen: Screen, graphics: GuiGraphicsExtractor) {
        if (markerIcon == "none") return
        val container = screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ?: return
        val accessor = screen as AbstractContainerScreenAccessor
        val font = Minecraft.getInstance().font
        for (slot in container.menu.slots) {
            val stack = slot.item
            if (!isSlotMarked(stack, slot.index)) continue
            val x = accessor.leftPos + slot.x
            val y = accessor.topPos + slot.y
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x + 10f, y - 1f)
            pose.scale(0.6f)
            graphics.text(font, markerIcon, 0, 0, getMarkerColor(), true)
            pose.popMatrix()
        }
    }

    private fun handleShulkerScreen(mc: Minecraft, level: Level, player: Player, screen: ShulkerBoxScreen) {
        val a = screen as AbstractContainerScreenAccessor
        val container = (screen.menu as ShulkerBoxMenuAccessor).container
        val pos = getValidShulkerPos(mc, level) ?: return
        val be = level.getBlockEntity(pos) as? BaseContainerBlockEntity
        val shulkerType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        val displayName = screen.title.string
        val contentHash = fingerprintContainer(container, shulkerType, be?.components()?.get(DataComponents.CUSTOM_NAME))
        
        val coordStr = "${pos.x},${pos.y},${pos.z}"
        val dimStr = level.dimension().identifier().toString()

        // Get held shulker UUID to exclude from hash matching
        val heldStack = player.inventoryMenu.getSlot(player.inventory.selectedSlot).item
        val heldUUID = getItemUUID(heldStack)

        val beUUID = be?.let { getBlockEntityUUID(it) }
        var currentKey = when {
            beUUID != null && trackedShulkers.containsKey(beUUID) -> {
                val entry = trackedShulkers[beUUID]!!
                if (entry.state != "block" || entry.coords != coordStr || entry.dim != dimStr) {
                    entry.state = "block"
                    entry.entity_id = ""
                    entry.coords = coordStr
                    entry.dim = dimStr
                    entry.last_update_time = System.currentTimeMillis().toString()
                    entry.from = "hss:update (uuid)"
                    if (entry.happy) notify("§aupdated§f shulker: §e${entry.name}§f")
                }
                beUUID
            }
            else -> trackedShulkers.values.firstOrNull { it.state == "block" && it.coords == coordStr && it.dim == dimStr }?.uuid
        }

        // Fallback to hash if UUID didn't find anything or is missing
        if (currentKey == null) {
            // Skip generic empty-box hashes - shared by every unnamed empty box
            // of this color, so matching on them would merge this block's
            // identity with an unrelated empty box (and stamp that box's stack).
            val hashMatch = if (contentHash == genericEmptyHash(shulkerType)) null else trackedShulkers.values.firstOrNull { k ->
                k.uuid != heldUUID && k.contentHash == contentHash && (k.state == "inv" || k.state == "item")
            }
            if (hashMatch != null) {
                hashMatch.state = "block"
                hashMatch.entity_id = ""
                hashMatch.coords = coordStr
                hashMatch.dim = dimStr
                hashMatch.last_update_time = System.currentTimeMillis().toString()
                hashMatch.from = "hss:hash_match"
                if (hashMatch.happy) notify("§amatch§f → §eblock§f §7(${hashMatch.name})§7")
                currentKey = hashMatch.uuid
                log("handleShulkerScreen: hash-matched key=$currentKey")
            } else {
                // Contents changed since last cached (no uuid stamp yet, e.g.
                // freshly placed) - exact hash can never match. Don't drop the
                // record just because contents moved without us watching;
                // fall back to a unique name match and accept the new
                // contents as current, same as ex-inv recovery does.
                val nameMatch = trackedShulkers.values.singleOrNull { k ->
                    k.uuid != heldUUID && k.happy && k.type == shulkerType && k.name == displayName &&
                        (k.state == "inv" || k.state == "item" || k.state == "ex-inv")
                }
                if (nameMatch != null) {
                    nameMatch.state = "block"
                    nameMatch.entity_id = ""
                    nameMatch.coords = coordStr
                    nameMatch.dim = dimStr
                    nameMatch.lastKnown = false
                    nameMatch.last_update_time = System.currentTimeMillis().toString()
                    nameMatch.from = "hss:name_match"
                    notify("§amatch§f → §eblock§f §7(${nameMatch.name})§7 §7(contents changed)")
                    currentKey = nameMatch.uuid
                    log("handleShulkerScreen: name-matched key=$currentKey (contents changed)")
                } else {
                    log("handleShulkerScreen: no match found, coords=$coordStr beUUID=$beUUID heldUUID=$heldUUID")
                }
            }
        } else {
            log("handleShulkerScreen: matched key=$currentKey")
        }

        openShulkerKey = currentKey

        val currentEntry = currentKey?.let { trackedShulkers[it] }
        val isHappy = currentEntry?.happy ?: false

        if (currentKey != null) {
            val entry = trackedShulkers[currentKey]!!
            val cachedNbt = chooseRicherCache(
                serializeContainerToNbt(container),
                be?.let { serializeContainerToNbt(it) },
                entry.cachedContents
            )
            log("handleShulkerScreen: currentKey=$currentKey cachedNbt=${cachedNbt?.size ?: "NULL"}")
            entry.name = displayName
            entry.contentHash = contentHash
            entry.type = shulkerType
            entry.cachedContents = cachedNbt
        }

        val button = Button.builder(Component.literal(if (isHappy) "\u263A" else "\u2639")) { btn ->
            // Re-fingerprint at click time: the container may have been empty at
            // handleShulkerScreen time (pre-sync) but is populated by click time.
            val currentHash = fingerprintContainer(container, shulkerType, be?.components()?.get(DataComponents.CUSTOM_NAME))
            val currentlyHappy = trackedShulkers[currentKey]?.happy ?: false
            // Empty, unnamed boxes share the generic hash and can't be tracked
            // unambiguously - allow them only once named (unless already marked).
            if (!currentlyHappy && currentHash == genericEmptyHash(shulkerType)) {
                notify("\u00A7ccan't mark an empty unnamed box\u00A7f - name it or add contents first")
                return@builder
            }
            val key = currentKey
            val finalUUID = beUUID ?: key ?: java.util.UUID.randomUUID().toString()
            
            if (beUUID == null) {
                val uuidTag = CompoundTag()
                uuidTag.putString("wtf:uuid", finalUUID)
                val patch = DataComponentPatch.builder()
                    .set(DataComponents.CUSTOM_DATA, CustomData.of(uuidTag))
                    .build()
                // collectComponents() includes implicit components (CONTAINER);
                // be.components() doesn't, and applyComponents rebuilds the BE's
                // itemStacks from the base map - using the cached map wipes the
                // client-side items until the box is reopened.
                be?.applyComponents(be.collectComponents(), patch)
            }

            val entry = trackedShulkers[finalUUID]
            val newHappy = !(entry?.happy ?: false)
            
            if (entry == null) {
                val newCached = chooseRicherCache(
                    serializeContainerToNbt(container),
                    be?.let { serializeContainerToNbt(it) }
                )
                log("toggle happy: new entry $finalUUID containerSize=${container.containerSize} cachedNbt=${newCached?.size ?: "NULL"} items=${newCached?.let { deserializeNbtToItems(it).count { s -> !s.isEmpty } } ?: -1}")
                trackedShulkers[finalUUID] = ShulkerState(
                    uuid = finalUUID,
                    state = "block",
                    dim = dimStr,
                    coords = coordStr,
                    last_update_time = System.currentTimeMillis().toString(),
                    name = displayName,
                    happy = newHappy,
                    contentHash = contentHash,
                    from = "new-entry",
                    type = shulkerType,
                    cachedContents = newCached,
                    firstSeen = System.currentTimeMillis()
                )
            } else {
                entry.happy = newHappy
                entry.name = displayName
                entry.contentHash = contentHash
                entry.type = shulkerType
                val newCached = chooseRicherCache(
                    serializeContainerToNbt(container),
                    be?.let { serializeContainerToNbt(it) },
                    entry.cachedContents
                )
                log("toggle happy: existing entry $finalUUID containerSize=${container.containerSize} cachedNbt=${newCached?.size ?: "NULL"} items=${newCached?.let { deserializeNbtToItems(it).count { s -> !s.isEmpty } } ?: -1}")
                entry.cachedContents = newCached
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = "toggle"
            }
            
            currentKey = finalUUID
            btn.setMessage(Component.literal(if (newHappy) getMarkerIcon() else "☹"))
            if (newHappy) notify("§a${getMarkerIcon()}§f marked: §e${displayName}§f")
            else notify("§7☹§f unmarked: §e${displayName}§f")
            save()
        }
            .pos(a.leftPos + a.imageWidth / 2 - 6, a.topPos + 3)
            .size(12, 12)
            .build()
        Screens.getWidgets(screen).add(button)
    }

    private fun handleShulkerScreenClosed(screen: ShulkerBoxScreen) {
        openShulkerKey = null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val pos = getValidShulkerPos(mc, level) ?: return
        val be = level.getBlockEntity(pos) as? BaseContainerBlockEntity
        val shulkerType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        val container = (screen.menu as ShulkerBoxMenuAccessor).container
        
        val coordStr = "${pos.x},${pos.y},${pos.z}"
        val dimStr = level.dimension().identifier().toString()
        
        val beUUID = be?.let { getBlockEntityUUID(it) }
        val currentKey = beUUID ?: trackedShulkers.values.firstOrNull { it.state == "block" && it.coords == coordStr && it.dim == dimStr }?.uuid
        
        if (currentKey != null) {
            val entry = trackedShulkers[currentKey]
            if (entry != null) {
                val newNbt = serializeContainerToNbt(container)
                val newHash = fingerprintContainer(container, shulkerType, be?.components()?.get(DataComponents.CUSTOM_NAME))
                // Container can already be cleared client-side by the time the
                // close event fires - don't blank a known-good cache with an
                // empty read.
                val newHasItems = newNbt != null && deserializeNbtToItems(newNbt).any { !it.isEmpty }
                if (newHasItems) {
                    entry.cachedContents = newNbt
                    entry.contentHash = newHash
                }
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = "hss:close"
                log("handleShulkerScreenClosed: updated cachedContents and contentHash for happy=${entry.happy} uuid=$currentKey")
                save()
            }
        }
        
        performInventoryScan(level, player)
    }

    private fun performInventoryScan(level: Level, player: Player) {
        val inv = player.inventoryMenu
        val currentSlot = player.inventory.selectedSlot
        val heldStack = player.inventoryMenu.getSlot(currentSlot).item
        
        // 1. Collect all shulker stacks currently in inventory/cursor
        val inventoryShulkers = mutableListOf<Triple<ItemStack, String, String>>() // Stack, locKey, fingerprint
        for (i in 0 until inv.slots.size) {
            val stack = inv.getSlot(i).item
            if (stack.isEmpty || !isTrackableShulker(stack)) continue
            inventoryShulkers.add(Triple(stack, indexToKey(i), fingerprintFromItem(stack) ?: ""))
        }
        if (!inv.carried.isEmpty && isTrackableShulker(inv.carried)) {
            inventoryShulkers.add(Triple(inv.carried, "cursor", fingerprintFromItem(inv.carried) ?: ""))
        }

        // Evict duplicate wtf:uuid stamps in inventory (pre-1.4.9 bad data).
        // If the same uuid appears on >1 stack, strip it from all but the first
        // (lowest slot index wins; entry.coords will confirm the real slot).
        val invUUIDSeen = mutableSetOf<String>()
        for (ss in inventoryShulkers) {
            val uuid = getItemUUID(ss.first) ?: continue
            if (!invUUIDSeen.add(uuid)) {
                stripItemUUID(ss.first)
                log("evict: duplicate inv uuid ${uuid.take(8)} stripped from ${ss.second}")
            }
        }

        val foundUUIDs = mutableSetOf<String>()
        var changed = false

        // 2. Pass 1: Match by existing UUID (Highest confidence)
        for (ss in inventoryShulkers) {
            val uuid = getItemUUID(ss.first)
            if (uuid != null && trackedShulkers.containsKey(uuid)) {
                val entry = trackedShulkers[uuid]!!
                if (entry.state != "inv" || entry.coords != ss.second) {
                    log("transition: ${entry.name} (${uuid.take(8)}) ${entry.state} → inv from=scan:uuid (changed) lastKnown=${entry.lastKnown}")
                    val wasLK = entry.state == "ex-inv" && entry.lastKnown
                    entry.state = "inv"
                    entry.entity_id = ""
                    entry.coords = ss.second
                    entry.dim = level.dimension().identifier().toString()
                    entry.last_update_time = System.currentTimeMillis().toString()
                    entry.from = "scan:uuid"
                    entry.contentHash = ss.third
                    entry.lastKnown = false
                    if (wasLK && entry.happy) notify("§c[LK]§f → §ainv§f §7(${entry.name})§f")
                    changed = true
                } else {
                    log("transition: ${entry.name} (${uuid.take(8)}) inv → inv from=scan:uuid (noop)")
                }
                foundUUIDs.add(uuid)
            } else if (uuid != null) {
                log("scan: found item with UUID $uuid but not in tracked map")
            }
        }

        // 3. Pass 2: Match by hash/state (For items that lost NBT or were just picked up)
        for (ss in inventoryShulkers) {
            val currentUUID = getItemUUID(ss.first)
            if (currentUUID != null && foundUUIDs.contains(currentUUID)) continue

            val hash = ss.third

            val stackType = BuiltInRegistries.ITEM.getKey(ss.first.item).toString()
            val stackName = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "???"
            val transitMatchUuid = transitOrder
                .mapIndexedNotNull { index, candidateUuid ->
                    if (candidateUuid in foundUUIDs) return@mapIndexedNotNull null
                    val candidate = trackedShulkers[candidateUuid] ?: return@mapIndexedNotNull null
                    val ageMs = System.currentTimeMillis() - (candidate.last_update_time.toLongOrNull() ?: 0L)
                    if ((candidate.from != "entity_removed" && candidate.from != "vanish:pickup") || ageMs > 10_000) return@mapIndexedNotNull null
                    if (candidate.type != stackType) return@mapIndexedNotNull null
                    // Pickup can momentarily strip CUSTOM_NAME via server sync, leaving
                    // stackName == "???" - don't let that disqualify the real candidate.
                    if (stackName != "???" && candidate.name != stackName && candidate.name.isNotEmpty()) return@mapIndexedNotNull null

                    val candidateCoords = parseVec3(candidate.coords)
                    val distSq = if (candidateCoords != null) {
                        player.distanceToSqr(candidateCoords.first, candidateCoords.second, candidateCoords.third)
                    } else {
                        Double.MAX_VALUE
                    }
                    Triple(candidateUuid, distSq, index)
                }
                .minWithOrNull(compareBy<Triple<String, Double, Int>> { it.second }.thenBy { it.third })
                ?.first

            if (transitMatchUuid != null) {
                val match = trackedShulkers[transitMatchUuid]!!
                log("scan: transit-matched ${ss.second} to tracked UUID ${match.uuid}")
                injectItemUUID(ss.first, match.uuid)
                match.state = "inv"
                match.entity_id = ""
                match.coords = ss.second
                match.dim = level.dimension().identifier().toString()
                match.last_update_time = System.currentTimeMillis().toString()
                match.from = "scan:transit_match"
                match.contentHash = hash
                foundUUIDs.add(match.uuid)
                transitOrder.remove(match.uuid)
                changed = true
                continue
            }

            if (hash.isEmpty()) continue

            // Try to match with an unclaimed shulker in our map. Skip generic
            // empty-box hashes - shared by every unnamed empty box of this
            // color, so matching on them would stamp two distinct physical
            // stacks with the same uuid (see genericEmptyHash).
            val match = if (hash == genericEmptyHash(stackType)) null else {
                val hashCandidates = trackedShulkers.values.filter {
                    it.uuid !in foundUUIDs && it.contentHash == hash && (it.state == "inv" || it.state == "item" || it.state == "enderchest" || it.state == "block")
                }
                // Prefer candidate last seen at this exact slot so same-hash boxes don't swap identity across scans.
                hashCandidates.firstOrNull { it.coords == ss.second } ?: hashCandidates.firstOrNull()
            }

            if (match != null) {
                val uuid = match.uuid
                log("scan: hash-matched ${ss.second} (hash=${hash.take(8)}) to tracked UUID $uuid")
                injectItemUUID(ss.first, uuid)
                match.state = "inv"
                match.entity_id = ""
                match.coords = ss.second
                match.dim = level.dimension().identifier().toString()
                match.last_update_time = System.currentTimeMillis().toString()
                match.from = "scan:hash_match"
                match.contentHash = hash
                foundUUIDs.add(uuid)
                transitOrder.remove(uuid)
                changed = true
            } else {
                // A custom name is not unique - several boxes can share it. Only
                // re-link by name when exactly one tracked box carries it; with
                // duplicates, name alone can't say which physical box this is, and
                // matching would stamp the same uuid onto every same-named box
                // (icon appears on all). Ambiguous ones fall through to discovery.
                val nameCandidates = trackedShulkers.values.filter {
                    it.uuid !in foundUUIDs &&
                        it.happy &&
                        (it.state == "inv" || it.state == "item" || it.state == "enderchest") &&
                        it.type == stackType &&
                        it.name == stackName
                }
                val nameMatch = nameCandidates.singleOrNull()

                if (nameMatch != null) {
                    log("scan: name-matched ${ss.second} to tracked UUID ${nameMatch.uuid}")
                    injectItemUUID(ss.first, nameMatch.uuid)
                    nameMatch.state = "inv"
                    nameMatch.entity_id = ""
                    nameMatch.coords = ss.second
                    nameMatch.dim = level.dimension().identifier().toString()
                    nameMatch.last_update_time = System.currentTimeMillis().toString()
                    nameMatch.from = "scan:name_match"
                    nameMatch.contentHash = hash
                    foundUUIDs.add(nameMatch.uuid)
                    transitOrder.remove(nameMatch.uuid)
                    changed = true
                    continue
                }

                // Ex-inv / ender recovery: re-link entries that reappeared in inventory
                val recoveryMatch = if (hash == genericEmptyHash(stackType)) null else {
                    val recoveryCandidates = trackedShulkers.values.filter {
                        it.uuid !in foundUUIDs &&
                            (it.state == "ex-inv" || it.state == "enderchest") &&
                            it.contentHash == hash
                    }
                    recoveryCandidates.firstOrNull { it.coords == ss.second } ?: recoveryCandidates.firstOrNull()
                }

                if (recoveryMatch != null) {
                    log("transition: ${recoveryMatch.name} (${recoveryMatch.uuid.take(8)}) ex-inv → inv from=scan:ex_inv_recovery lastKnown=${recoveryMatch.lastKnown}")
                    val wasLK = recoveryMatch.lastKnown
                    injectItemUUID(ss.first, recoveryMatch.uuid)
                    recoveryMatch.state = "inv"
                    recoveryMatch.entity_id = ""
                    recoveryMatch.coords = ss.second
                    recoveryMatch.dim = level.dimension().identifier().toString()
                    recoveryMatch.last_update_time = System.currentTimeMillis().toString()
                    recoveryMatch.from = "scan:ex_inv_recovery"
                    recoveryMatch.lastKnown = false
                    if (wasLK && recoveryMatch.happy) notify("§c[LK]§f → §ainv§f §7(${recoveryMatch.name})§f")
                    foundUUIDs.add(recoveryMatch.uuid)
                    transitOrder.remove(recoveryMatch.uuid)
                    changed = true
                    continue
                }

                // New discovery
                val newUUID = ensureItemUUID(ss.first)
                log("scan: new shulker discovered in ${ss.second}, assigned UUID $newUUID")
                trackedShulkers[newUUID] = ShulkerState(
                    uuid = newUUID,
                    state = "inv",
                    dim = level.dimension().identifier().toString(),
                    coords = ss.second,
                    last_update_time = System.currentTimeMillis().toString(),
                    name = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "???",
                    happy = false,
                    contentHash = hash,
                    from = "scan:new",
                    type = BuiltInRegistries.ITEM.getKey(ss.first.item).toString(),
                    firstSeen = System.currentTimeMillis()
                )
                foundUUIDs.add(newUUID)
                transitOrder.remove(newUUID)
                changed = true
            }
        }

        // 4. Cleanup: Orphan stale inv entries (not in player inventory anymore)
        for (entry in trackedShulkers.values) {
            if (entry.state == "inv" && entry.uuid !in foundUUIDs) {
                entry.state = "ex-inv"
                log("transition: ${entry.name} (${entry.uuid.take(8)}) inv → ex-inv from=scan:cleanup lastKnown=${entry.lastKnown}")
            }
        }

        if (changed) {
            save()
        }
    }


    @JvmStatic
    fun onShulkerPlaced(player: Player, pos: BlockPos, hand: InteractionHand, stack: ItemStack) {
        if (!player.level().isClientSide) return
        if (stack.isEmpty || !isShulkerItem(stack)) return

        val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val hash = fingerprintFromItem(stack) ?: ""
        val uuid = getItemUUID(stack)
        val slotIdx = if (hand == InteractionHand.MAIN_HAND) player.inventory.selectedSlot + 36 else 45
        val locKey = indexToKey(slotIdx)
        
        // Priority 1: UUID from stack
        var resolvedKey = uuid
        
        // Priority 2: Deterministic slot-based match (If state is inv)
        if (resolvedKey == null) {
            resolvedKey = trackedShulkers.values.firstOrNull { it.state == "inv" && it.coords == locKey }?.uuid
        }
        
        // Priority 3: Fallback to hash/name match
        if (resolvedKey == null) {
            val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: ""
            val matches = trackedShulkers.values.filter { 
                it.type == type && (it.name == stackName || stackName.isEmpty()) && it.contentHash == hash
            }
            resolvedKey = matches.firstOrNull { it.happy }?.uuid ?: matches.firstOrNull()?.uuid
        }

        // Final Fallback: New shulker
        val finalUUID = resolvedKey ?: java.util.UUID.randomUUID().toString()
        val entry = trackedShulkers[finalUUID]
        
        val coordStr = "${pos.x},${pos.y},${pos.z}"
        val dimStr = player.level().dimension().identifier().toString()
        val be = player.level().getBlockEntity(pos) as? BaseContainerBlockEntity
        
        if (be != null) {
            val uuidTag = CompoundTag()
            uuidTag.putString("wtf:uuid", finalUUID)
            val patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_DATA, CustomData.of(uuidTag))
                .build()
            // See happy-button note: base must include implicit CONTAINER or
            // applyComponents wipes the BE's client-side items.
            be.applyComponents(be.collectComponents(), patch)
        }
        
        val blockHash = if (be != null) {
            val bType = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString()
            fingerprintContainer(be, bType, be.components().get(DataComponents.CUSTOM_NAME))
        } else hash
        // The placed stack's CONTAINER component is always accurate at this
        // instant; the block entity may not have its contents synced yet
        // right after placement, which would otherwise overwrite the cache
        // with an empty preview until the box is opened.
        val stackContainer = stack.get(DataComponents.CONTAINER)
        val stackHasItems = stackContainer != null && stackContainer.nonEmptyItems().iterator().hasNext()
        val freshNbt = if (stackHasItems || be == null) {
            serializeShulkerContents(stack)
        } else {
            serializeContainerToNbt(be)
        }
        // Don't blank a known-good preview on placement: only adopt the
        // freshly-serialized contents if they actually contain items, since
        // the box's real contents are only confirmed by opening it.
        val freshHasItems = freshNbt != null && deserializeNbtToItems(freshNbt).any { !it.isEmpty }
        val cachedNbt = if (freshHasItems) freshNbt else entry?.cachedContents ?: freshNbt
        
        val displayName = if (stack.has(DataComponents.CUSTOM_NAME)) stack.get(DataComponents.CUSTOM_NAME)?.string ?: "???" else entry?.name ?: "Shulker Box"
        
        if (entry == null) {
            trackedShulkers[finalUUID] = ShulkerState(
                uuid = finalUUID,
                state = "block",
                dim = dimStr,
                coords = coordStr,
                last_update_time = System.currentTimeMillis().toString(),
                name = displayName,
                happy = false,
                contentHash = blockHash,
                from = "place:new",
                type = type,
                cachedContents = cachedNbt,
                firstSeen = System.currentTimeMillis()
            )
        } else {
            entry.state = "block"
            entry.entity_id = ""
            entry.dim = dimStr
            entry.coords = coordStr
            entry.last_update_time = System.currentTimeMillis().toString()
            entry.contentHash = blockHash
            entry.type = type
            entry.cachedContents = cachedNbt
            entry.from = "place"
            if (entry.happy) notify("§aplaced§f → §eblock§f §7(${entry.name})§f")
        }
        
        log("transition: $displayName (${finalUUID.take(8)}) ${entry?.state ?: "new"} → block from=${if (entry == null) "place:new" else "place:existing"} slot=$locKey")
        save()
    }


    fun removeTrackedShulker(uuid: String) {
        val entry = trackedShulkers.remove(uuid) ?: return
        transitOrder.remove(uuid)
        log("transition: ${entry.name} (${uuid.take(8)}) ${entry.state} → removed from=ui:manual_remove")
        save()
    }

    private fun resolveHappyShulkers(): List<ShulkerEntry> {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val entries = mutableListOf<ShulkerEntry>()

        log("resolveHappyShulkers: trackedShulkers=${trackedShulkers.size}")

        for (entry in trackedShulkers.values) {
            if (!entry.happy) continue
            
            val item = BuiltInRegistries.ITEM.get(Identifier.parse(entry.type)).orElse(null)
            val stack = if (item != null) ItemStack(item) else ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX)
            val items = entry.cachedContents?.let { deserializeNbtToItems(it) } ?: List(27) { ItemStack.EMPTY }
            
            val location = when (entry.state) {
                "block" -> "${entry.dim}:${entry.coords}"
                "ex-inv" -> {
                    val baseLoc = "${entry.dim}:${entry.coords}"
                    if (entry.lastKnown) "§c[Last Known]§7 $baseLoc" else baseLoc
                }
                "enderchest" -> if (entry.slotIndex >= 0) "Ender Chest · Slot ${entry.slotIndex + 1}" else "Ender Chest"
                else -> entry.coords
            }
            
            val shulkerEntry = ShulkerEntry(
                id = entry.uuid,
                name = Component.literal(entry.name),
                stack = stack,
                section = entry.state,
                location = location,
                shortHash = entry.uuid.take(6),
                serial = 0,
                items = items,
                cachedContentsNbt = entry.cachedContents,
                from = entry.from,
                lastLocation = entry.lastLocation,
                lastKnown = entry.lastKnown
            )
            entries.add(shulkerEntry)
        }

        log("resolveHappyShulkers: returning ${entries.size} entries")
        return entries
    }

    private fun getValidShulkerPos(mc: Minecraft, level: Level): BlockPos? {
        val hr = mc.hitResult
        if (hr !is net.minecraft.world.phys.BlockHitResult) return null
        val pos = hr.blockPos
        val blockId = try {
            BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        } catch (_: Exception) { return null }
        return if (blockId.contains("shulker_box")) pos else null
    }

    // Memoized per Item: registry key lookup + string alloc is too hot for
    // the per-slot per-tick call sites, and an Item's key never changes.
    private val shulkerItemCache = java.util.IdentityHashMap<net.minecraft.world.item.Item, Boolean>()

    private fun isShulkerItem(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        return shulkerItemCache.getOrPut(stack.item) {
            BuiltInRegistries.ITEM.getKey(stack.item).toString().contains("shulker_box")
        }
    }

    // A stack of >1 is untrackable: stamping a wtf:uuid onto it tags every box
    // in the stack at once (they share one ItemStack), so the marker icon shows
    // on all of them and they toggle as a unit. Boxes with contents never stack
    // even with stacking mods enabled, so a real tracked box is always count==1;
    // only empty boxes can stack, and those carry no distinguishing identity.
    private fun isTrackableShulker(stack: ItemStack): Boolean =
        isShulkerItem(stack) && stack.count == 1

    private fun fingerprintItems(shulkerId: String, customName: String, items: NonNullList<ItemStack>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shulkerId.toByteArray(Charsets.UTF_8))
        digest.update(customName.toByteArray(Charsets.UTF_8))
        if (debugVerbose) log("fingerprintItems: id=$shulkerId name=$customName")
        for (i in 0 until items.size) {
            val s = items[i]
            if (s.isEmpty) {
                digest.update(0.toByte())
            } else {
                if (debugVerbose) {
                    val itemId = BuiltInRegistries.ITEM.getKey(s.item).toString()
                    log("fingerprintItems:   slot[$i] = $itemId x${s.count} hash=${ItemStack.hashItemAndComponents(s)}")
                }
                digest.update(1.toByte())
                intToBytes(stableItemHash(s)).forEach { digest.update(it) }
            }
        }
        val result = digest.digest().toHex()
        if (debugVerbose) log("fingerprintItems: => $result")
        return result
    }

    private fun fingerprintContainer(container: Container, shulkerType: String, customName: Component?): String {
        val items = NonNullList.withSize(container.containerSize, ItemStack.EMPTY)
        for (i in 0 until container.containerSize) items[i] = container.getItem(i)
        if (debugVerbose) log("fingerprintContainer: size=${container.containerSize} type=$shulkerType name=${customName?.string ?: ""}")
        return fingerprintItems(shulkerType, customName?.string ?: "", items)
    }

    private val genericEmptyHashCache = mutableMapOf<String, String>()

    // Hash of an empty, unnamed shulker box of this type. Any tracked entry
    // whose contentHash equals this is indistinguishable from any other
    // empty/unnamed box of the same color - too ambiguous to drop-match.
    private fun genericEmptyHash(itemId: String): String {
        return genericEmptyHashCache.getOrPut(itemId) {
            fingerprintItems(itemId, "", NonNullList.withSize(27, ItemStack.EMPTY))
        }
    }

    private fun fingerprintFromItem(stack: ItemStack): String? {
        if (!isShulkerItem(stack)) return null
        val container = stack.get(DataComponents.CONTAINER)
        val items = NonNullList.withSize(27, ItemStack.EMPTY)
        container?.copyInto(items)
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: ""
        if (debugVerbose) log("fingerprintFromItem: name=$stackName via ${if (container != null) "CONTAINER" else "EMPTY"}")
        return fingerprintItems(itemId, stackName, items)
    }

    private fun blockLocation(pos: BlockPos, level: Level): String {
        val dim = level.dimension().identifier().toString()
        return "${dim}:${pos.x}_${pos.y}_${pos.z}"
    }

    private fun parseBlockLoc(loc: String): BlockPos? {
        val lastColon = loc.lastIndexOf(':')
        if (lastColon < 0) return null
        val coords = loc.substring(lastColon + 1)
        val parts = coords.split("_")
        if (parts.size != 3) return null
        val x = parts[0].toIntOrNull() ?: return null
        val y = parts[1].toIntOrNull() ?: return null
        val z = parts[2].toIntOrNull() ?: return null
        return BlockPos(x, y, z)
    }

    private fun parseVec3(coords: String): Triple<Double, Double, Double>? {
        val parts = coords.split(",")
        if (parts.size != 3) return null
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        val z = parts[2].toDoubleOrNull() ?: return null
        return Triple(x, y, z)
    }

    private fun chooseRicherCache(vararg candidates: ByteArray?): ByteArray? {
        return candidates
            .filterNotNull()
            .maxWithOrNull(
                compareBy<ByteArray> { deserializeNbtToItems(it).count { stack -> !stack.isEmpty } }
                    .thenBy { it.size }
            )
    }

    private fun intToBytes(v: Int) = byteArrayOf(
        (v shr 24).toByte(),
        (v shr 16).toByte(),
        (v shr 8).toByte(),
        v.toByte()
    )

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun getWorldId(): String? {
        val mc = Minecraft.getInstance()
        val server = mc.currentServer
        if (server != null) return "server_${sanitize(server.ip)}"
        val sp = mc.singleplayerServer
        if (sp != null) return "local_${sanitize(sp.worldData.levelName)}"
        return null
    }

    private fun getConfigFile(worldId: String): File {
        val baseDir = Minecraft.getInstance().gameDirectory
        return File(baseDir, "config/wtf/$worldId/moods.json")
    }

    private fun getLogFile(worldId: String): File {
        val baseDir = Minecraft.getInstance().gameDirectory
        return File(baseDir, "config/wtf/$worldId/debug.log")
    }

    private fun sanitize(s: String) = s.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    private fun load() {
        val id = getWorldId() ?: return
        currentWorldId = id
        prevSelectedSlot = -1
        prevHeldUUID = null
        scanQueued = false
        throttleTicks = 0
        postJoinGraceTicks = 100
        tickCounter = 0
        if (debugMode) {
            val logFile = getLogFile(id)
            logFile.parentFile.mkdirs()
            logFile.delete()
            logWriter = logFile.bufferedWriter().let { PrintWriter(it) }
            logBytesWritten = 0
            log("--- debug logging started ---")
        }
        val file = getConfigFile(id)
        if (!file.exists()) {
            trackedShulkers.clear()
            nextSerial = 1
            return
        }
        try {
            val json = file.readText()
            log("load: reading ${json.length} chars from ${file.name}")
            val save = gson.fromJson(json, ShulkerSave::class.java)
            trackedShulkers.clear()
            trackedShulkers.putAll(save.tracked_shulkers)
            // Backfill firstSeen for pre-v15 saves so the identity tiebreak has a
            // stable order. Stagger by last_update_time when available so the
            // relative age ordering is preserved rather than collapsed to a tie.
            trackedShulkers.values.filter { it.firstSeen == 0L }.forEach {
                it.firstSeen = it.last_update_time.toLongOrNull() ?: 1L
            }
            nextSerial = save.nextSerial
            markerIcon = save.markerIcon?.takeIf { it in markerIcons } ?: markerIcons[0]
            markerColorIdx = save.markerColorIdx?.takeIf { it in markerColorOptions.indices } ?: 0
            persistedChestLedger.clear()
            save.chestSlotLedger?.forEach { (loc, slots) -> persistedChestLedger[loc] = slots.toMutableMap() }
            val totalCached = save.tracked_shulkers.values.count { it.cachedContents != null }
            log("load: loaded tracked_shulkers=${save.tracked_shulkers.size}, cachedContents=$totalCached")
        } catch (e: Exception) { 
            log("load failed: $e")
        }
    }

    private fun getItemUUID(stack: ItemStack): String? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        if (data.isEmpty) return null
        val tag = data.copyTag()
        if (!tag.contains("wtf:uuid")) return null
        val uuid = tag.getString("wtf:uuid")
        return if (uuid is java.util.Optional<*>) {
            (uuid as java.util.Optional<String>).orElse(null)
        } else {
            uuid.toString()
        }?.takeIf { it.isNotEmpty() }
    }

    private fun getBlockEntityUUID(be: BaseContainerBlockEntity): String? {
        val data = be.components().get(DataComponents.CUSTOM_DATA) ?: return null
        if (data.isEmpty) return null
        val tag = data.copyTag()
        if (!tag.contains("wtf:uuid")) return null
        val uuid = tag.getString("wtf:uuid")
        return if (uuid is java.util.Optional<*>) {
            (uuid as java.util.Optional<String>).orElse(null)
        } else {
            uuid.toString()
        }?.takeIf { it.isNotEmpty() }
    }

    // hashItemAndComponents includes CUSTOM_DATA, so a wtf:uuid stamp on a
    // nested shulker box - which server resync wipes on rejoin - changes
    // the hash of whatever box contains it. Strip wtf:uuid before hashing
    // so contentHash fingerprints stay stable across restarts.
    private fun stableItemHash(stack: ItemStack): Int {
        val data = stack.get(DataComponents.CUSTOM_DATA)
        if (data == null || data.isEmpty) return ItemStack.hashItemAndComponents(stack)
        val tag = data.copyTag()
        if (!tag.contains("wtf:uuid")) return ItemStack.hashItemAndComponents(stack)
        val stripped = stack.copy()
        tag.remove("wtf:uuid")
        if (tag.isEmpty) {
            stripped.remove(DataComponents.CUSTOM_DATA)
        } else {
            stripped.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
        return ItemStack.hashItemAndComponents(stripped)
    }

    private fun ensureItemUUID(stack: ItemStack): String {
        val existing = getItemUUID(stack)
        if (existing != null) return existing
        val uuid = java.util.UUID.randomUUID().toString()
        injectItemUUID(stack, uuid)
        return uuid
    }

    private fun stripItemUUID(stack: ItemStack) {
        val current = stack.get(DataComponents.CUSTOM_DATA) ?: return
        val tag = current.copyTag()
        if (!tag.contains("wtf:uuid")) return
        tag.remove("wtf:uuid")
        if (tag.isEmpty) {
            stack.remove(DataComponents.CUSTOM_DATA)
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        }
    }

    // After the slot ledger is fully built, evict stale/duplicate wtf:uuid
    // stamps from chest slots. Handles two cases:
    //   1. Same uuid on >1 slot (pre-1.4.9 blanket name-match left duplicates) -
    //      keep only the ledger-confirmed slot, strip the rest. If none is
    //      confirmed, strip all (they're orphaned bad stamps).
    //   2. Single carrier but ledger assigns that slot a different uuid - stale
    //      stamp from a box that no longer occupies this slot.
    private fun evictOrphanUUIDStamps(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player?
    ) {
        val uuidSlots = mutableMapOf<String, MutableList<Pair<Int, ItemStack>>>()
        for (slot in menu.slots) {
            if (player != null && slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val uuid = getItemUUID(stack) ?: continue
            uuidSlots.getOrPut(uuid) { mutableListOf() }.add(slot.index to stack)
        }
        val ledgerOwner = slotLedger.entries.associate { (pos, uuid) -> uuid to pos }
        for ((uuid, slots) in uuidSlots) {
            if (slots.size == 1) {
                val (idx, stack) = slots[0]
                val thisPos = ledgerPosKey(idx)
                val ledgerUUID = slotLedger[thisPos]
                if (ledgerUUID != null && ledgerUUID != uuid) {
                    stripItemUUID(stack)
                    slotLedger.remove(thisPos)
                    log("evict: orphan uuid ${uuid.take(8)} at slot $idx (ledger has ${ledgerUUID.take(8)})")
                }
            } else {
                val ownerPos = ledgerOwner[uuid]
                for ((idx, stack) in slots) {
                    val pos = ledgerPosKey(idx)
                    if (pos != ownerPos) {
                        stripItemUUID(stack)
                        if (slotLedger[pos] == uuid) slotLedger.remove(pos)
                        log("evict: duplicate uuid ${uuid.take(8)} at slot $idx (owner=$ownerPos)")
                    }
                }
            }
        }
    }

    private fun injectItemUUID(stack: ItemStack, uuid: String) {
        val current = stack.get(DataComponents.CUSTOM_DATA) ?: CustomData.EMPTY
        val tag = current.copyTag()
        val existingOpt = tag.getString("wtf:uuid")
        val existing = if (existingOpt is java.util.Optional<*>) {
            (existingOpt as java.util.Optional<String>).orElse(null)
        } else {
            existingOpt.toString()
        }
        if (existing == uuid) return
        tag.putString("wtf:uuid", uuid)
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
    }

    // Toggles the happy tag for whatever shulker box ItemStack the cursor is
    // hovering in any open container screen (player inv or external chest).
    // Item stacks carry their CONTAINER component everywhere, so contents
    // can be fingerprinted/cached without placing or opening the box.
    private fun toggleHappyForHoveredSlot() {
        val mc = Minecraft.getInstance()
        val screen = mc.screen

        // When a placed shulker block is open, the keybind should toggle the
        // block itself - not whatever content slot the cursor happens to hover.
        if (screen is ShulkerBoxScreen) {
            val key = openShulkerKey
            if (key == null) {
                notify("§cshulker not yet tracked§f - use the button or wait a moment")
                return
            }
            val entry = trackedShulkers[key]
            val newHappy = !(entry?.happy ?: false)
            if (entry == null) {
                notify("§cshulker entry missing§f")
                return
            }
            entry.happy = newHappy
            entry.last_update_time = System.currentTimeMillis().toString()
            entry.from = "keybind-toggle"
            if (newHappy) notify("§a${getMarkerIcon()}§f marked: §e${entry.name}§f")
            else notify("§7☹§f unmarked: §e${entry.name}§f")
            save()
            return
        }

        if (screen !is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            return
        }
        val slot = (screen as AbstractContainerScreenAccessor).hoveredSlot ?: return
        val stack = slot.item
        if (stack.isEmpty || !isShulkerItem(stack)) {
            return
        }
        if (stack.count > 1) {
            notify("§ccan't mark a stack§f - split to a single box first")
            return
        }

        val displayName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: stack.hoverName.string
        val shulkerType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val contentHash = fingerprintFromItem(stack) ?: ""
        // An empty, unnamed box has no distinguishing fingerprint (shares the
        // generic hash with every other empty box of its color), so tracking it
        // is inherently ambiguous. Allow empties only once they're named.
        if (contentHash == genericEmptyHash(shulkerType)) {
            notify("§ccan't mark an empty unnamed box§f - name it or add contents first")
            return
        }
        val cachedNbt = serializeShulkerContents(stack)
        val player = mc.player

        // A stack without a stamped uuid may still correspond to an existing
        // tracked entry that just hasn't been hash-matched yet (e.g. an
        // unmatched duplicate in this chest's seedChestSlotUUIDs pass).
        // Resolving it the same way avoids minting a fresh uuid that "steals"
        // this slot from the real entry on chest close, which marks the real
        // entry's last known location as stale (LK).
        val hasCustomName = stack.has(DataComponents.CUSTOM_NAME)
        val isExternal = !(player != null && slot.container == player.inventory)
        val posKey = ledgerPosKey(slot.index)
        val uuid = getItemUUID(stack) ?: run {
            if (isExternal) {
                // Slot-anchored marking: the user pointed at a specific physical
                // slot, so a box only inherits an existing identity when the slot
                // ledger pins it HERE (and it still matches). Never re-link by
                // content alone - that steals an identical box's identity and
                // parks the marker on the wrong slot. Pin the slot immediately so
                // close/reopen keeps the marker exactly where it was marked.
                val chestLoc = if (openChestIsEnderChest) "Ender Chest"
                    else mc.level?.let { lv -> openChestPos?.let { blockLocation(it, lv) } } ?: ""
                val ledgerValues = slotLedger.values
                val pinned = (slotLedger[posKey]
                    ?: persistedChestLedger[chestLoc]?.get(posKey)?.takeIf { it !in ledgerValues })
                    ?.takeIf {
                        it in trackedShulkers &&
                            ledgerEntryMatchesStack(trackedShulkers[it]!!, contentHash, displayName, shulkerType, hasCustomName)
                    }
                val resolved = pinned ?: ensureItemUUID(stack)
                slotLedger[posKey] = resolved
                if (chestLoc.isNotEmpty()) persistedChestLedger.getOrPut(chestLoc) { mutableMapOf() }[posKey] = resolved
                if (getItemUUID(stack) != resolved) injectItemUUID(stack, resolved)
                resolved
            } else {
                // Inventory slot: performInventoryScan already re-links boxes via
                // hash/transit match before the user presses the key. If the stack
                // still has no uuid at this point, it's genuinely new - mint one.
                // Do NOT call resolveTrackedChestStack here: name-match would steal
                // a tracked entry from a DIFFERENT physical box with the same name
                // (e.g. marking Kitt #2 re-links it to Kitt #1's uuid → both toggle).
                ensureItemUUID(stack)
            }
        }

        // Evict any duplicate uuid stamps from other chest slots now that this
        // slot is authoritatively pinned in the ledger. Stale stamps from
        // pre-1.4.9 blanket name-matching would otherwise make every same-named
        // box show the marker icon.
        if (isExternal) {
            val containerScreen = screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
            if (containerScreen != null) {
                evictOrphanUUIDStamps(containerScreen.menu, player)
            }
        }

        val entry = trackedShulkers[uuid]
        val newHappy = !(entry?.happy ?: false)
        if (entry == null) {
            val state = if (!isExternal) "inv" else if (openChestIsEnderChest) "enderchest" else "ex-inv"
            trackedShulkers[uuid] = ShulkerState(
                uuid = uuid,
                state = state,
                last_update_time = System.currentTimeMillis().toString(),
                name = displayName,
                happy = newHappy,
                contentHash = contentHash,
                from = "keybind-toggle",
                type = shulkerType,
                cachedContents = cachedNbt,
                slotIndex = if (isExternal) slot.index else -1,
                firstSeen = System.currentTimeMillis()
            )
        } else {
            entry.happy = newHappy
            entry.name = displayName
            entry.contentHash = contentHash
            entry.type = shulkerType
            entry.cachedContents = chooseRicherCache(cachedNbt, entry.cachedContents)
            entry.last_update_time = System.currentTimeMillis().toString()
            entry.from = "keybind-toggle"
            if (isExternal) entry.slotIndex = slot.index
        }
        if (newHappy) notify("§a${getMarkerIcon()}§f marked: §e${displayName}§f")
        else notify("§7☹§f unmarked: §e${displayName}§f")
        save()
    }

    private fun log(msg: String) {
        if (!debugMode) return
        val w = logWriter ?: return
        if (logBytesWritten >= MAX_LOG_BYTES) return
        w.println(msg)
        w.flush()
        logBytesWritten += msg.toByteArray(Charsets.UTF_8).size + 1
    }

    private fun notify(msg: String) {
        if (!debugMode) return
        // Strip Private Use Area chars: some resource packs (e.g. Redstone Tweaks)
        // map these to large guide-table bitmaps, blowing up chat if present in item names.
        val sanitized = msg.replace(Regex("[\\uE000-\\uF8FF]"), "")
        Minecraft.getInstance().gui.chat.addClientSystemMessage(Component.literal("§7[§fWTF§7] §f$sanitized"))
        log(msg)
    }

    private fun serializeShulkerContents(stack: ItemStack): ByteArray? {
        if (stack.isEmpty || !isShulkerItem(stack)) return null
        val container = stack.get(DataComponents.CONTAINER)
        val items = NonNullList.withSize(27, ItemStack.EMPTY)
        container?.copyInto(items)
        return serializeItemListToNbt(items)
    }

    private fun serializeItemListToNbt(items: List<ItemStack>): ByteArray? {
        return try {
            val registries = Minecraft.getInstance().level?.registryAccess() ?: return null
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val listTag = ListTag()
            for (i in 0 until 27) {
                val stack = items.getOrNull(i) ?: ItemStack.EMPTY
                val tag = if (stack.isEmpty) {
                    CompoundTag()
                } else {
                    ItemStack.CODEC.encodeStart(ops, stack).result().orElse(CompoundTag())
                }
                listTag.add(tag)
            }
            val root = CompoundTag()
            root.put("items", listTag)
            val out = ByteArrayOutputStream()
            NbtIo.writeCompressed(root, out)
            out.toByteArray()
        } catch (e: Exception) {
            log("serializeItemListToNbt failed: $e")
            null
        }
    }

    private fun serializeContainerToNbt(container: Container): ByteArray? {
        val items = NonNullList.withSize(27, ItemStack.EMPTY)
        for (i in 0 until minOf(container.containerSize, 27)) {
            items[i] = container.getItem(i)
        }
        return serializeItemListToNbt(items)
    }

    fun deserializeNbtToItems(data: ByteArray?): List<ItemStack> {
        if (data == null || data.isEmpty()) {
            return List(27) { ItemStack.EMPTY }
        }
        return try {
            val registries = Minecraft.getInstance().level?.registryAccess()
                ?: return List(27) { ItemStack.EMPTY }
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtAccounter.unlimitedHeap())
            val listTag = root.getListOrEmpty("items")
            val result = MutableList(27) { ItemStack.EMPTY }
            for (i in 0 until minOf(27, listTag.size)) {
                val tag = listTag.get(i) as? CompoundTag ?: continue
                if (!tag.isEmpty) {
                    result[i] = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY)
                }
            }
            result
        } catch (e: Exception) {
            log("deserializeNbtToItems failed: $e")
            List(27) { ItemStack.EMPTY }
        }
    }

    private fun save() {
        val id = currentWorldId ?: return
        val file = getConfigFile(id)
        file.parentFile.mkdirs()

        val happyShulkers = trackedShulkers.filterValues { it.happy }

        val totalCached = happyShulkers.values.count { it.cachedContents != null }
        val totalBytes = happyShulkers.values.mapNotNull { it.cachedContents?.size ?: 0 }.sum()

        if (happyShulkers.isEmpty() && !file.exists() && markerIcon == markerIcons[0] && markerColorIdx == 0) return

        val prunedLedger = persistedChestLedger.mapValues { (_, slots) ->
            slots.filterValues { it in trackedShulkers }
        }.filterValues { it.isNotEmpty() }

        val json = gson.toJson(ShulkerSave(
            version = 15,
            nextSerial = nextSerial,
            tracked_shulkers = happyShulkers,
            markerIcon = markerIcon,
            markerColorIdx = markerColorIdx,
            chestSlotLedger = prunedLedger
        ))
        log("save: tracked_shulkers=${happyShulkers.size}, cachedContents=$totalCached (${totalBytes}B), json=${json.length} chars")
        file.writeText(json)
    }
}

internal fun indexToKey(index: Int): String {
    return when (index) {
        0 -> "craft:output"
        in 1..4 -> "craft:input:${index}"
        in 5..8 -> {
            val name = when(index) {
                5 -> "helmet"
                6 -> "chestplate"
                7 -> "leggings"
                8 -> "boots"
                else -> index.toString()
            }
            "armor:$name"
        }
        in 9..35 -> "inv:${index - 9 + 1}"
        in 36..44 -> "hotbar:${index - 36 + 1}"
        45 -> "offhand"
        else -> "slot:$index"
    }
}

internal fun inventorySlotToKey(index: Int): String {
    return when (index) {
        in 0..8 -> "hotbar:${index + 1}"
        in 9..35 -> "inv:${index - 9 + 1}"
        40 -> "offhand"
        else -> "slot:$index"
    }
}

internal fun keyToLabel(key: String): String {
    if (key.startsWith("hotbar:")) return "Hotbar ${key.substringAfter(":")}"
    if (key.startsWith("inv:")) return "Inventory ${key.substringAfter(":")}"
    if (key.startsWith("armor:")) return "Armor: ${key.substringAfter(":").replaceFirstChar { it.uppercase() }}"
    if (key == "offhand") return "Offhand"
    if (key == "craft:output") return "Crafting Output"
    if (key.startsWith("craft:input:")) return "Crafting Input ${key.substringAfterLast(":")}"
    return key
}

internal fun keyToIndex(key: String): Int? {
    if (key == "craft:output") return 0
    if (key.startsWith("craft:input:")) return key.substringAfterLast(":").toIntOrNull()
    if (key.startsWith("armor:")) {
        return when(key.substringAfter(":")) {
            "helmet" -> 5
            "chestplate" -> 6
            "leggings" -> 7
            "boots" -> 8
            else -> null
        }
    }
    if (key.startsWith("inv:")) return key.substringAfter(":").toIntOrNull()?.let { it + 9 - 1 }
    if (key.startsWith("hotbar:")) return key.substringAfter(":").toIntOrNull()?.let { it + 36 - 1 }
    if (key == "offhand") return 45
    if (key.startsWith("slot:")) return key.substringAfter(":").toIntOrNull()
    return key.toIntOrNull() // Backwards compatibility for old saves
}

internal fun slotLabel(loc: String): String {
    return keyToLabel(loc)
}
