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
    // Debug jars ship a /wtf_debug.flag marker resource (see build.gradle.kts
    // debugJar); its presence turns logging on from the first tick. Normal jars
    // start off and rely on /wtf debug.
    // The Loom dev client is only ever run to watch what the mod does, so it
    // starts in verbose - no /wtf debug verbose to remember before reproducing
    // something, and chat feedback (notify) is on from the first tick too.
    // Guarded: the unit tests initialize this object in a plain JVM with no
    // loader, and an exception here takes the whole class down with it.
    private val isDevEnv = try {
        net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment
    } catch (e: Throwable) {
        false
    }
    private var debugMode = isDevEnv || WTFClient::class.java.getResource("/wtf_debug.flag") != null
    private var debugVerbose = isDevEnv
    private var logWriter: PrintWriter? = null
    private var logBytesWritten = 0
    private var logDirty = false
    private val MAX_LOG_BYTES = 20_000_000
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

    // Every collection in this object is unsynchronized (HashMap, ArrayDeque,
    // IdentityHashMap). Packet handlers run once on the network thread before
    // being rescheduled onto the main thread, so any mixin entry point
    // reachable from packet handling has to skip the network-thread pass or it
    // races the tick and render threads into a ConcurrentModificationException.
    //
    // ponytail: guard the handful of entry points instead of locking every
    // collection. If a producer ever appears that has no main-thread pass at
    // all, re-dispatch with mc.execute { } rather than dropping the event.
    private fun onMainThread(): Boolean = Minecraft.getInstance().isSameThread

    fun onEntityRemoved(entityId: Int, reason: net.minecraft.world.entity.Entity.RemovalReason) {
        if (!onMainThread()) return
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
            // A stack another record holds by position is that record's box.
            val key = indexToKey(i)
            if (trackedShulkers.values.any { it !== entry && it.state == "inv" && it.coords == key }) continue
            if (uuid == null && entry.contentHash.isNotEmpty() &&
                fingerprintFromItem(stack) == entry.contentHash
            ) return true
        }
        return false
    }

    // A record that says the box is somewhere physical the client can still
    // see it: placed as a block, or lying on the ground as a live entity. Such
    // a record cannot be a box that just turned up in the inventory or a chest,
    // however alike the contents. With the placed record's hash fixed, an
    // identical twin arriving in the inventory was handed the PLACED box's
    // identity (scenario "F with an identical twin placed nearby"). An unloaded
    // block counts as still there - boxes do not move while nobody is near.
    fun stillWhereRecorded(e: ShulkerState): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        return when (e.state) {
            "block" -> {
                val c = e.coords.split(',').mapNotNull { it.toIntOrNull() }
                if (c.size != 3) return false
                val pos = BlockPos(c[0], c[1], c[2])
                !level.isLoaded(pos) || level.getBlockState(pos).block is net.minecraft.world.level.block.ShulkerBoxBlock
            }
            // Never, for a box on the ground. Its record leaves "item" only through
            // the paths built for that - the take packet, the vanish check with
            // real evidence, a hopper making it last-known - and content matching
            // may have it after that. Looking for the entity instead raced the
            // network: after a relog the ground item arrived after the scan, and
            // the unmarked twin in the hotbar took the marked twin's record
            // (found by WtfFuzz, seed 14).
            "item" -> true
            else -> false
        }
    }

    // The inventory slot an "inv" record remembers still holds a box with its
    // contents. Stamp-free on purpose: the server has usually just wiped it.
    private fun stillAtRememberedSlot(player: Player, e: ShulkerState): Boolean {
        val index = when {
            e.coords.startsWith("hotbar:") -> e.coords.removePrefix("hotbar:").toIntOrNull()?.minus(1)
            e.coords.startsWith("inv:") -> e.coords.removePrefix("inv:").toIntOrNull()?.plus(8)
            e.coords == OFFHAND_POS -> OFFHAND_SLOT
            else -> null
        } ?: return false
        val stack = player.inventory.getItem(index)
        return !stack.isEmpty && isShulkerItem(stack) && fingerprintFromItem(stack) == e.contentHash
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
        if (match.happy) trace("§ainv§f → §7item§f §7(${match.name})§f")
        if (match.happy && isItemGlowEnabled()) (entity as EntityAccessor).invokeSetSharedFlag(6, true)
    }

    // Server's ClientboundTakeItemEntityPacket: authoritative "entity was
    // collected by collector". Fires for player and mob pickup, not hoppers -
    // so a take packet aimed at our player is proof of pickup, no inventory
    // count heuristics needed.
    fun onTakeItemEntity(itemEntityId: Int, collectorId: Int) {
        val mc = Minecraft.getInstance()
        if (!onMainThread()) return
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
                val oldState = entry.state
                entry.state = "inv"
                entry.entity_id = ""
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = from
                transitOrder.remove(entry.uuid)
                transitOrder.addLast(entry.uuid)
                // Only the most recent moves are ever useful as scan hints, and
                // performInventoryScan walks this deque per stack - unbounded it
                // grows for the whole session.
                while (transitOrder.size > MAX_TRANSIT_SPARE) transitOrder.removeFirst()
                log("transition: ${entry.name} (${entry.uuid.take(8)}) $oldState → inv from=$from")
                if (entry.happy) trace("§7→ §ainv§f §7(${entry.name})§f")
                // Refresh contentHash from inventory so ENTITY_LOAD can match on Q-drop.
                // On a real multiplayer server the pickup packet can hand back a stack
                // that never got our wtf:uuid stamp at all (the server doesn't echo
                // client-only NBT through that round-trip) - if so, don't wait for the
                // next throttled scan to notice: find it by content hash right now and
                // re-stamp immediately, so the marker is correct the instant you open
                // your inventory instead of possibly a few ticks later.
                val invMenu = player.inventoryMenu
                var foundStamped = false
                for (i in 0 until invMenu.slots.size) {
                    val stack = invMenu.getSlot(i).item
                    if (isShulkerItem(stack) && getItemUUID(stack) == entry.uuid) {
                        fingerprintFromItem(stack)?.let { entry.contentHash = it }
                        foundStamped = true
                        break
                    }
                }
                if (!foundStamped && entry.contentHash.isNotEmpty()) {
                    for (i in 0 until invMenu.slots.size) {
                        val stack = invMenu.getSlot(i).item
                        if (isTrackableShulker(stack) && getItemUUID(stack) == null &&
                            fingerprintFromItem(stack) == entry.contentHash) {
                            injectItemUUID(stack, entry.uuid)
                            log("transferToInv: re-stamped ${entry.name} (${entry.uuid.take(8)}) immediately, server pickup dropped the tag")
                            break
                        }
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
    // Set by renderHappyMarkers when it sees one uuid stamped on two stacks;
    // cleared by the tick handler, which does the actual repair. Render must
    // stay read-only - see dedupeOpenContainerStamps.
    private var dupStampPending: Boolean = false
    private var throttleTicks: Int = 0
    private var hotbarHealTicks: Int = 0
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


    // Block the player last right-clicked, recorded at click time. The container
    // screen only arrives a round-trip later, so mc.hitResult at that point is
    // wherever the crosshair drifted to - see handleChestScreen.
    private var lastUsedBlockPos: BlockPos? = null
    private var lastUsedBlockTick: Int = Int.MIN_VALUE
    private val USED_BLOCK_TTL = 60

    // Screen instance we already attached a close handler to - see AFTER_INIT.
    private var closeHandlerScreen: Screen? = null

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

    // The offhand is not a slot in a chest/ender menu - ChestMenu builds the
    // container plus player inventory indices 0..35 and nothing else - but the
    // offhand swap key (F) moves stacks in and out of it from inside an open
    // container anyway. Every menu.slots walk below therefore has a hole the
    // size of one slot, which is how a box swapped out of an ender chest stayed
    // recorded as still being in it. Synthesize the slot at its player-
    // inventory index (inventorySlotToKey already labels 40 "offhand").
    private const val OFFHAND_SLOT = 40
    private const val OFFHAND_POS = "offhand"

    // Null when this menu already has a real slot for the offhand stack (the
    // player's own inventory screen does), so it is never counted twice.
    private fun liveOffhand(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player?
    ): ItemStack? {
        if (player == null) return null
        val stack = player.getItemInHand(InteractionHand.OFF_HAND)
        if (stack.isEmpty || !isShulkerItem(stack)) return null
        // Identity, not index: the menu's slot indices are its own container's
        // space, and only the actual backing stack identifies a duplicate.
        if (menu.slots.any { it.item === stack }) return null
        return stack
    }

    private fun captureMenuShulkers(menu: net.minecraft.world.inventory.AbstractContainerMenu, copy: Boolean): MutableMap<String, ItemStack> {
        val snap = mutableMapOf<String, ItemStack>()
        for (slot in menu.slots) {
            val s = slot.item
            if (!s.isEmpty && isShulkerItem(s)) snap[ledgerPosKey(slot.index)] = if (copy) s.copy() else s
        }
        val carried = menu.carried
        if (!carried.isEmpty && isShulkerItem(carried)) snap["cursor"] = if (copy) carried.copy() else carried
        liveOffhand(menu, Minecraft.getInstance().player)?.let {
            snap[OFFHAND_POS] = if (copy) it.copy() else it
        }
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
        if (!onMainThread()) return
        val player = Minecraft.getInstance().player ?: return
        // A container packet is the server's copy of the stack - no stamp. Opening
        // any chest resends the whole inventory, so every box the player carries
        // came back bare, and stayed bare until the throttled scan got round to it
        // up to two seconds later. In that window two look-alike boxes, one marked,
        // are identical stacks to anything that compares components - itemscroller's
        // alt-move took both, or one, depending on when you clicked (reported
        // 2026-09-24). Scan at the end of this tick instead: packets are handled
        // before the tick and clicks after the frame, so the stamps are back first.
        scanQueued = true
        throttleTicks = 0
        val menu = player.containerMenu
        if (menu.containerId != ledgerMenuId) return
        if (isWorkbenchMenu(menu)) return
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

    fun onMenuClickPost(
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        slotId: Int,
        button: Int,
        swap: Boolean,
    ) {
        val pre = clickPreSnapshot ?: return
        clickPreSnapshot = null
        if (menu.containerId != ledgerMenuId) return
        val post = captureMenuShulkers(menu, copy = false)
        val player = Minecraft.getInstance().player

        fun sameStack(a: ItemStack, b: ItemStack) =
            ItemStack.hashItemAndComponents(a) == ItemStack.hashItemAndComponents(b)

        fun isPlayerInvPos(pos: String): Boolean {
            if (pos == OFFHAND_POS) return true
            if (pos == "cursor" || player == null) return false
            val idx = pos.removePrefix("s").toIntOrNull() ?: return false
            return menu.slots.getOrNull(idx)?.container == player.inventory
        }

        // slotLedger only tracks non-player-inventory slots (see seedLedger),
        // so a shulker dragged OUT of the player's own inventory into an open
        // chest has no ledger entry to find here - fall back to its live
        // stamp (survives local click prediction even though slotLedger
        // never saw it) so the move is still caught instantly instead of
        // waiting on the slower packet-confirmed repair pass.
        fun heldElsewhere(uuid: String, pos: String) =
            slotLedger.any { (k, v) -> v == uuid && k != pos } ||
                menu.slots.any { ledgerPosKey(it.index) != pos && getItemUUID(it.item) == uuid }

        fun resolveUUID(pos: String, stack: ItemStack): String? =
            slotLedger[pos]
                ?: getItemUUID(stack)?.takeIf { it in trackedShulkers }
                // Position memory, for the player's own slots. The stamp is gone
                // from any stack the server has just answered for - which is every
                // click round-trip, not only a resync - and slotLedger keeps
                // container slots only. The scan's pass 0 already treats this
                // mapping as authoritative; without it here a box in the offhand
                // could not be identified at all, so half of every F swap was
                // dropped and the next press paired the leftovers across each
                // other.
                ?: invMemoryKey(pos, menu, player)?.let { key ->
                    trackedShulkers.values.firstOrNull { it.state == "inv" && it.coords == key }?.uuid
                }
                // Last resort: the fingerprint. Without this the click ledger
                // was stamp-or-nothing, so a box whose stamp the server had
                // wiped could not be followed across a menu at all - which is
                // how an anvil rename lost its identity (the ledger never saw
                // the move, adoptRename never ran, and the renamed box was
                // adopted afterwards as a brand new entry).
                // ...unless that record's own box is visibly somewhere else in this
                // menu. Two identical boxes in a chest, one marked: the unmarked
                // twin has no record, so the fingerprint had exactly one owner to
                // offer - the marked box's - and picking the twin up handed it the
                // marked identity while the real one lost its stamp to the
                // duplicate eviction. Reported from the dev client 2026-09-24.
                ?: fingerprintOwner(stack)?.takeIf { !heldElsewhere(it, pos) }

        // Positions whose tracked shulker left, and positions that gained a
        // (new or different) shulker stack this click.
        val lost = pre.filter { (k, old) -> resolveUUID(k, old) != null && (post[k]?.let { !sameStack(old, it) } ?: true) }
        val gained = post.filter { (k, now) -> pre[k]?.let { !sameStack(it, now) } ?: true }.toMutableMap()

        // Every identity resolved from the PRE-click state, before the loop below
        // starts writing to slotLedger - which resolveUUID reads. A swap puts two
        // positions in `lost` (the slot and the cursor exchange stacks in one
        // click), and the first iteration's write was being read back by the
        // second as the identity of a different stack: put an unmarked box into
        // the anvil slot holding a marked one and the marked box's uuid, name and
        // content hash were handed to the box that displaced it, while the real
        // one came back on the cursor as an unrecognised stranger.
        val lostUUIDs = lost.mapValues { (pos, old) -> resolveUUID(pos, old) }

        // The two positions a SWAP exchanges, straight from vanilla's own
        // arguments: the clicked slot and the hotbar index, where 40 means the
        // offhand. Every heuristic below infers a destination from what changed,
        // which cannot be right for the one gesture that moves two stacks at once
        // - both of them look like a source and a destination to each other. F
        // twice with a marked box was losing a whole half of the exchange and then
        // pairing the leftovers across each other on the next press.
        val swapPair: Pair<String, String>? = when {
            slotId < 0 || player == null -> null
            swap -> {
                fun playerSlotPos(containerSlot: Int) = menu.slots
                    .firstOrNull { it.container === player.inventory && it.containerSlot == containerSlot }
                    ?.let { ledgerPosKey(it.index) }
                // The offhand is not a slot in a chest menu, and captureMenuShulkers
                // synthesizes it under OFFHAND_POS there - match that spelling.
                val other = if (button == OFFHAND_SLOT) playerSlotPos(OFFHAND_SLOT) ?: OFFHAND_POS
                            else playerSlotPos(button)
                other?.let { ledgerPosKey(slotId) to it }
            }
            // Dropping a held stack onto an OCCUPIED slot moves two stacks as
            // well - the slot's box comes back to the cursor - but vanilla calls
            // that a PICKUP, not a SWAP, so it fell through to the before/after
            // heuristics that cannot resolve a two-stack move. The displaced half
            // was simply never recorded: its record went on claiming a slot
            // another box now held, the scan found the other box there, and
            // cleanup declared the first one gone. Its marker died with it and
            // came back only until the next scan.
            //
            // No guessing needed - a slot that held a shulker before the click and
            // holds a different one after, with a shulker on the cursor to begin
            // with, has exchanged with the cursor by definition.
            else -> {
                val slotPos = ledgerPosKey(slotId)
                val displaced = pre[slotPos]
                val landed = post[slotPos]
                if (displaced != null && pre["cursor"] != null &&
                    landed != null && !sameStack(displaced, landed)
                ) slotPos to "cursor" else null
            }
        }

        for ((fromPos, oldStack) in lost) {
            val uuid = lostUUIDs[fromPos] ?: continue
            // 0. a swap already said where this went
            // takeIf: the counterpart only answers when a shulker actually landed
            // there. Swap one out for a sword and the snapshot has no stack under
            // that key at all, which the code below would dereference.
            var toPos = swapPair?.let { (a, b) ->
                when (fromPos) {
                    a -> b
                    b -> a
                    else -> null
                }
            }?.takeIf { it in post }
            // 1. uuid stamp survived the move
            if (toPos == null) toPos = gained.entries.firstOrNull { getItemUUID(it.value) == uuid }?.key
            // 2. exact same stack landed elsewhere
            if (toPos == null) toPos = gained.entries.firstOrNull { sameStack(oldStack, it.value) }?.key
            // 3. single source, single destination
            if (toPos == null && lost.size == 1 && gained.size == 1) toPos = gained.keys.first()

            slotLedger.remove(fromPos)
            if (toPos != null) {
                // A swap can move the displaced stack into fromPos - only drop
                // mappings we are overwriting, not unrelated ones.
                slotLedger.entries.removeAll { it.key == toPos }
                gained.remove(toPos)
                // Re-stamp: keeps the uuid fast path alive even after server
                // resyncs stripped it from the stack.
                if (!isWorkbenchMenu(menu) && getItemUUID(post[toPos]!!) != uuid) injectItemUUID(post[toPos]!!, uuid)
                if (!isPlayerInvPos(toPos)) slotLedger[toPos] = uuid
                // The scan's pass 0 reads entry.coords and outranks the stamp, so
                // the ledger has to keep it true. It did not, and a SWAP is the one
                // click where that matters: two entries exchange places at once, so
                // the displaced one still named the slot the other had just moved
                // into. The next scan believed it, stamped its uuid onto the wrong
                // box - undoing the correct stamp written three lines above - and
                // the real box, now matching nothing, was cleaned to ex-inv while
                // the one on the cursor was adopted as a stranger.
                val toKey = invMemoryKey(toPos, menu, player)
                if (toKey != null) {
                    trackedShulkers[uuid]?.let { moved ->
                        // A state change that prints no transition: line makes the
                        // log lie, and CHECKLIST.md greps that prefix to record its
                        // scenarios. Flipping silently here had the same entry
                        // logging "inv → ex-inv" twice in a row with nothing in
                        // between, because the way back in was invisible.
                        if (moved.state != "inv") {
                            val wasLK = moved.state == "ex-inv" && moved.lastKnown
                            log("transition: ${moved.name} (${uuid.take(8)}) ${moved.state} → inv from=ledger:click lastKnown=${moved.lastKnown}")
                            moved.state = "inv"
                            moved.entity_id = ""
                            moved.lastKnown = false
                            moved.from = "ledger:click"
                            if (wasLK && moved.happy) trace("§c[LK]§f → §ainv§f §7(${moved.name})§f")
                            save()
                        }
                        moved.coords = toKey
                        moved.dim = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() ?: moved.dim
                        moved.last_update_time = System.currentTimeMillis().toString()
                    }
                } else {
                    // Leaving the player's own slots for a container. The memory of
                    // the slot it vacated has to go with it, or pass 0 hands this
                    // entry's identity to whatever lands there next - and then
                    // fights the duplicate-stamp dedupe over it forever, because
                    // dedupe walks menu order and an anvil's input slots come
                    // before the hotbar. 230 rounds of exactly that in one session.
                    // Only the claim is dropped, not the state: where the box went
                    // is the chest path's answer to give, not ours.
                    invMemoryKey(fromPos, menu, player)?.let { fromKey ->
                        trackedShulkers[uuid]
                            ?.takeIf { it.state == "inv" && it.coords == fromKey }
                            ?.let { it.coords = "" }
                    }
                }
                log("ledger: $fromPos -> $toPos (${uuid.take(8)})")
                logClickMove(uuid, fromPos, toPos)
                adoptRename(uuid, post[toPos]!!)
            } else {
                // Left the menu entirely (thrown out). Register an expectation
                // so the spawn handler binds the item entity by intent, not by
                // content hash.
                log("ledger: $fromPos -> out (${uuid.take(8)})")
                registerDropExpectation(uuid, oldStack, "menu_throw")
            }
        }
    }

    // F with no screen open (ticket 015). The server does the swap and answers
    // with fresh, stamp-less copies of both hands, and the client never moved
    // anything itself - so for the scan it looks like two boxes appearing out of
    // nowhere while position memory still names the old slots. With a marked box
    // in each hand, pass 0 then stamped each hand with the OTHER box's record:
    // the records traded places, measured in WtfScenarios. With one box and an
    // empty offhand it fell to the hash match, which is ticket 010's coin flip
    // reached without opening anything, and it printed no click: line at all.
    //
    // So the swap is recorded when the packet goes out and applied when the
    // server has answered for both hands, in the same click: spelling a GUI swap
    // uses. Not predicted locally: a server plugin can cancel the swap, and then
    // nothing comes back - the entry just times out and nothing has moved.
    private class PendingHandSwap(
        val selected: Int,
        val main: ItemStack,
        val off: ItemStack,
        val mainUUID: String?,
        val offUUID: String?,
        val deadline: Int,
    )
    private var pendingHandSwap: PendingHandSwap? = null

    fun onHandSwapSent() {
        if (!onMainThread()) return
        val player = Minecraft.getInstance().player ?: return
        if (player.isSpectator) return
        val inv = player.inventory
        val selected = inv.selectedSlot
        val main = inv.getItem(selected)
        val off = inv.getItem(OFFHAND_SLOT)
        // Who is in each hand right now, the way pass 0 would answer it.
        fun owner(stack: ItemStack, key: String): String? {
            if (stack.isEmpty || !isTrackableShulker(stack)) return null
            return trackedShulkers.values.firstOrNull { it.state == "inv" && it.coords == key }?.uuid
                ?: getItemUUID(stack)?.takeIf { it in trackedShulkers }
        }
        val mainUUID = owner(main, inventorySlotToKey(selected))
        val offUUID = owner(off, OFFHAND_POS)
        if (mainUUID == null && offUUID == null) return
        pendingHandSwap = PendingHandSwap(selected, main, off, mainUUID, offUUID, tickCounter + 100)
    }

    // Runs every tick and at the top of every scan, so pass 0 never reads the
    // old slots against the new stacks. "Answered" is object identity: a slot
    // packet replaces the ItemStack in the slot, nothing else here does. Two
    // identical boxes swapped produce no answer at all - the server sees nothing
    // changed - and then nothing has changed for us either.
    private fun resolveHandSwap(player: Player) {
        val p = pendingHandSwap ?: return
        val inv = player.inventory
        val main = inv.getItem(p.selected)
        val off = inv.getItem(OFFHAND_SLOT)
        if (main === p.main || off === p.off) {
            if (tickCounter > p.deadline) {
                pendingHandSwap = null
                log("swap: F with no screen was never answered, dropped")
            }
            return
        }
        pendingHandSwap = null
        fun same(a: ItemStack, b: ItemStack) =
            (a.isEmpty && b.isEmpty) ||
                (!a.isEmpty && !b.isEmpty && a.count == b.count && a.item == b.item &&
                    fingerprintFromItem(a) == fingerprintFromItem(b))
        if (!same(off, p.main) || !same(main, p.off)) {
            log("swap: F with no screen was answered with something other than a swap, left to the scan")
            return
        }
        val mainKey = inventorySlotToKey(p.selected)
        val now = System.currentTimeMillis().toString()
        for ((uuid, stack, from, to) in listOf(
            SwapLeg(p.mainUUID, off, mainKey, OFFHAND_POS),
            SwapLeg(p.offUUID, main, OFFHAND_POS, mainKey),
        )) {
            val entry = uuid?.let { trackedShulkers[it] } ?: continue
            entry.coords = to
            entry.last_update_time = now
            injectItemUUID(stack, entry.uuid)
            // logClickMove's spelling - for the player's own slots its label IS
            // the key - so a grep for one gesture finds both.
            log("click: $from > $to ${entry.name} (${entry.uuid.take(8)})")
            if (entry.happy) trace("§7moved§f §e${entry.name}§f: $from §7→§f $to")
        }
        save()
    }

    private data class SwapLeg(val uuid: String?, val stack: ItemStack, val from: String, val to: String)

    // The scan's own key space for stacks the player is carrying - "hotbar:1",
    // "inv:12", "offhand", "cursor" - which is what position memory is keyed by.
    // Null for a container slot: those belong to the chest path, and a box in a
    // chest is not in the inventory however the ledger got it there.
    private fun invMemoryKey(
        pos: String,
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player?,
    ): String? {
        if (pos == "cursor") return "cursor"
        if (pos == OFFHAND_POS) return OFFHAND_POS
        if (player == null) return null
        val slot = pos.removePrefix("s").toIntOrNull()?.let { menu.slots.getOrNull(it) } ?: return null
        return if (slot.container === player.inventory) inventorySlotToKey(playerSlotIndexOf(player, slot)) else null
    }

    // A container move does not change entry.state until the screen closes
    // (handleChestClosed) or the next scan runs, so watching a shift-click live
    // showed nothing but the terse "ledger: s58 -> s1" line. Name the move as it
    // happens, in the same shape the transition lines use. Deliberately NOT
    // logged as "transition:" - nothing has transitioned yet, and CHECKLIST.md
    // greps that prefix for actual state changes.
    private fun logClickMove(uuid: String, fromPos: String, toPos: String) {
        val entry = trackedShulkers[uuid] ?: return
        val player = Minecraft.getInstance().player ?: return
        val menu = player.containerMenu

        fun label(pos: String): String {
            if (pos == "cursor") return "cursor"
            if (pos == OFFHAND_POS) return "offhand"
            val slot = pos.removePrefix("s").toIntOrNull()?.let { menu.slots.getOrNull(it) }
                ?: return pos
            return when {
                slot.container === player.inventory -> inventorySlotToKey(playerSlotIndexOf(player, slot))
                openChestIsEnderChest -> "ender slot ${slot.containerSlot + 1}"
                else -> "container slot ${slot.containerSlot + 1}"
            }
        }

        val from = label(fromPos)
        val to = label(toPos)
        if (from == to) return
        log("click: $from > $to ${entry.name} (${uuid.take(8)})")
        if (entry.happy) trace("§7moved§f §e${entry.name}§f: $from §7→§f $to")
    }

    // An anvil hands back a server-built stack: the client-only wtf:uuid stamp
    // is gone, and the custom name is part of the fingerprint, so a renamed box
    // matches the record by neither uuid nor hash and the scan adopts it as a
    // brand new entry, orphaning the old one. The ledger move above already
    // carried the uuid across the anvil (input slot out, result slot in, one
    // source and one destination); this refreshes what the rename changed, so
    // the record keeps working when the server next strips the stamp.
    //
    // A crafting table dyeing the box is the same shape - the grid eats it and
    // the result is a new stack of a different ITEM - and the record kept the
    // old type for good, so the grid drew the wrong colour and every type check
    // in the scan's fallbacks refused the real box.
    private fun adoptRename(uuid: String, stack: ItemStack) {
        val entry = trackedShulkers[uuid] ?: return
        val newType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        if (isShulkerItem(stack) && newType != entry.type) {
            log("recolour: ${entry.type} -> $newType ${entry.name} (${uuid.take(8)})")
            entry.type = newType
            fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
            entry.last_update_time = System.currentTimeMillis().toString()
            save()
        }
        val newName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
        if (newName == entry.name) return
        // Without this the first menu move of any unnamed box read as a rename:
        // it rewrote the entry's contentHash and saved, on a click where nothing
        // had been renamed at all.
        if (isUnnamed(newName) && isUnnamed(entry.name)) return
        log("rename: ${entry.name} -> $newName (${uuid.take(8)})")
        entry.name = newName
        fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
        entry.last_update_time = System.currentTimeMillis().toString()
        if (entry.happy) trace("\u00A7erenamed\u00A7f \u00A77(${entry.name})\u00A7f")
        save()
    }

    // The single tracked entry whose contents match this stack, or null if there
    // is no match or more than one. Ambiguity has to lose here: two boxes with
    // identical contents are genuinely indistinguishable by fingerprint, and
    // guessing would hand one box's identity to the other.
    private fun fingerprintOwner(stack: ItemStack): String? {
        if (stack.isEmpty || !isTrackableShulker(stack)) return null
        val fp = fingerprintFromItem(stack) ?: return null
        if (fp.isEmpty()) return null
        if (fp == genericEmptyHash(BuiltInRegistries.ITEM.getKey(stack.item).toString())) return null
        // singleOrNull, never firstOrNull: a hash shared by three entries resolves
        // to nothing rather than to whichever one iteration happened to reach
        // first. Ticket 010's reversal let the ex-inv RECOVERY pass claim by count
        // instead of refusing; this site deliberately kept the refusal. Recovery
        // promotes a record that is already the player's and already marked, where
        // a wrong pick swaps two boxes nothing can tell apart. This runs on the
        // click path and mints identity outright - stamp, name and content hash
        // adopted in the same tick - which is where a wrong answer does real harm.
        return trackedShulkers.values.filter { it.contentHash == fp }.singleOrNull()?.uuid
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

    // A double chest is two adjacent single-chest blocks merged by vanilla into
    // one CompoundContainer, which is NOT a BaseContainerBlockEntity - so
    // getContainerBlockPos can't see it, and identity falls back to whichever
    // exact half the player's hitResult landed on. Which half that is varies
    // by which side you click to open it, so the SAME physical chest could
    // get recorded under either half's coordinates on different opens,
    // silently splitting its ledger/persisted history in two. Always resolve
    // to the same canonical half (lower x, tie-break lower z) regardless of
    // which side was actually clicked.
    //
    // The partner is the one the block says it is connected to. This used to
    // take any neighbouring block of the same kind, so two single chests side
    // by side were one chest, and in a wall of double chests a half could pair
    // with the NEIGHBOURING double's half - one physical chest under two
    // coordinates depending on the side clicked (found by WtfFuzz, seed 12).
    private fun canonicalChestPos(pos: BlockPos, level: Level): BlockPos {
        val state = level.getBlockState(pos)
        if (state.block !is net.minecraft.world.level.block.ChestBlock ||
            !state.hasProperty(net.minecraft.world.level.block.ChestBlock.TYPE) ||
            state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) == net.minecraft.world.level.block.state.properties.ChestType.SINGLE
        ) return pos
        val partner = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state))
        return if (partner.x < pos.x || (partner.x == pos.x && partner.z < pos.z)) partner else pos
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

    // The player's own inventory is an AbstractContainerScreen like any other, and
    // its menu has no block entity - so getContainerBlockPos fell through to
    // mc.hitResult and recorded whatever block the crosshair happened to be on.
    // Pressing E while facing a chest therefore "opened" that chest: the close
    // path then ran against the player's own inventory, wrote its contents into
    // that chest's persisted ledger, and could mark boxes sitting in the player's
    // hands as missing from a chest they had never been in.
    //
    // Vanilla reserves container id 0 for the player's own menu and gives every
    // server-opened container a non-zero id, so that is the whole test. It also
    // excludes the creative inventory, which is that same menu underneath.
    private fun isWorldContainer(screen: Any): Boolean {
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu
            ?: return false
        return menu.containerId != 0
    }

    private fun handleChestScreen(mc: Minecraft, screen: Any) {
        val level = mc.level ?: return

        openChestScreenTick = tickCounter
        openChestPos = null
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu
        // Client-side the ender chest menu's container is a plain SimpleContainer
        // (PlayerEnderChestContainer never reaches the client), so the block the
        // player interacted with is the only available signal.
        //
        // Read it from the recorded click, NOT from mc.hitResult here: the open
        // screen arrives a full round-trip later (~250ms on the usual test
        // server), by which time the crosshair has often moved off the chest.
        // Misreading it routes ender contents into the ex-inv path and writes
        // ender boxes into a real chest's ledger.
        val usedPos = lastUsedBlockPos?.takeIf { tickCounter - lastUsedBlockTick <= USED_BLOCK_TTL }
        openChestIsEnderChest = when {
            usedPos != null ->
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(usedPos).block).toString() == "minecraft:ender_chest"
            else -> {
                // No recorded interaction (screen opened by a command, another
                // player, etc). Fall back to the crosshair.
                val hr = mc.hitResult
                hr is BlockHitResult &&
                    BuiltInRegistries.BLOCK.getKey(level.getBlockState(hr.blockPos).block).toString() == "minecraft:ender_chest"
            }
        }

        if (openChestIsEnderChest) {
            log("handleChestScreen: ender chest")
        } else {
        if (menu != null) {
            val fromContainer = getContainerBlockPos(menu, level)
            if (fromContainer != null) {
                // Canonicalize here too. Double chests reach the client as a
                // CompoundContainer and always fall through to the branch below,
                // so today this only ever sees single chests - but the invariant
                // "openChestPos is canonical" should hold on both paths, not one.
                openChestPos = canonicalChestPos(fromContainer.first, level)
                log("handleChestScreen: container at ${blockLocation(openChestPos!!, level)} (from BE)")
            }
        }

        // Fallback: the block the player actually clicked, else the crosshair.
        if (openChestPos == null) {
            val pos = usedPos ?: (mc.hitResult as? BlockHitResult)?.blockPos
            if (pos != null && level.getBlockEntity(pos) is BaseContainerBlockEntity) {
                openChestPos = canonicalChestPos(pos, level)
                log("handleChestScreen: container at ${blockLocation(openChestPos!!, level)} (from ${if (usedPos != null) "use" else "hitResult"})")
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
        if (isWorkbenchMenu(menu)) return
        val level = Minecraft.getInstance().level
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

        // Identities currently stamped on a player-inv stack are off-limits for
        // hash/slot matching a chest slot too - without this, a chest box with
        // identical contents to an inv box gets resolved onto the SAME tracked
        // entry, so the inv box's marker and the chest box's marker visually
        // fight (whichever slot renders first wins the per-frame dedupe, making
        // the other one look like it "lost" its icon).
        val invStampedUUIDs = if (player != null) {
            menu.slots.filter { it.container == player.inventory }
                .mapNotNull { getItemUUID(it.item) }.toSet()
        } else emptySet()
        // Records the mod has in the player's inventory are off-limits too, stamp
        // or not. Opening the chest resent the inventory without stamps, so the
        // stamp check above missed a marked box in the hotbar, and an identical
        // unmarked twin in the chest was seeded with the marked box's identity -
        // after which the scan and the duplicate eviction took turns stripping
        // each other's stamp every scan (reported 2026-09-24). Nothing has been
        // clicked in this menu yet, so "inv" really means "in the inventory".
        val inInventory = trackedShulkers.values.filter { it.state == "inv" }.map { it.uuid }
        val claimed = (slotLedger.values + invStampedUUIDs + inInventory).toMutableSet()
        // Slots the persisted ledger names go first. Per-slot order let an
        // earlier slot's content-hash match claim a record the ledger had
        // pinned to a LATER slot - with identical twins, the unmarked one took
        // the marked identity on every reopen.
        val pinned = persistedChestLedger[chestLoc]?.keys ?: emptySet()
        for (slot in menu.slots.sortedBy { if (ledgerPosKey(it.index) in pinned) 0 else 1 }) {
            if (player != null && slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isTrackableShulker(stack)) continue
            val pos = ledgerPosKey(slot.index)
            var existingUUID = getItemUUID(stack)
            if (existingUUID != null && existingUUID in trackedShulkers) {
                // If this uuid is ALSO stamped on a player-inventory item, the same
                // identity is present in two different locations simultaneously (old
                // shared-uuid bad data). Chest item loses - strip it and block the
                // uuid from being re-assigned to any chest slot this session so that
                // the inv item remains the sole owner. Don't just leave this slot
                // bare for the rest of the open though (that's what made marked
                // boxes show no icon until the NEXT reopen) - fall through to the
                // unstamped-resolution path below so it gets re-identified to its
                // own real entry immediately, by hash/slot, in this same pass.
                val alsoInInv = player != null && menu.slots.any { s ->
                    s.container == player.inventory && getItemUUID(s.item) == existingUUID
                }
                if (alsoInInv) {
                    stripItemUUID(stack)
                    slotLedger.remove(pos)
                    persistedChestLedger[chestLoc]?.let { if (it[pos] == existingUUID) it.remove(pos) }
                    claimed.add(existingUUID)
                    log("evict: uuid ${existingUUID.take(8)} also in player inv, stripped from chest slot ${slot.index}")
                    existingUUID = null
                } else {
                    // Already stamped and no conflict — add to ledger so evictOrphanUUIDStamps
                    // knows the owner slot and doesn't strip it as an unclaimed duplicate.
                    if (pos !in slotLedger && existingUUID !in claimed) {
                        slotLedger[pos] = existingUUID
                        claimed.add(existingUUID)
                    }
                    continue
                }
            }
            if (pos in slotLedger) continue
            val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val hash = fingerprintFromItem(stack) ?: continue
            val hasCustomName = stack.has(DataComponents.CUSTOM_NAME)
            val name = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            val persistedHint = persistedChestLedger[chestLoc]?.get(pos)?.takeIf {
                it in trackedShulkers && it !in claimed &&
                    ShulkerIdentityResolver.ledgerEntryMatchesStack(trackedShulkers[it]!!, name, type, hasCustomName)
            }
            val uuid = ShulkerIdentityResolver.resolveChestSlot(
                trackedShulkers,
                stampUUID = null,   // stamped slots returned above
                ledgerHint = null,  // slots already in slotLedger were skipped above
                persistedHint = persistedHint,
                chestState = chestState,
                slotIndex = slot.index,
                stackHash = hash,
                stackName = name,
                stackType = type,
                hasCustomName = hasCustomName,
                claimed = claimed,
                genericEmptyHash = genericEmptyHash(type),
                chestCoords = coordStr,
                stillWhereRecorded = ::stillWhereRecorded,
            ) ?: continue
            slotLedger[pos] = uuid
            claimed.add(uuid)
            injectItemUUID(stack, uuid)
            trackedShulkers[uuid]?.let { it.contentHash = hash }
            log("ledger: chest seed $pos -> ${uuid.take(8)} (hash)")
        }
        evictOrphanUUIDStamps(menu, player)
    }

    private fun handleChestClosed(screen: Any) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return

        // Closed without hovering the highlighted slot (hover already would
        // have cleared locateTarget) - give it a short decay instead of the
        // full 20min safety net, so it's gone soon if the player just glanced
        // in, but reopening right away still shows it.
        openChestPos?.let { p ->
            locateTarget?.let { if (it.pos == p && it.dim == level.dimension().identifier().toString()) it.expireAtTick = tickCounter + LOCATE_FOUND_DECAY_TICKS }
        }

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

        // Same guard as seedChestSlotUUIDs: an identity currently stamped on a
        // player-inv stack can't be claimed by a hash/slot match on the chest
        // side too, or the two slots' markers fight over one shared entry.
        val invStampedUUIDs = menu.slots.filter { it.container == player.inventory }
            .mapNotNull { getItemUUID(it.item) }.toSet()
        val shulkersInChest = invStampedUUIDs.toMutableSet()
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
                if (ShulkerIdentityResolver.ledgerEntryMatchesStack(entry, stackName, stackType, hasCustomName)) return u
                log("chest resolve: evicting stale ledger $posKey -> ${u.take(8)} (stack mismatch)")
                if (slotLedger[posKey] == u) slotLedger.remove(posKey)
                persistedChestLedger[chestLoc]?.let { if (it[posKey] == u) it.remove(posKey) }
                return null
            }
            val ledgerUUID = if (menu.containerId == ledgerMenuId) validateLedger(slotLedger[posKey]) else null
            val persistedUUID = validateLedger(persistedChestLedger[chestLoc]?.get(posKey))
            if (stackUUID != null && stackUUID in shulkersInChest) {
                // This chest stack's literal stamp duplicates an identity
                // that's currently held elsewhere (inv, or another chest
                // slot already claimed this pass) - splitting it here, not
                // just skipping it, so it doesn't fall to "pending" and get
                // ensureItemUUID'd right back onto the same duplicate stamp.
                stripItemUUID(stack)
                log("chest resolve: stamp ${stackUUID.take(8)} also held elsewhere, split at $posKey")
            }
            val matchedUUID = ShulkerIdentityResolver.resolveChestSlot(
                trackedShulkers,
                stampUUID = stackUUID,
                ledgerHint = ledgerUUID,
                persistedHint = persistedUUID,
                chestState = chestState,
                slotIndex = slot.index,
                stackHash = stackHash,
                stackName = stackName,
                stackType = stackType,
                hasCustomName = hasCustomName,
                claimed = shulkersInChest,
                genericEmptyHash = genericEmptyHash(stackType),
                chestCoords = coordStr,
                stillWhereRecorded = ::stillWhereRecorded,
            )
            if (matchedUUID != null) {
                shulkersInChest.add(matchedUUID)
                resolved.add(item to matchedUUID)
            } else {
                pending.add(item)
            }
        }

        for (item in pending) {
            // Assign UUID to untracked shulker found in chest
            val uuid = ensureItemUUID(item.stack)
            // Create a tracking entry if not already tracked (discovered in chest)
            if (uuid !in trackedShulkers) {
                trackedShulkers[uuid] = ShulkerState(
                    uuid = uuid,
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
                log("close: (untracked) > $chestLoc ${item.stackName} (${uuid.take(8)}) discovered")
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
                    entry.lastLocation = lastLocationOf(entry.dim, oldCoords)
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
                    if (entry.happy) trace("$notifyTag ← §a${oldState}§f §7(${entry.name})§f")
                    log("close: $oldState > $chestLoc ${entry.name} (${uuid.take(8)}) from=${entry.from} oldFrom=$oldFrom")
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
            entry.lastLocation = lastLocationOf(entry.dim, oldCoords)
            entry.state = "ex-inv"
            entry.lastKnown = true
            entry.entity_id = ""
            entry.dim = dimStr
            entry.coords = coordStr
            entry.last_update_time = System.currentTimeMillis().toString()
            entry.from = "chest:vanished_on_insert"
            entry.cachedContents = chooseRicherCache(serializeShulkerContents(snapshot.stack), entry.cachedContents)
            fingerprintFromItem(snapshot.stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
            log("close: ${snapshot.locKey} > $chestLoc (last-known) ${entry.name} (${uuid.take(8)}) source slot changed, absent from final chest scan")
            if (entry.happy) trace("§ainv§f → §cchest LK§f §7(${entry.name})§f")
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
                        entry.lastLocation = lastLocationOf(entry.dim, entry.coords)
                        entry.state = "inv"
                        entry.lastKnown = false
                        entry.entity_id = ""
                        entry.coords = locKey
                        entry.dim = dimStr
                        entry.last_update_time = System.currentTimeMillis().toString()
                        entry.from = "chest:take_to_inv"
                        entry.cachedContents = chooseRicherCache(serializeShulkerContents(stack), entry.cachedContents)
                        fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() }?.let { entry.contentHash = it }
                        log("close: $chestLoc > $locKey ${entry.name} (${entry.uuid.take(8)}) lastKnown=$wasLK")
                        if (entry.happy) {
                            if (wasLK) trace("§c[LK]§f → §ainv§f §7(${entry.name})§f") else trace("§echest§f → §ainv§f §7(${entry.name})§f")
                        }
                        corrected = true
                    } else if (!entry.lastKnown && tickCounter - openChestScreenTick >= MIN_CHEST_SCAN_TICKS &&
                        persistedChestLedger[chestLoc]?.containsValue(entry.uuid) == true) {
                        // Only declare "missing" when we've previously pinned this uuid
                        // to a specific slot via the persisted ledger - otherwise we may
                        // just not have been able to identify it (ambiguous unnamed box),
                        // so leave state unchanged rather than marking it last-known.
                        log("close: $chestLoc > (gone) ${entry.name} (${entry.uuid.take(8)}) no longer in chest, marking last-known")
                        entry.lastKnown = true
                        entry.lastLocation = lastLocationOf(entry.dim, entry.coords)
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

    // Locates the live inventory stack backing a tracked entry, so callers can
    // re-stamp or read it. Slot ledger first, then content heuristics.
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
                    return inventorySlotToKey(playerSlotIndexOf(player, slot)) to slot.item
                }
            }
        }

        val snapshot = openChestInvSnapshot ?: emptyMap()
        val candidates = mutableListOf<Pair<String, ItemStack>>()

        for (slot in menu.slots) {
            if (slot.container != player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            candidates.add(inventorySlotToKey(playerSlotIndexOf(player, slot)) to stack)
        }

        if (!menu.carried.isEmpty && isShulkerItem(menu.carried)) {
            val carried = menu.carried
            candidates.add("cursor" to carried)
        }

        liveOffhand(menu, player)?.let { candidates.add(OFFHAND_POS to it) }

        candidates.firstOrNull { getItemUUID(it.second) == entry.uuid }?.let { return it }

        val newOrChanged = candidates.filter { (key, stack) ->
            val uuid = getItemUUID(stack)
            // A stack that already answers to another record is that record's
            // box, and so is a slot another inventory record holds. Without this
            // a twin the click ledger had followed out of a dropper was handed to
            // the OTHER twin's record here at close, and the next scan overrode
            // it back (found by WtfFuzz, seed 12).
            if (uuid != null && uuid != entry.uuid && uuid in trackedShulkers) return@filter false
            if (trackedShulkers.values.any { it.uuid != entry.uuid && it.state == "inv" && it.coords == key }) return@filter false
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
            val invIndex = playerSlotIndexOf(player, slot)
            snapshot[uuid] = InventorySnapshotEntry(
                slotIndex = invIndex,
                locKey = inventorySlotToKey(invIndex),
                stack = stack.copy()
            )
        }
        liveOffhand(menu, player)?.let { off ->
            getItemUUID(off)?.let { snapshot[it] = offhandSnapshotEntry(off) }
        }
        return snapshot
    }

    private fun offhandSnapshotEntry(stack: ItemStack) = InventorySnapshotEntry(
        slotIndex = OFFHAND_SLOT,
        locKey = inventorySlotToKey(OFFHAND_SLOT),
        stack = stack.copy()
    )

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
            val invIndex = playerSlotIndexOf(player, slot)
            snapshot[invIndex] = InventorySnapshotEntry(
                slotIndex = invIndex,
                locKey = inventorySlotToKey(invIndex),
                stack = stack.copy()
            )
        }
        liveOffhand(menu, player)?.let { snapshot[OFFHAND_SLOT] = offhandSnapshotEntry(it) }
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
        // 0 means "field absent" - saves written before versioning existed.
        val version: Int = 0,
        val markerIcon: String? = null,
        val markerColorIdx: Int? = null,
        val chestSlotLedger: Map<String, Map<String, String>>? = null
    )

    data class UiSettings(
        val previewBlur: Boolean = true,
        val showMatchPercent: Boolean = true,
        val itemGlow: Boolean = true
    )

    // Active "Locate" target set by the grid screen's Locate button. pos is
    // non-null only for state=="block" entries (compass + world overlay);
    // for inventory/chest items only the slot-blink applies.
    data class LocateTarget(
        val uuid: String,
        val dim: String,
        val pos: BlockPos?,
        val startTick: Int,
        var expireAtTick: Int = startTick + 24000
    )

    // ~2s - short grace period after the box is found (hovered/opened) before
    // the compass/overlay/marker clears, was 200 (10s) and felt sticky.
    private const val LOCATE_FOUND_DECAY_TICKS = 40

    private var locateTarget: LocateTarget? = null

    fun getLocateTarget(): LocateTarget? = locateTarget

    fun isXaeroPresent(): Boolean = XaeroCompat.isPresent()

    // "block" = the box itself is the world block; "ex-inv" = it's an item
    // sitting inside another container, but that container's own coords are
    // still the box's last-seen location - shared by locateEntry (HUD/overlay)
    // and addXaeroWaypoint (waypoint), both need the same world position.
    private fun resolveWorldPos(state: ShulkerState): BlockPos? {
        if (state.state != "block" && state.state != "ex-inv") return null
        val parts = state.coords.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size == 3) BlockPos(parts[0], parts[1], parts[2]) else null
    }

    fun addXaeroWaypoint(entry: ShulkerEntry) {
        val state = trackedShulkers[entry.id] ?: return
        val pos = resolveWorldPos(state)
        val player = Minecraft.getInstance().player
        if (pos == null) {
            player?.sendSystemMessage(Component.literal("Couldn't resolve a position to waypoint."))
            return
        }
        val shulkerId = BuiltInRegistries.ITEM.getKey(entry.stack.item).toString()
        val waypointName = "SB: ${entry.name.string}"
        val colorName = XaeroCompat.waypointColorNameFor(shulkerId)
        if (XaeroCompat.addNamedTemporaryWaypoint(pos.x, pos.y, pos.z, waypointName, "SB", colorName, state.dim)) {
            // ChatFormatting shares the same 16 color names Xaero's WaypointColor
            // uses, so the chat confirmation can match the waypoint's actual color.
            val chatColor = try {
                net.minecraft.ChatFormatting.valueOf(colorName)
            } catch (_: Exception) {
                net.minecraft.ChatFormatting.WHITE
            }
            val nameComponent = Component.literal(entry.name.string).withStyle(chatColor)
            player?.sendSystemMessage(Component.literal("Added temporary waypoint to map: ").append(nameComponent))
        } else {
            player?.sendSystemMessage(Component.literal("Failed to add waypoint."))
        }
    }

    private fun dimDisplayName(dim: String): String = when (dim) {
        "minecraft:overworld" -> "the Overworld"
        "minecraft:the_nether" -> "the Nether"
        "minecraft:the_end" -> "the End"
        else -> dim
    }

    fun locateEntry(entry: ShulkerEntry) {
        if (locateTarget?.uuid == entry.id) {
            locateTarget = null
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("Stopped locating."))
            return
        }
        val state = trackedShulkers[entry.id] ?: return
        when (state.state) {
            "block", "ex-inv" -> {
                val pos = resolveWorldPos(state)
                locateTarget = LocateTarget(entry.id, state.dim, pos, tickCounter)
                val player = Minecraft.getInstance().player
                val currentDim = player?.level()?.dimension()?.identifier()?.toString()
                if (pos != null && currentDim != null && currentDim != state.dim) {
                    player.sendSystemMessage(Component.literal(
                        "${entry.name.string} is in ${dimDisplayName(state.dim)} - compass will point once you're there."
                    ))
                } else if (pos != null) {
                    player?.sendSystemMessage(Component.literal("Pointing with the UI compass."))
                } else {
                    player?.sendSystemMessage(Component.literal("Couldn't resolve a position to locate."))
                }
            }
            else -> {
                locateTarget = LocateTarget(entry.id, state.dim, null, tickCounter)
                Minecraft.getInstance().player?.sendSystemMessage(
                    Component.literal("Highlighting in inventory/container.")
                )
            }
        }
    }

    fun clearLocateTarget() {
        locateTarget = null
    }

    // Resolves the on-screen slot rect for the active locate target's uuid in
    // the currently open screen (container or player inventory), or null if
    // not open / not found. Returns [x0, y0, x1, y1].
    fun resolveLocateSlotBounds(screen: Screen): IntArray? {
        val target = locateTarget ?: return null
        val accessor = screen as? AbstractContainerScreenAccessor ?: return null
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu ?: return null
        // Stamp-only matching works in the player's own inventory (stamps
        // survive there) but chests/barrels/etc. routinely have their wtf:uuid
        // stamp wiped by server resync - the same gap the marker cache covers by
        // falling back to the ledger, needed here too for other container types.
        val slot = menu.slots.find { getItemUUID(it.item) == target.uuid }
            ?: menu.slots.find { slotLedger[ledgerPosKey(it.index)] == target.uuid }
            ?: return null
        val x = accessor.leftPos + slot.x
        val y = accessor.topPos + slot.y
        return intArrayOf(x, y, x + 16, y + 16)
    }

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

    fun clearAllRecords() {
        trackedShulkers.clear()
        slotLedger.clear()
        persistedChestLedger.clear()
        transitOrder.clear()
        // Wiping the map alone leaves every box's literal wtf:uuid stamp in
        // place. The next scan/open then "rediscovers" those leftover stamps
        // as if they were still valid - and since old data can carry the same
        // stamp on more than one distinct physical box, rediscovery silently
        // cross-links unrelated boxes back together. A clear should mean
        // zero memory AND zero leftover physical stamps, so strip every
        // shulker box currently reachable (inventory + any open container).
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player != null) {
            for (i in 0 until player.inventoryMenu.slots.size) {
                stripItemUUID(player.inventoryMenu.getSlot(i).item)
            }
            val menu = player.containerMenu
            if (menu !== player.inventoryMenu) {
                for (slot in menu.slots) stripItemUUID(slot.item)
            }
        }
        save()
        log("clearAllRecords: all records wiped, stamps stripped")
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
        LocateCompassHud.register()
        LocateWorldOverlay.register()
        HotbarMarkerHud.register()
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
                client.setScreenAndShow(ShulkerGridScreen(entries))
            }

            while (toggleHappyKeyBinding?.consumeClick() == true) {
                toggleHappyForHoveredSlot()
            }

            val player = client.player ?: return@register
            val level = client.level ?: return@register
            tickCounter++

            // Normal clear is "player hovered the highlighted slot." expireAtTick
            // defaults to a 20min safety net (long walk to a far-off box without
            // the indicator dying mid-trip), but gets tightened to a short decay
            // once the matching container/box has been opened-and-closed without
            // a hover, so it doesn't linger forever if the player just glances
            // in and walks away.
            locateTarget?.let { if (tickCounter >= it.expireAtTick) locateTarget = null }

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

                val center = net.minecraft.world.phys.Vec3.atCenterOf(pending.pos)
                val items = level.getEntitiesOfClass(ItemEntity::class.java, AABB.ofSize(center, 4.0, 4.0, 4.0))
                val found = items
                    .asSequence()
                    .filter { isShulkerItem(it.item) }
                    .filter { it.id !in claimedEntityIds }
                    .minByOrNull { it.distanceToSqr(center.x, center.y, center.z) }
                if (found != null) {
                    val shulker = trackedShulkers[pending.uuid]
                    if (shulker != null) {
                        // Captured before the write, and printed in the canonical
                        // format. This changed state and announced it only as
                        // "block -> item: found entity ...", which CHECKLIST.md's
                        // grep does not match - so breaking a placed box looked
                        // like nothing had happened, and scenario 2 could not pass.
                        val oldState = shulker.state
                        shulker.state = "item"
                        shulker.entity_id = found.id.toString()
                        shulker.dim = level.dimension().identifier().toString()
                        shulker.coords = "${found.x},${found.y},${found.z}"
                        shulker.last_update_time = System.currentTimeMillis().toString()
                        shulker.from = "tick:found_entity"
                        log("transition: ${shulker.name} (${pending.uuid.take(8)}) $oldState → item from=tick:found_entity entity=${found.id}")
                        if (shulker.happy) trace("§eblock§f → §7item§f §7(${shulker.name})§f")
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
                // One entity, one answer. The same entity could sit in the queue
                // twice, and its second pass - with the first match excluded -
                // found the one other record with those contents: an identical
                // twin still in the player's hand, flipped to "item" until the next
                // scan (found by WtfFuzz, 2026-09-24).
                val owned = trackedShulkers.values.filter { it.state == "item" }.map { it.entity_id }.toSet()
                readyEntities.retainAll { (e, _) -> e.id.toString() !in owned }
                val seenIds = mutableSetOf<Int>()
                readyEntities.retainAll { (e, _) -> seenIds.add(e.id) }
                if (readyEntities.isNotEmpty()) {
                    val claimedUUIDs = mutableSetOf<String>()
                    var anyMatched = false

                    // A "block" entry can also match if the drop spawned at its
                    // block position (broken by another player, explosion, ...).
                    fun matchableState(e: ShulkerState, entity: ItemEntity): Boolean {
                        // A record whose box is still sitting in its inventory slot
                        // did not just land on the ground.
                        if (e.state == "inv" && stillAtRememberedSlot(player, e)) return false
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
                    // Entity gone - or only renumbered. Ids do not survive a relog or
                    // a chunk reload, and an id that no longer resolves said "picked
                    // up" to the check below while the box lay exactly where it was.
                    // A matching item at the recorded spot that no other record owns
                    // is this one.
                    val coords = parseVec3(shulker.coords)
                    val rebound = coords?.let { (x, y, z) ->
                        level.getEntitiesOfClass(ItemEntity::class.java, net.minecraft.world.phys.AABB(x - 2.0, y - 2.0, z - 2.0, x + 2.0, y + 2.0, z + 2.0))
                            .firstOrNull { e ->
                                isShulkerItem(e.item) && fingerprintFromItem(e.item) == shulker.contentHash &&
                                    trackedShulkers.values.none { o -> o !== shulker && o.state == "item" && o.entity_id == e.id.toString() }
                            }
                    }
                    if (rebound != null) {
                        log("item: ${shulker.name} (${shulker.uuid.take(8)}) rebound from entity ${shulker.entity_id} to ${rebound.id}")
                        shulker.entity_id = rebound.id.toString()
                        itemVanishTicks.remove(shulker.uuid)
                        itemVanishInvCount.remove(shulker.uuid)
                        continue
                    }
                    // Straight after a join the entities have not all arrived yet.
                    if (postJoinGraceTicks > 0) continue
                    // If the player picked it up, the inventory scan
                    // will flip the entry to inv shortly - give it a grace window.
                    val pos = coords?.let { BlockPos(it.first.toInt(), it.second.toInt(), it.third.toInt()) }
                    if (pos == null || !level.isLoaded(pos)) {
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
                    // Evidence of a pickup is this record's own stamp in the inventory,
                    // or one more box there than before. A look-alike that was already
                    // there proves nothing: after a relog the unmarked twin in the
                    // hotbar "confirmed" the pickup of the marked twin still on the
                    // ground, and took its mark (found by WtfFuzz, seed 14).
                    val stamped = player.inventoryMenu.slots.any { getItemUUID(it.item) == shulker.uuid }
                    if (stamped || countInvShulkers(player) > baseline) {
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
                    shulker.lastLocation = lastLocationOf(shulker.dim, shulker.coords)
                    shulker.state = "ex-inv"
                    shulker.lastKnown = true
                    shulker.entity_id = ""
                    shulker.last_update_time = System.currentTimeMillis().toString()
                    shulker.from = "tick:item_vanished"
                    log("transition: ${shulker.name} (${shulker.uuid.take(8)}) item → ex-inv from=tick:item_vanished lastKnown=true coords=${shulker.coords}")
                    if (shulker.happy) trace("§7item§f → §c[LK]§f §7(${shulker.name})§f")
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
                            // isLoaded, not the deprecated hasChunkAt: hasChunkAt can read
                            // true for a client-side placeholder/empty chunk outside render
                            // distance, whose block data defaults to air - that falsely
                            // looked like "block gone" and marked it last-known just from
                            // walking away, not from genuinely verifying it was missing.
                            if (level.isLoaded(pos)) {
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
                                        shulker.lastLocation = lastLocationOf(shulker.dim, shulker.coords)
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
                                    shulker.lastLocation = lastLocationOf(shulker.dim, shulker.coords)
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

            // Hotbar marker HUD (gameplay screen, no open menu) only trusts
            // the live wtf:uuid stamp - no slotLedger to fall back on like
            // the in-screen marker has. A pickup into a hotbar slot that
            // ISN'T the currently-selected one never re-stamps until some
            // other trigger (opening a screen) runs performInventoryScan, so
            // the icon silently goes dark until then. Cheap periodic heal
            // catches that without needing a screen open.
            hotbarHealTicks++
            if (hotbarHealTicks >= 40) {
                hotbarHealTicks = 0
                if (!scanQueued) {
                    scanQueued = true
                    throttleTicks = 0
                }
            }

            if (dupStampPending) {
                dupStampPending = false
                dedupeOpenContainerStamps(player)
            }

            refreshMarkerCache(player)

            if (logDirty) {
                logDirty = false
                logWriter?.flush()
            }

            resolveHandSwap(player)

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
                    Minecraft.getInstance().setScreenAndShow(ShulkerGridScreen(entries))
                    1
                }
                .then(ClientCommands.literal("debug")
                    .executes {
                        debugMode = !debugMode
                        debugVerbose = false
                        val status = if (debugMode) "§aon§f" else "§coff§f"
                        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
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
                            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                                Component.literal("§7[§fWTF§f] Debug $status")
                            )
                            1
                        }
                    )
                )
            )
        }

        // Record the block at interaction time, not at screen-open time.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register { _, world, _, hit ->
            if (world.isClientSide) {
                lastUsedBlockPos = hit.blockPos
                lastUsedBlockTick = tickCounter
            }
            net.minecraft.world.InteractionResult.PASS
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            load()
            // Without this, repairSlotUUIDs/onMenuClickPost stay dead until the
            // player opens some screen this session (ledgerMenuId starts at a
            // sentinel that never matches the player's own default inventory
            // menu), so a pickup before that first open never gets the fast
            // repair path.
            client.player?.let { ledgerMenuId = it.inventoryMenu.containerId }
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
                    val lvl = entity.level()
                    if (expect != null) {
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
            // Everything world-scoped goes through resetWorldState(). Leaving any
            // of it populated leaks one server's identities into the next - e.g. a
            // stale locateTarget whose dimension id happens to match makes the
            // compass point at a position that means nothing on the new world.
            resetWorldState()
            currentWorldId = null
            saveBlocked = false
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
            log("ScreenEvents.AFTER_INIT: ${screen::class.simpleName} (isContainerAccessor=${screen is AbstractContainerScreenAccessor}, isShulkerBox=${screen is ShulkerBoxScreen})")
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            // Only screens that can show markers are worth a scan. This used to
            // fire on the title, pause and options screens too.
            if (screen is AbstractContainerScreenAccessor || screen is ShulkerGridScreen) {
                performInventoryScan(level, player)
            }
            // AFTER_INIT fires again for the SAME screen instance whenever the
            // window is resized (resize -> init), and the two halves below want
            // OPPOSITE things from that - they used to be the wrong way round.
            //
            // The close handler must be registered EVERY time. Fabric's
            // ScreenMixin.beforeInit calls ScreenEventFactory.createRemoveEvent()
            // on each init, throwing away every callback registered before it, so
            // a guard that registers only once means the first resize silently
            // deletes the handler: handleChestClosed never runs again and the
            // chest ledger is never committed. Registering each time still yields
            // exactly one live callback, because the previous one no longer
            // exists - which is also the honest fix for the double-fire the guard
            // was written for. Measured 2026-09-16: 566 inits, 16 removes, none
            // after the first resize.
            //
            // handleChestScreen must run ONCE. It decides which container this
            // is, and on a re-init the recorded interaction has aged past
            // USED_BLOCK_TTL, so it falls back to mc.hitResult - re-identifying
            // the open chest as whatever the crosshair happens to be on now. Of
            // 566 calls in that session, 549 resolved that way.
            val firstInit = closeHandlerScreen !== screen
            if (screen is ShulkerBoxScreen) {
                if (firstInit) handleShulkerScreen(mc, level, player, screen)
                closeHandlerScreen = screen
                ScreenEvents.remove(screen).register {
                    closeHandlerScreen = null
                    handleShulkerScreenClosed(screen)
                }
            }
            if (screen is AbstractContainerScreenAccessor && screen !is ShulkerBoxScreen &&
                isWorldContainer(screen)
            ) {
                if (firstInit) handleChestScreen(mc, screen)
                closeHandlerScreen = screen
                ScreenEvents.remove(screen).register {
                    closeHandlerScreen = null
                    log("ScreenEvents.remove fired for ${screen::class.simpleName}")
                    handleChestClosed(screen)
                    val l = Minecraft.getInstance().level
                    val p = Minecraft.getInstance().player
                    if (l != null && p != null) {
                        performInventoryScan(l, p)
                    }
                }
            }
        }
    }

    // Called from AbstractContainerScreenTooltipMixin, injected right before
    // extractTooltip (vanilla's renderTooltip) - NOT via ScreenEvents.afterExtract,
    // which fires after the screen's ENTIRE render including the tooltip itself
    // (afterExtract = after extractRenderState, the renamed Screen.render). That
    // made the marker icon draw on top of tooltips/other mods' overlays every
    // time. This mixin point is the one place that's after items but before
    // tooltip.
    @JvmStatic
    fun onBeforeTooltip(screen: Any, graphics: GuiGraphicsExtractor) {
        if (screen !is Screen || screen !is AbstractContainerScreenAccessor) return
        renderHappyMarkers(screen, graphics)
        renderLocateSlotBlink(screen, graphics)
    }

    // Cycles red -> yellow -> red over the slot holding the locate target's
    // stack, full alpha swing (not just a faint shimmer) - a plain white
    // shimmer was too easy to miss against light item textures/backgrounds.
    // Clears the locate target once the player hovers the matching slot.
    private fun renderLocateSlotBlink(screen: Screen, graphics: GuiGraphicsExtractor) {
        if (locateTarget == null) return
        val bounds = resolveLocateSlotBounds(screen) ?: return
        val accessor = screen as AbstractContainerScreenAccessor
        val hovered = accessor.hoveredSlot
        if (hovered != null) {
            val hx = accessor.leftPos + hovered.x
            val hy = accessor.topPos + hovered.y
            if (hx == bounds[0] && hy == bounds[1]) {
                locateTarget = null
                return
            }
        }
        val pulse = (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0))
        val alpha = (0x40 + (0xB0 * pulse)).toInt().coerceIn(0x40, 0xF0)
        // Lerp red (255,40,40) -> yellow (255,220,40) with the same pulse so
        // the slot visibly changes hue, not just brightness.
        val g = (40 + (180 * pulse)).toInt().coerceIn(40, 220)
        val color = (alpha shl 24) or (0xFF shl 16) or (g shl 8) or 0x28
        graphics.fill(bounds[0], bounds[1], bounds[2], bounds[3], color)
    }

    // Render used to ask the wtf:uuid stamp directly, at frame rate. That stamp
    // is client-only NBT: the server wipes it on every resync and only a scan
    // puts it back, and scans are throttled - the hotbar heal runs once every 40
    // ticks. That is the marker blinking out for up to two seconds at a stretch.
    // Resolve it once per tick instead, falling back to the content fingerprint
    // when the stamp is gone, and let render just read the answer. Keyed by
    // player-inventory index, so it covers the hotbar HUD, the inventory half of
    // any container screen, and the offhand.
    private val markedPlayerSlots = mutableSetOf<Int>()
    private var markedCarried = false

    // The cursor is not an inventory slot, but it goes through the same
    // resolution, so it rides along under an index no real slot can have.
    private const val CURSOR_SLOT = -1

    // Same idea for the slots of whatever container is open, keyed in that
    // menu's own index space. Without this, a box shift-clicked into a chest
    // after the server had stripped its stamp rendered no icon at all - the
    // inventory could still identify it by fingerprint, the chest could not, so
    // the marker died the moment it crossed into the container (and stayed dead
    // on reopen, since the stamp never came back).
    private val markedMenuSlots = mutableSetOf<Int>()
    private val markerMenuCache = mutableMapOf<Int, Pair<Int, String>>()
    private var markerMenuId = Int.MIN_VALUE

    private fun refreshMarkerCache(player: Player) {
        markedPlayerSlots.clear()
        // uuids a stamped stack has already accounted for, so the passes below
        // cannot hand the same marked entry to a second stack.
        val claimed = mutableSetOf<String>()
        // Stacks whose stamp the server has just wiped, by inventory index. The
        // cursor joins them as CURSOR_SLOT rather than carrying its own half-copy
        // of the resolution, which is how it ended up with neither the empty-box
        // guard nor a way to be reached when it was the only unstamped stack.
        val unstamped = mutableListOf<Pair<Int, ItemStack>>()

        val carried = player.containerMenu.carried
        val carriedEntry = if (carried.isEmpty || !isTrackableShulker(carried)) null
            else getItemUUID(carried)?.let { trackedShulkers[it] }
        markedCarried = carriedEntry?.happy == true
        when {
            carriedEntry != null -> claimed.add(carriedEntry.uuid)
            !carried.isEmpty && isTrackableShulker(carried) -> unstamped.add(CURSOR_SLOT to carried)
        }

        val invMenu = player.inventoryMenu
        for (slot in invMenu.slots) {
            if (slot.container !== player.inventory) continue
            // containerSlot, never index: Slot.index is the slot's position in
            // the MENU (AbstractContainerMenu.addSlot assigns it), so the same
            // physical inventory slot is 30 in the inventory screen, 57 with a
            // chest open and 84 with a large chest. containerSlot is its index
            // in the player's inventory, which is the same number everywhere.
            val invIndex = slot.containerSlot
            val stack = slot.item
            if (stack.isEmpty || !isTrackableShulker(stack)) continue
            val entry = getItemUUID(stack)?.let { trackedShulkers[it] }
            if (entry != null) {
                claimed.add(entry.uuid)
                if (entry.happy) markedPlayerSlots.add(invIndex)
            } else {
                unstamped.add(invIndex to stack)
            }
        }

        if (unstamped.isNotEmpty()) {
            val stackAt = unstamped.toMap()
            val awarded = allocateMarkers(
                unstamped.map { (i, _) -> i to if (i == CURSOR_SLOT) "cursor" else inventorySlotToKey(i) },
                trackedShulkers.values.filter { it.happy }.map { e ->
                    MarkerEntry(e.uuid, e.coords.takeIf { e.state == "inv" }, e.contentHash)
                },
                claimed,
            ) { index ->
                val stack = stackAt.getValue(index)
                // Every empty unnamed box of a colour shares one hash, so counting
                // over it would mark boxes that were never marked.
                val generic = genericEmptyHash(BuiltInRegistries.ITEM.getKey(stack.item).toString())
                fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() && it != generic }
            }
            for (i in awarded) {
                if (i == CURSOR_SLOT) markedCarried = true else markedPlayerSlots.add(i)
            }
        }

        val menu = player.containerMenu
        if (menu.containerId != markerMenuId) {
            markerMenuId = menu.containerId
            markerMenuCache.clear()
        }
        markedMenuSlots.clear()
        if (menu === player.inventoryMenu) return
        // Same rule as the inventory above, for the open container's own slots.
        // A slot that can say who it is - chest ledger, then stamp - answers for
        // itself, once per identity. The rest are allocated by COUNT: one marked
        // record with these contents still unaccounted for lights exactly one
        // stack. Deciding per stack lit every twin of a marked box (reported
        // 2026-09-24: mark one of two identical pink boxes, both show the icon).
        val leftovers = mutableListOf<Pair<Int, String>>()
        for (slot in menu.slots) {
            if (slot.container === player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isTrackableShulker(stack)) {
                markerMenuCache.remove(slot.index)
                continue
            }
            val uuid = slotLedger[ledgerPosKey(slot.index)]
                ?: getItemUUID(stack)?.takeIf { it in trackedShulkers }
            if (uuid != null && claimed.add(uuid)) {
                if (trackedShulkers[uuid]?.happy == true) markedMenuSlots.add(slot.index)
                continue
            }
            // The fingerprint hashes the box's contents - cached per slot against
            // the stack's own hash, since this runs every tick.
            val hash = ItemStack.hashItemAndComponents(stack)
            val fp = markerMenuCache[slot.index]?.takeIf { it.first == hash }?.second
                ?: run {
                    val generic = genericEmptyHash(BuiltInRegistries.ITEM.getKey(stack.item).toString())
                    val f = fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() && it != generic } ?: ""
                    markerMenuCache[slot.index] = hash to f
                    f
                }
            if (fp.isNotEmpty()) leftovers.add(slot.index to fp)
        }
        if (leftovers.isEmpty()) return
        // Records physically somewhere else - carried or placed - are not owed a
        // box in this container.
        val owed = trackedShulkers.values
            .filter { it.happy && it.uuid !in claimed && it.state != "inv" && it.state != "block" && it.contentHash.isNotEmpty() }
            .groupingBy { it.contentHash }
            .eachCount()
            .toMutableMap()
        for ((index, fp) in leftovers) {
            val left = owed[fp] ?: 0
            if (left <= 0) continue
            owed[fp] = left - 1
            markedMenuSlots.add(index)
        }
    }

    // Slot of the open container, resolved on the last tick.
    fun isMenuSlotMarked(slotIndex: Int): Boolean = slotIndex in markedMenuSlots

    // Player-inventory index (hotbar 0..8, main 9..35, offhand 40), resolved on
    // the last tick. Never the stamp, so a resync cannot blank it.
    fun isPlayerSlotMarked(slotIndex: Int): Boolean = slotIndex in markedPlayerSlots

    // Slot.containerSlot is the container's own index everywhere except creative,
    // which wraps each inventoryMenu slot in a SlotWrapper built as
    // SlotWrapper(inventoryMenu.slots[i], i, x, y) - so containerSlot carries the
    // MENU index: armor 5..8, hotbar 36..44, offhand 45. Main inventory is 9..35 in
    // both spaces, which is exactly why the marker rendered there in creative and
    // nowhere else. The stack object is the same instance either way, so ask the
    // inventory which slot holds it rather than trusting the number.
    private fun playerSlotIndexOf(player: Player, slot: Slot): Int {
        val stack = slot.item
        val inv = player.inventory
        val declared = slot.containerSlot
        if (stack.isEmpty) return declared
        if (declared in 0 until inv.containerSize && inv.getItem(declared) === stack) return declared
        // ponytail: linear scan of 41 slots, and only for a slot whose number already
        // disagreed. Per-slot map if some screen ever makes this the common path.
        for (i in 0 until inv.containerSize) if (inv.getItem(i) === stack) return i
        return declared
    }

    fun isCarriedMarked(): Boolean = markedCarried

    // Gameplay-screen hotbar has no open menu/slotLedger to fall back on. It
    // used to trust the live stamp alone, which is exactly the path the server
    // wipes; it reads the per-tick cache now.
    fun isHotbarSlotHappy(slotIndex: Int): Boolean = isPlayerSlotMarked(slotIndex)

    fun isMarkerIconNone(): Boolean = markerIcon == "none"

    // Repairs what render can only detect: one wtf:uuid stamped on several
    // stacks in the open container. First slot holding it keeps the stamp,
    // the rest are stripped and a rescan re-identifies them against their own
    // tracked entries. Runs at most once per tick, so the repair cost no longer
    // scales with frame rate.
    private fun dedupeOpenContainerStamps(player: Player) {
        val menu = player.containerMenu
        val seen = mutableSetOf<String>()
        var stripped = 0
        for (slot in menu.slots) {
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            val stampUUID = getItemUUID(stack) ?: continue
            if (seen.add(stampUUID)) continue
            stripItemUUID(stack)
            stripped++
            log("dedupe: slot=${slot.index} uuid=${stampUUID.take(8)} duplicate stamp stripped")
        }
        if (stripped > 0) scanQueued = true
    }

    private fun renderHappyMarkers(screen: Screen, graphics: GuiGraphicsExtractor) {
        if (markerIcon == "none") return
        val container = screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ?: return
        val accessor = screen as AbstractContainerScreenAccessor
        val font = Minecraft.getInstance().font
        // A wtf:uuid stamp can end up duplicated onto more than one physical
        // stack (e.g. same-contentHash boxes repeatedly re-resolving to the
        // same tracked entry on scan). Underlying dedup should prevent this,
        // but render is the last line of defense against showing the marker
        // on a whole bunch of slots at once for one identity - only the first
        // slot holding a given uuid this frame gets to show it.
        val shownThisFrame = mutableSetOf<String>()
        val player = Minecraft.getInstance().player
        for (slot in container.menu.slots) {
            val stack = slot.item
            // Player-inventory slots go through the per-tick cache, which still
            // knows the box after a resync has stripped its stamp. Container
            // slots keep the stamp/slotLedger path - the ledger covers them, and
            // it is keyed in the container's own index space.
            val marked = if (player != null && slot.container === player.inventory) {
                isPlayerSlotMarked(playerSlotIndexOf(player, slot))
            } else {
                isMenuSlotMarked(slot.index)
            }
            if (!marked) continue
            val stampUUID = getItemUUID(stack)
            val uuid = stampUUID ?: slotLedger[ledgerPosKey(slot.index)]
            if (uuid != null && !shownThisFrame.add(uuid)) {
                // Hiding the icon alone leaves the literal duplicate stamp in
                // place - the surplus stack and the "real" owner both still
                // point at the SAME tracked entry, so toggling either one
                // toggles both forever. The fix is to strip the surplus stamp,
                // but render runs at frame rate: doing it here made the
                // strip -> rescan -> re-stamp -> strip loop run as fast as the
                // GPU could go. Just flag it; the tick handler owns the repair.
                if (stampUUID != null) dupStampPending = true
                else if (debugVerbose) log("render: slot=${slot.index} uuid=${uuid.take(8)} SKIPPED (duplicate this frame, ledger-only)")
                continue
            }
            val x = accessor.leftPos + slot.x
            val y = accessor.topPos + slot.y
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(x + 10f, y - 1f)
            pose.scale(0.6f)
            graphics.text(font, markerIcon, 0, 0, getMarkerColor(), true)
            pose.popMatrix()
        }

        renderCarriedMarker(graphics, container, font)
    }

    // The stack on the cursor is not one of menu.slots, so nothing drew a marker
    // on it: picking a marked box up made its icon vanish until it was put down
    // again. Vanilla draws the carried item with its top-left at (mouse-8,
    // mouse-8), so the same (+10, -1) offset the slot loop uses lands the icon in
    // the same corner of the item.
    private fun renderCarriedMarker(
        graphics: GuiGraphicsExtractor,
        container: net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>,
        font: net.minecraft.client.gui.Font
    ) {
        if (!markedCarried || container.menu.carried.isEmpty) return
        val mc = Minecraft.getInstance()
        val window = mc.window
        if (window.screenWidth == 0 || window.screenHeight == 0) return
        val mouseX = mc.mouseHandler.xpos() * window.guiScaledWidth / window.screenWidth
        val mouseY = mc.mouseHandler.ypos() * window.guiScaledHeight / window.screenHeight
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(mouseX.toFloat() - 8f + 10f, mouseY.toFloat() - 8f - 1f)
        pose.scale(0.6f)
        graphics.text(font, markerIcon, 0, 0, getMarkerColor(), true)
        pose.popMatrix()
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

        // Don't clear here - that used to kill the slot-blink before it ever
        // got a chance to render inside this very screen. World overlay/HUD
        // compass are suppressed independently while any screen is open
        // (see LocateCompassHud/LocateWorldOverlay); the actual clear is
        // "hovered the slot," with a decay set on close (handleShulkerScreenClosed)
        // if the player closes without hovering.

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
                    if (entry.happy) trace("§aupdated§f shulker: §e${entry.name}§f")
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
                if (hashMatch.happy) trace("§amatch§f → §eblock§f §7(${hashMatch.name})§7")
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
                    trace("§amatch§f → §eblock§f §7(${nameMatch.name})§7 §7(contents changed)")
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
        // Opening the targeted box's own screen means it's found - short
        // decay instead of the full close+200-tick wait, so it's gone almost
        // right away but doesn't vanish mid-glance.
        locateTarget?.let { if (it.uuid == currentKey) it.expireAtTick = tickCounter + LOCATE_FOUND_DECAY_TICKS }

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
        // Closed without hovering the slot inside (hover would've cleared
        // locateTarget already) - matches by uuid, not position, since the
        // player's hitResult (used to derive a pos) may already point
        // elsewhere by the time the screen actually closes.
        locateTarget?.let { if (it.uuid == openShulkerKey) it.expireAtTick = tickCounter + LOCATE_FOUND_DECAY_TICKS }
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
        resolveHandSwap(player)
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
        // containerMenu, not inv: carried belongs to the menu that is OPEN, and
        // inventoryMenu.carried is empty the whole time a chest screen is up. The
        // scan therefore could not see a box held on the cursor with a container
        // open, and step 4's cleanup declared it had left the inventory - so
        // picking a tracked box up in a chest made /wtf say it was gone until it
        // was put back down.
        val carried = player.containerMenu.carried
        if (!carried.isEmpty && isTrackableShulker(carried)) {
            inventoryShulkers.add(Triple(carried, "cursor", fingerprintFromItem(carried) ?: ""))
        }

        // Same blindness as the cursor, one step further out: a box in the open
        // menu's OWN slots - an anvil input, a grindstone, a crafting grid - is in
        // neither inventoryMenu nor on the cursor, so step 4 declared it gone and
        // wrote "ex-inv, location unknown" while the player was looking straight at
        // it. It has not left their possession: vanilla hands an anvil input back
        // when the window closes, and the next scan re-finds it at a real slot.
        //
        // Gated on the mod NOT having identified a container. For a chest or an
        // ender chest the vanish IS the signal, and the hopper recovery path is
        // built on exactly it - openChestPos stays null here because an anvil has
        // no container block entity to resolve, which is what separates the two.
        //
        // Not added to inventoryShulkers: these slots have no inventory key to
        // record, and inventing one would write another location that is not a
        // place. Holding the entry where it was is enough - the point is only to
        // stop the demotion.
        val workbenchUUIDs = mutableSetOf<String>()
        val workbenchHashes = mutableSetOf<String>()
        val openMenu = player.containerMenu
        if (openChestPos == null && !openChestIsEnderChest && openMenu !== player.inventoryMenu) {
            for (slot in openMenu.slots) {
                if (slot.container === player.inventory) continue
                val stack = slot.item
                if (stack.isEmpty || !isTrackableShulker(stack)) continue
                getItemUUID(stack)?.let { workbenchUUIDs.add(it) }
                // An anvil hands back a server-built stack, so the stamp is exactly
                // what may have just been wiped. Content is not identity, but the
                // worst this costs is a twin briefly not being demoted either.
                fingerprintFromItem(stack)?.takeIf { it.isNotEmpty() }?.let { workbenchHashes.add(it) }
            }
        }

        val foundUUIDs = mutableSetOf<String>()
        var changed = false

        // Boxes the click ledger has placed in the open container's own slots.
        // A box clicked into a chest keeps its "inv" record until the chest
        // closes, so every content match below would still offer it for an
        // unidentified look-alike in the inventory - an untracked twin taken out
        // as the marked one went in was handed the marked identity, and the
        // duplicate eviction then stripped one or the other every scan
        // (reported 2026-09-24). The ledger knows better; nothing it holds is
        // in the player's inventory.
        //
        // Never the player's own menu: it is "open" all the time, so its ledger
        // outlives the screen - a box put in the 2x2 grid and handed back to the
        // inventory when the screen closed stayed excluded from its own record,
        // and got a second one (found by WtfFuzz, 2026-09-24).
        val inOpenMenu: Set<String> =
            if (player.containerMenu !== player.inventoryMenu && player.containerMenu.containerId == ledgerMenuId)
                slotLedger.values.toSet() else emptySet()

        // Pass 0: position memory, authoritative. A server resync can hand
        // back a stamp that's stale, wrong, or duplicated onto more than one
        // stack (the literal NBT is never something the client can fix
        // server-side - any edit gets overwritten on the next resync), so
        // physical NBT can't be trusted as the primary signal for a slot the
        // mod has already seen before. If this exact slot is remembered as a
        // specific tracked entry, that memory wins outright: force the stamp
        // back to what's remembered and skip every other heuristic for it.
        // Only slots with no memory at all fall through to uuid/hash/name
        // matching below.
        val resolvedThisScan = mutableSetOf<String>()
        for (ss in inventoryShulkers) {
            val remembered = trackedShulkers.values.firstOrNull {
                it.state == "inv" && it.coords == ss.second && it.uuid !in foundUUIDs
            } ?: continue
            // Authoritative about WHICH of several look-alikes this is - not
            // about whether a completely different box has taken the slot. A box
            // that leaves by a path no click sees (a plugin, /clear, a kit) keeps
            // its "inv" record through cleanup's one-second grace, and a new box
            // landing in that slot inside it was handed the old identity whole:
            // uuid, mark, and its contentHash rewritten to the newcomer's. Seen in
            // WtfScenarios, where the next scenario's fresh box came up marked.
            // Stamps are useless here - resync wipes them - but type, name and
            // contents are what the server sends, so they survive it.
            val stackType = BuiltInRegistries.ITEM.getKey(ss.first.item).toString()
            val stackName = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            val plausible = stackType == remembered.type && (
                remembered.contentHash.isEmpty() || remembered.contentHash == ss.third ||
                    stackName == remembered.name || (isUnnamed(stackName) && isUnnamed(remembered.name))
                )
            if (!plausible) {
                log("scan: position memory ${ss.second} -> ${remembered.uuid.take(8)} refused, a different box is there now ($stackName)")
                continue
            }
            val had = getItemUUID(ss.first)
            if (had != remembered.uuid) {
                injectItemUUID(ss.first, remembered.uuid)
                // Two very different events used to print the same line. ABSENT is
                // the server being the server - it drops client-only components on
                // every click round-trip, so this fires constantly and means
                // nothing. A DIFFERENT uuid means two identities are fighting over
                // one box, which is the only version worth reading, and it spent an
                // afternoon buried under hundreds of the routine kind.
                if (had == null) {
                    log("scan: position memory ${ss.second} -> ${remembered.uuid.take(8)} (restamped)")
                } else {
                    log("scan: position memory ${ss.second} -> ${remembered.uuid.take(8)} (OVERRODE ${had.take(8)})")
                }
            }
            remembered.contentHash = ss.third
            remembered.last_update_time = System.currentTimeMillis().toString()
            foundUUIDs.add(remembered.uuid)
            resolvedThisScan.add(ss.second)
        }

        // Evict duplicate wtf:uuid stamps in inventory (pre-1.4.9 bad data, or
        // a literal item clone that copied the tag along with everything
        // else). If the same uuid appears on >1 stack, the first keeps it;
        // the rest get a FRESH uuid right here instead of just being stripped
        // bare - stripping alone left them to be re-discovered (and possibly
        // re-collide) on every subsequent scan, which is what made this same
        // eviction block fire identically on every single chest close.
        val invUUIDSeen = mutableSetOf<String>()
        for (ss in inventoryShulkers) {
            if (ss.second in resolvedThisScan) continue
            val uuid = getItemUUID(ss.first) ?: continue
            if (!invUUIDSeen.add(uuid)) {
                val fresh = java.util.UUID.randomUUID().toString()
                injectItemUUID(ss.first, fresh)
                log("evict: duplicate inv uuid ${uuid.take(8)} at ${ss.second} split into fresh ${fresh.take(8)}")
            }
        }

        // 2. Pass 1: Match by existing UUID (Highest confidence)
        for (ss in inventoryShulkers) {
            if (ss.second in resolvedThisScan) continue
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
                    if (wasLK && entry.happy) trace("§c[LK]§f → §ainv§f §7(${entry.name})§f")
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
            if (ss.second in resolvedThisScan) continue
            val currentUUID = getItemUUID(ss.first)
            if (currentUUID != null && foundUUIDs.contains(currentUUID)) continue

            val hash = ss.third

            val stackType = BuiltInRegistries.ITEM.getKey(ss.first.item).toString()
            val stackName = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "???"
            val transitMatchUuid = transitOrder
                .mapIndexedNotNull { index, candidateUuid ->
                    if (candidateUuid in foundUUIDs || candidateUuid in inOpenMenu) return@mapIndexedNotNull null
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
            // "ex-inv" is missing from that state list on purpose: an entry that
            // has been declared gone is not hash-matched back here, because
            // content is not identity and several identical boxes would hand each
            // other's histories around. The way back in is the recovery pass
            // below, which since ticket 010's reversal claims the oldest
            // unaccounted record rather than refusing an ambiguous one - it has
            // the name and the cached contents to work with, which this pass does
            // not. Whether this exclusion should be relaxed too is still open.
            val match = if (hash == genericEmptyHash(stackType)) null else {
                val hashCandidates = trackedShulkers.values.filter {
                    it.uuid !in foundUUIDs && it.uuid !in inOpenMenu && it.contentHash == hash && !stillWhereRecorded(it) && (it.state == "inv" || it.state == "item" || it.state == "enderchest" || it.state == "block")
                }
                // Prefer candidate last seen at this exact slot so same-hash boxes don't
                // swap identity across scans. trackedShulkers is a HashMap - iteration
                // order is NOT stable across reloads/inserts, so the final fallback must
                // be deterministic (firstSeen) rather than a bare firstOrNull(), which
                // could pick a DIFFERENT same-hash candidate on a later scan and
                // re-duplicate the uuid stamp onto more than one physical stack.
                hashCandidates.firstOrNull { it.coords == ss.second }
                    // A record the click ledger took out of the player's slots into
                    // a menu slot is "inv" with no slot - in transit. A box coming
                    // back (the 2x2 grid hands its items back on close) is that one,
                    // not an older record that happens to share its contents; the
                    // oldest-first rule gave a returning marked twin's identity to a
                    // stale twin record and stranded the mark (WtfFuzz, seed 17).
                    ?: hashCandidates.filter { it.state == "inv" && it.coords.isEmpty() }
                        .maxByOrNull { it.last_update_time.toLongOrNull() ?: 0L }
                    ?: hashCandidates.minByOrNull { if (it.firstSeen > 0) it.firstSeen else Long.MAX_VALUE }
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
                    it.uuid !in foundUUIDs && it.uuid !in inOpenMenu && !stillWhereRecorded(it) &&
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

                // Ex-inv / ender recovery: re-link entries that reappeared in inventory.
                // With several hash-identical boxes (the common Kitt case), an
                // arbitrary firstOrNull() falsely "recovers" a box that's still
                // sitting right where it was (e.g. still in a chest) just because
                // some unrelated inv stack happens to share its contents - and
                // that gets caught/corrected later, but it's a real false
                // "picked up" claim in the meantime. Without an exact slot match,
                // only recover when there's exactly ONE candidate - ambiguous
                // ones fall through to new discovery instead of a guess.
                val recoveryMatch = if (hash == genericEmptyHash(stackType)) null else {
                    // contentHash covers type + NAME + contents, so a record whose
                    // hash was taken before the box was renamed describes a box
                    // that no longer exists, and nothing can ever match it again -
                    // it falls through to new discovery below and the real box
                    // ends up with a second record, marked twice, the old one
                    // stranded forever. cachedContents carries no name, so it
                    // still describes the real box: let it be a second way in.
                    // The singleOrNull below is untouched, so an ambiguous answer
                    // still refuses rather than guessing.
                    // The cache route must agree on the NAME as well. Contents
                    // alone put every same-contents record in the running, and two
                    // candidates make singleOrNull refuse - which killed matches
                    // the hash route would otherwise have made. The name is not a
                    // guess between indistinguishable boxes (010's refusal); it is
                    // a signal that tells genuinely different boxes apart, and
                    // throwing it away was the whole bug.
                    val liveContents by lazy { serializeShulkerContents(ss.first) }
                    val stackName = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
                    val recoveryCandidates = trackedShulkers.values.filter {
                        it.uuid !in foundUUIDs && it.uuid !in inOpenMenu &&
                            (it.state == "ex-inv" || it.state == "enderchest") &&
                            (it.contentHash == hash ||
                                ((it.name == stackName || (isUnnamed(it.name) && isUnnamed(stackName))) &&
                                    liveContents?.let { live -> it.cachedContents?.contentEquals(live) } == true))
                    }
                    // Counting, not matching. This used to be singleOrNull() - an
                    // ambiguous answer refused outright - which stranded a record
                    // forever whenever two boxes were genuinely alike, and on a
                    // server where a dozen boxes all read "Shulker Box" that is
                    // most of them. Reversed deliberately (ticket 010, 2026-09-15):
                    // reaching this point means the stack in front of us is
                    // unaccounted for, so the count says one of these records is
                    // owed a box. Claim the oldest, tiebroken by uuid so the pick
                    // cannot depend on HashMap order, and let foundUUIDs stop the
                    // same record answering twice in one scan.
                    //
                    // The price, accepted: with two truly identical boxes the
                    // claim can go to the wrong one, permanently, and the first
                    // rename afterwards inherits the wrong history. The count is
                    // right either way, and the count is what the player reads.
                    //
                    // Vanished records first. "ex-inv" is also the state of a box
                    // sitting in a chest the mod has seen, and claiming the oldest
                    // took an unmarked twin's record out of the chest it was still
                    // in, leaving the marked box that had actually come back
                    // stranded (reported 2026-09-24, hopper between two chests).
                    recoveryCandidates.firstOrNull { it.coords == ss.second }
                        ?: recoveryCandidates.filter { it.lastKnown || it.coords.isEmpty() }
                            .ifEmpty { recoveryCandidates }
                            .minWithOrNull(
                            compareBy(
                                { if (it.firstSeen > 0) it.firstSeen else Long.MAX_VALUE },
                                { it.uuid },
                            )
                        )
                }

                if (recoveryMatch != null) {
                    log("transition: ${recoveryMatch.name} (${recoveryMatch.uuid.take(8)}) ex-inv → inv from=scan:ex_inv_recovery lastKnown=${recoveryMatch.lastKnown}")
                    val wasLK = recoveryMatch.lastKnown
                    injectItemUUID(ss.first, recoveryMatch.uuid)
                    recoveryMatch.state = "inv"
                    recoveryMatch.entity_id = ""
                    // The stale hash is why this needed recovering at all; leaving
                    // it stale means doing this again on every session boundary.
                    if (hash.isNotEmpty()) recoveryMatch.contentHash = hash
                    recoveryMatch.coords = ss.second
                    recoveryMatch.dim = level.dimension().identifier().toString()
                    recoveryMatch.last_update_time = System.currentTimeMillis().toString()
                    recoveryMatch.from = "scan:ex_inv_recovery"
                    recoveryMatch.lastKnown = false
                    if (wasLK && recoveryMatch.happy) trace("§c[LK]§f → §ainv§f §7(${recoveryMatch.name})§f")
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
                    // "Shulker Box", not "???": both mean unnamed to the
                    // resolver, but one spelling means adoptRename and the grid
                    // never see a name they have to special-case.
                    name = ss.first.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box",
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

        // 4. Cleanup: Orphan stale inv entries (not in player inventory anymore).
        // Grace window: an entry touched moments ago (just created/confirmed
        // by THIS or the previous scan) might miss a single pass due to sync
        // timing right at screen open, not because it actually left the
        // inventory. Demoting it immediately erases the position memory pass0
        // relies on, forcing a fresh uuid (and a fresh round of duplicate
        // splitting) next time instead of ever stabilizing.
        val now = System.currentTimeMillis()
        for (entry in trackedShulkers.values) {
            if (entry.state == "inv" && entry.uuid !in foundUUIDs) {
                val age = now - (entry.last_update_time.toLongOrNull() ?: 0L)
                if (age < 1000) continue
                if (entry.uuid in workbenchUUIDs || entry.contentHash in workbenchHashes) {
                    log("scan: ${entry.name} (${entry.uuid.take(8)}) held in an open menu's own slots, not demoted")
                    continue
                }
                // coords still holds the inventory slot this box used to sit in
                // ("hotbar:1"), and ex-inv means "left your inventory, last seen
                // at coords" - so leaving it there produced entries claiming to be
                // external at minecraft:overworld:hotbar:1, which is not a place.
                // Keep it as the last location, and say plainly that where the box
                // went is unknown.
                // Only when there is a place to remember. With both halves empty
                // this wrote the literal ":" - 20 such entries across the saves on
                // this machine - which reads as a location the mod knows and is
                // not one. Null is the honest answer for a box that was never
                // anywhere the mod could name.
                entry.lastLocation = lastLocationOf(entry.dim, entry.coords)
                entry.coords = ""
                entry.state = "ex-inv"
                entry.from = "scan:cleanup"
                entry.last_update_time = now.toString()
                // Without this the flip was never persisted unless some other pass
                // happened to set it, so a relog brought the entry back as "inv"
                // at a slot it had already left.
                changed = true
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
        
        // The placed stack, not the block: on the client the block entity is
        // empty until the server sends its contents, and hashing it then gave
        // the record an empty box's fingerprint - after which nothing matched
        // the record by content again (found by WtfFuzz, 2026-09-24). The block
        // is only the fallback for a stack that could not be fingerprinted.
        val blockHash = hash.ifEmpty {
            if (be != null) {
                val bType = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString()
                fingerprintContainer(be, bType, be.components().get(DataComponents.CUSTOM_NAME))
            } else ""
        }
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
        // Read before the branch below overwrites it. The log used to ask
        // entry.state AFTER setting it to "block", so this line could only ever
        // print "block -> block" - a transition that says nothing happened, on
        // the one scenario CHECKLIST.md opens with. applyDropMatch has done it
        // this way all along; these two sites were the exceptions.
        val oldState = entry?.state ?: "new"
        
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
            if (entry.happy) trace("§aplaced§f → §eblock§f §7(${entry.name})§f")
        }
        
        log("transition: $displayName (${finalUUID.take(8)}) $oldState → block from=${if (entry == null) "place:new" else "place:existing"} slot=$locKey")
        save()
    }


    fun removeTrackedShulker(uuid: String) {
        val entry = trackedShulkers.remove(uuid) ?: return
        transitOrder.remove(uuid)
        log("transition: ${entry.name} (${uuid.take(8)}) ${entry.state} → removed from=ui:manual_remove")
        save()
    }

    internal fun resolveHappyShulkers(): List<ShulkerEntry> {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val entries = mutableListOf<ShulkerEntry>()

        log("resolveHappyShulkers: trackedShulkers=${trackedShulkers.size}")

        for (entry in trackedShulkers.values) {
            if (!entry.happy) continue
            
            val item = BuiltInRegistries.ITEM.get(Identifier.parse(entry.type)).orElse(null)
            val stack = if (item != null) ItemStack(item) else ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX)
            val items = entry.cachedContents?.let { deserializeNbtToItems(it) } ?: List(SHULKER_SLOTS) { ItemStack.EMPTY }
            
            val location = when (entry.state) {
                "block" -> "${entry.dim}:${entry.coords}"
                "ex-inv" -> {
                    // No coords means it left the inventory without the mod seeing
                    // where it went. Say so, rather than rendering a dimension with
                    // an empty position after it.
                    val baseLoc = if (entry.coords.isEmpty()) "location unknown"
                        else "${entry.dim}:${entry.coords}"
                    if (entry.lastKnown) "§c[Last Known]§7 $baseLoc" else baseLoc
                }
                "enderchest" -> if (entry.slotIndex >= 0) "Ender Chest · Slot ${entry.slotIndex + 1}" else "Ender Chest"
                else -> entry.coords
            }
            
            val shulkerEntry = ShulkerEntry(
                id = entry.uuid,
                name = Component.literal(stripPua(entry.name)),
                stack = stack,
                section = entry.state,
                location = location,
                shortHash = entry.uuid.take(6),
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

    // Reused rather than re-obtained per call: getInstance does a provider
    // lookup every time, and a double-chest open/close runs two full 54-slot
    // fingerprint passes. Safe because every caller is main-thread guarded.
    private val sha256 by lazy { MessageDigest.getInstance("SHA-256") }

    private fun fingerprintItems(shulkerId: String, customName: String, items: NonNullList<ItemStack>): String {
        val digest = sha256
        digest.reset()
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
                // hashItemAndComponents leaves the count out, so a box of 5 stone
                // and a box of 20 stone were the same box to every content match -
                // a half-full storage box could take a full one's record and mark.
                // Added in save v18, which re-fingerprints the saved records.
                intToBytes(s.count).forEach { digest.update(it) }
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
    internal fun genericEmptyHash(itemId: String): String {
        return genericEmptyHashCache.getOrPut(itemId) {
            fingerprintItems(itemId, "", NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY))
        }
    }

    // Refilled with EMPTY before each use because copyInto only overwrites the
    // slots the source actually has - a shorter container would otherwise
    // inherit the previous box's tail.
    private val fingerprintScratch: NonNullList<ItemStack> = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY)

    private fun fingerprintFromItem(stack: ItemStack): String? {
        if (!isShulkerItem(stack)) return null
        val container = stack.get(DataComponents.CONTAINER)
        val items = fingerprintScratch
        java.util.Collections.fill(items, ItemStack.EMPTY)
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


    private fun parseVec3(coords: String): Triple<Double, Double, Double>? {
        val parts = coords.split(",")
        if (parts.size != 3) return null
        val x = parts[0].toDoubleOrNull() ?: return null
        val y = parts[1].toDoubleOrNull() ?: return null
        val z = parts[2].toDoubleOrNull() ?: return null
        return Triple(x, y, z)
    }

    // Decorate first: deserializeNbtToItems is a gzip decompress plus 27 codec
    // parses, and inside a comparator it ran O(n log n) times per call - on
    // every chest close, screen close and place. Now once per candidate.
    private fun chooseRicherCache(vararg candidates: ByteArray?): ByteArray? {
        return candidates
            .filterNotNull()
            .map { it to deserializeNbtToItems(it).count { stack -> !stack.isEmpty } }
            .maxWithOrNull(compareBy({ it.second }, { it.first.size }))
            ?.first
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

    // Every field below is per-world. Anything left populated across a server
    // switch is a contamination bug: persistedChestLedger and slotLedger are
    // keyed by strings ("minecraft:overworld:100_64_200", bare slot index) that
    // collide freely between servers, and pendingDropEntities / locateTarget /
    // openChestScreenTick hold *absolute* tick deadlines that never expire once
    // tickCounter restarts at 0. One function so DISCONNECT and load() cannot
    // drift apart.
    private fun resetWorldState() {
        trackedShulkers.clear()
        pendingHandSwap = null
        transitOrder.clear()
        pendingItemEntities.clear()
        pendingDropEntities.clear()
        itemVanishTicks.clear()
        itemVanishInvCount.clear()
        dropExpectations.clear()
        persistedChestLedger.clear()
        slotLedger.clear()
        ledgerMenuId = Int.MIN_VALUE
        clickPreSnapshot = null
        locateTarget = null
        openShulkerKey = null
        openChestPos = null
        openChestInvSnapshot = null
        lastUsedBlockPos = null
        lastUsedBlockTick = Int.MIN_VALUE
        closeHandlerScreen = null
        dupStampPending = false
        markedPlayerSlots.clear()
        markedMenuSlots.clear()
        markerMenuCache.clear()
        markerMenuId = Int.MIN_VALUE
        markedCarried = false
        openChestIsEnderChest = false
        openChestScreenTick = 0
        prevInvShulkerCount = 0
        prevSelectedSlot = -1
        prevHeldUUID = null
        scanQueued = false
        throttleTicks = 0
        hotbarHealTicks = 0
        postJoinGraceTicks = 0
        tickCounter = 0
    }

    // Bumped whenever the on-disk shape changes; migrate() below is gated on it.
    private const val SAVE_VERSION = 18

    // Set when load() could not read an existing save. save() refuses to run
    // while this is true, so a parse failure can never be laundered into an
    // empty file that overwrites recoverable data.
    private var saveBlocked = false

    private fun load() {
        val id = getWorldId() ?: return
        resetWorldState()
        currentWorldId = id
        saveBlocked = false
        postJoinGraceTicks = 100
        if (debugMode) {
            val logFile = getLogFile(id)
            logFile.parentFile.mkdirs()
            logFile.delete()
            logWriter = logFile.bufferedWriter().let { PrintWriter(it) }
            logBytesWritten = 0
            log("--- debug logging started ---")
        }
        val file = getConfigFile(id)
        if (!anySaveExists(file)) return

        val loaded = loadNewestSave(file, ::readSave, ::log)
        if (loaded != null) {
            applySave(loaded.value)
            when (loaded.source) {
                SaveSource.PRIMARY -> {}
                // The game died between writing the new save and moving it into
                // place. Nothing was lost - it is the newest save there is - so
                // this is a log line, not a warning.
                SaveSource.TMP -> log("load: recovered the newest save from an interrupted write (${file.name}.tmp)")
                SaveSource.BACKUP -> {
                    warn("§eSave file was unreadable - recovered from backup.§f")
                    log("load: primary unreadable, recovered from ${file.name}.bak")
                }
            }
            return
        }
        saveBlocked = true
        resetWorldState()
        warn("§cCould not read your WTF save (and no usable backup). Marks are hidden this session and nothing will be overwritten - see ${file.path}§f")
        log("load: FAILED, saves blocked. primary=${file.exists()} backup=${bakOf(file).exists()} tmp=${tmpOf(file).exists()}")
    }

    // Gson builds ShulkerState without the Kotlin constructor - the class has
    // required parameters, so there is no no-arg path - and a field the JSON does
    // not carry is therefore left at the JVM zero value, NOT at the Kotlin
    // default written in the class. An entry saved before `slotIndex` existed
    // loads as slot 0, a real slot, instead of -1, "no slot at all": it then
    // renders as "Ender Chest - Slot 1" and feeds the resolver's slot-continuity
    // tiebreak with a position the box never had. Six entries across the saves on
    // this machine already have that shape, and a v16 file never even reaches
    // migrate(). Fill the gaps here in the tree, where absence is still visible;
    // once bound to the data class, absent and zero are the same thing.
    private fun normalizeEntries(root: JsonObject) {
        val tracked = root.getAsJsonObject("tracked_shulkers") ?: return
        for ((_, element) in tracked.entrySet()) {
            val entry = element as? JsonObject ?: continue
            if (!entry.has("slotIndex")) entry.addProperty("slotIndex", -1)
            if (!entry.has("type")) entry.addProperty("type", "minecraft:shulker_box")
            for (field in ENTRY_STRING_FIELDS) {
                if (!entry.has(field)) entry.addProperty(field, "")
            }
        }
    }

    private val ENTRY_STRING_FIELDS = listOf(
        "entity_id", "dim", "coords", "last_update_time", "name", "contentHash", "from"
    )

    private fun readSave(file: File): ShulkerSave? {
        return try {
            val json = file.readText()
            log("load: reading ${json.length} chars from ${file.name}")
            val root = com.google.gson.JsonParser.parseString(json) as? JsonObject ?: return null
            normalizeEntries(root)
            val parsed = gson.fromJson(root, ShulkerSave::class.java) ?: return null
            // Defensive, not a fix for an observed crash: every ShulkerSave
            // parameter has a default, so Kotlin emits a no-arg constructor and
            // Gson uses it - `{}` binds to an empty map, not to null. Add one
            // parameter without a default and that stops being true, and
            // applySave runs outside any try. The entry class below has no
            // no-arg path at all, which is what normalizeEntries is for.
            @Suppress("SENSELESS_COMPARISON")
            if (parsed.tracked_shulkers == null) {
                log("load: ${file.name} parsed but has no tracked_shulkers")
                return null
            }
            parsed
        } catch (e: Exception) {
            log("load: ${file.name} unreadable: $e")
            null
        }
    }

    private fun applySave(save: ShulkerSave) {
        trackedShulkers.putAll(save.tracked_shulkers)
        migrate(save.version)
        markerIcon = save.markerIcon?.takeIf { it in markerIcons } ?: markerIcons[0]
        markerColorIdx = save.markerColorIdx?.takeIf { it in markerColorOptions.indices } ?: 0
        save.chestSlotLedger?.forEach { (loc, slots) -> persistedChestLedger[loc] = slots.toMutableMap() }
        val totalCached = save.tracked_shulkers.values.count { it.cachedContents != null }
        log("load: loaded tracked_shulkers=${save.tracked_shulkers.size}, cachedContents=$totalCached, version=${save.version}")
    }

    // Version-gated fixups applied to trackedShulkers after a load. Add a branch
    // here (and bump SAVE_VERSION) whenever the persisted shape changes.
    private fun migrate(from: Int) {
        if (from >= SAVE_VERSION) return
        if (from < 15) {
            // Backfill firstSeen so the identity tiebreak has a stable order.
            // Stagger by last_update_time when available so the relative age
            // ordering is preserved rather than collapsed to a tie.
            trackedShulkers.values.filter { it.firstSeen == 0L }.forEach {
                it.firstSeen = it.last_update_time.toLongOrNull() ?: 1L
            }
        }
        if (from < 17) {
            // Until 2.4.0, the scan's cleanup pass flipped an entry to "ex-inv"
            // and left coords holding the inventory slot the box had been in.
            // ex-inv means "left your inventory, last seen at coords", so those
            // entries claim to be external at minecraft:overworld:hotbar:1, and
            // Locate is handed a slot name where a position belongs. Move it to
            // lastLocation, where it is at least true.
            val inventoryKey = Regex("^(hotbar|inv|offhand|craft|armor|slot):")
            for (entry in trackedShulkers.values) {
                if (entry.state != "ex-inv") continue
                if (!inventoryKey.containsMatchIn(entry.coords) && entry.coords != "offhand") continue
                log("migrate: ${entry.name} (${entry.uuid.take(8)}) ex-inv coords '${entry.coords}' is an inventory slot, clearing")
                entry.lastLocation = lastLocationOf(entry.dim, entry.coords)
                entry.coords = ""
            }
        }
        if (from < 18) {
            // The fingerprint now includes item counts, so every stored
            // contentHash is from the old formula. cachedContents is the box's
            // contents as the mod last saw them - every saved record carries it -
            // so the new hash can be recomputed exactly. A cache that will not
            // read back leaves the old hash, which heals on the box's next sighting.
            var redone = 0
            for (entry in trackedShulkers.values) {
                val cache = entry.cachedContents ?: continue
                val items = deserializeNbtToItems(cache)
                if (items.all { it.isEmpty }) continue
                val list = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY)
                items.forEachIndexed { i, st -> if (i < SHULKER_SLOTS) list[i] = st }
                entry.contentHash = fingerprintItems(entry.type, if (isUnnamed(entry.name)) "" else entry.name, list)
                redone++
            }
            log("migrate: re-fingerprinted $redone records with item counts")
        }
        // v16 dropped the unused `nextSerial` field. Gson ignores it on read, so
        // no data fixup is needed - the bump exists to record the shape change.
        log("migrate: save v$from -> v$SAVE_VERSION")
    }

    // Chat is the only channel a normal (non-debug) user ever sees. log() alone
    // is a no-op for them, which is how a corrupt save used to vanish silently.
    // Something went wrong with the user's data and they have to know, whether
    // or not they were doing anything at the time. Never gated.
    private fun warn(msg: String) = chat(msg)

    // CompoundTag.getString returns Optional<String> on some versions and a bare
    // String on others, and this file has to build against both. Read it once,
    // here, and return null on anything unexpected - the old inline version fell
    // back to .toString(), which turns an Optional into the literal text
    // "Optional[...]" and stamps that on as a uuid.
    @Suppress("UNCHECKED_CAST", "USELESS_IS_CHECK", "USELESS_CAST")
    private fun tagString(tag: CompoundTag, key: String): String? {
        val raw: Any? = tag.getString(key)
        return when (raw) {
            is java.util.Optional<*> -> raw.orElse(null) as? String
            is String -> raw
            else -> null
        }
    }

    private fun getItemUUID(stack: ItemStack): String? {
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        if (data.isEmpty) return null
        val tag = data.copyTag()
        if (!tag.contains("wtf:uuid")) return null
        return tagString(tag, "wtf:uuid")?.takeIf { it.isNotEmpty() }
    }

    private fun getBlockEntityUUID(be: BaseContainerBlockEntity): String? {
        val data = be.components().get(DataComponents.CUSTOM_DATA) ?: return null
        if (data.isEmpty) return null
        val tag = data.copyTag()
        if (!tag.contains("wtf:uuid")) return null
        return tagString(tag, "wtf:uuid")?.takeIf { it.isNotEmpty() }
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

    // Every tracked box carries a stamp, marked or not.
    //
    // Stamping only marked boxes was tried on 2026-09-15 and reverted the same
    // morning - see .wayfinder/tickets/011. The cost it was paying for is real
    // (client-side matching compares the whole component patch, so a per-box stamp
    // makes identical boxes into different items and itemscroller's move-matching
    // moves one where it should move three), but every other part of identity
    // treats the stamp as the primary key: position memory rewrites it, the
    // duplicate eviction splits on it, the toggle reads it. Without it those
    // passes fought each other - 284 position-memory rewrites in one session, a
    // marker that faded, twins that lit up, and 28 tracked entries where there
    // should have been 9.
    // "???" is scan:new's old spelling of "unnamed" and "Shulker Box" is
    // everyone else's; ShulkerIdentityResolver already treats both that way
    // (entryNamed). Saves in the wild carry both.
    // "minecraft:overworld:" is not a place. Nine sites built this string by hand
    // and exactly one checked whether there was anything to remember, so an entry
    // that had never been anywhere nameable stored a bare dimension and the grid
    // rendered it as a location it knew. Null is the honest answer, and the list
    // already says "location unknown" for it.
    private fun lastLocationOf(dim: String, coords: String): String? =
        if (coords.isEmpty()) null else "$dim:$coords"

    private fun isUnnamed(n: String) = n.isEmpty() || n == "???" || n == "Shulker Box"

    // An anvil, a grindstone or a smithing table: vanilla's own base class for
    // "two inputs, a synthesized container, one computed result".
    //
    // Nothing may write a stamp into those slots. The write is in-place on the
    // live ItemStack, which the CLIENT menu's lastSlots copy does not see, so
    // the slot compares unequal from then on. Every keystroke in an anvil sends
    // a rename packet, the answer sets the result slot, and ResultContainer's
    // setChanged reaches slotsChanged -> broadcastChanges -> triggerSlotListeners,
    // which then reports slot 0 as changed to AnvilScreen.slotChanged - whose
    // whole body is name.setValue(itemStack.getHoverName()). One keystroke, one
    // wipe, so a tracked box could not be renamed at all.
    //
    // Deliberately this class and not "the mod resolved no container", which is
    // the discriminator ticket 005 used for the scan. That one is also true of a
    // chest minecart, a donkey and any modded container without a block entity,
    // and refusing to stamp there would take the marker off boxes inside them to
    // fix a bug none of them have. ItemCombinerMenu is the whole population with
    // a result slot that recomputes under a text field.
    private fun isWorkbenchMenu(menu: net.minecraft.world.inventory.AbstractContainerMenu): Boolean =
        menu is net.minecraft.world.inventory.ItemCombinerMenu

    // The stack as the SERVER holds it: our stamp is client-only. A click packet
    // carries a hash of every slot the client predicts it changed, and of the
    // cursor; hashed with the stamp, none of them ever matched, so the server
    // "corrected" every one - that is the stamp wipe on every click round-trip,
    // and it was ours. Worse, a correction that lands after the NEXT click has
    // been predicted overwrites it: two clicks inside one round trip (itemscroller,
    // or anyone clicking fast on a laggy server) left a ghost box on the cursor.
    // Hashing without the stamp makes the prediction agree with the server, so
    // it has nothing to correct.
    fun withoutStamp(stack: ItemStack): ItemStack {
        if (getItemUUID(stack) == null) return stack
        return stack.copy().also { stripItemUUID(it) }
    }

    private fun injectItemUUID(stack: ItemStack, uuid: String) {
        val current = stack.get(DataComponents.CUSTOM_DATA) ?: CustomData.EMPTY
        val tag = current.copyTag()
        val existing = tagString(tag, "wtf:uuid")
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
        val screen = mc.gui.screen()

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

        // With no GUI open the key acts on the box in the main hand - which is
        // what the in-game help has always claimed ("box in hand"), while this
        // handler in fact required a container screen with a slot under the
        // cursor and silently did nothing otherwise.
        val holder = mc.player ?: return
        val slot = when (screen) {
            is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*> ->
                (screen as AbstractContainerScreenAccessor).hoveredSlot ?: return
            null -> {
                val selected = holder.inventory.selectedSlot
                holder.inventoryMenu.slots.firstOrNull {
                    it.container === holder.inventory && it.containerSlot == selected
                } ?: return
            }
            else -> return
        }
        val stack = slot.item
        log("toggle: hoveredSlot=${slot.index} x=${slot.x} y=${slot.y} container=${slot.container::class.simpleName} item=${stack.item} stamp=${getItemUUID(stack)?.take(8)}")
        if (stack.isEmpty || !isShulkerItem(stack)) {
            return
        }
        if (stack.count > 1) {
            notify("§ccan't mark a stack§f - split to a single box first")
            return
        }

        // Must match the generic fallback used everywhere identity is
        // validated (ledgerEntryMatchesStack etc) - hoverName.string for an
        // unnamed COLORED box returns e.g. "Blue Shulker Box", not "Shulker
        // Box". Storing that as entry.name made every future scan think a
        // genuinely-unnamed colored box was named (entryNamed check), so
        // its stale generic-fallback "Shulker Box" stackName never matched
        // and the ledger entry got evicted as stale on every chest open.
        val displayName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
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

        // Direct action, no guessing: the player pointed at this exact physical
        // stack and pressed the key, so it gets the stamp it already carries, or
        // a fresh one if it has none. No content/name/ledger-pin matching here -
        // that's what let toggling one box flip an unrelated same-named box's
        // marker too. Slot position is still recorded below for display and as
        // a hint for the SEPARATE chest-scan resolver, but never used here to
        // pick a different identity than what's actually stamped on this stack.
        val hasCustomName = stack.has(DataComponents.CUSTOM_NAME)
        val isExternal = !(player != null && slot.container == player.inventory)
        val posKey = ledgerPosKey(slot.index)
        // An unstamped box in the player's inventory is identified by the slot it
        // sits in. Without this, pressing the key on a box whose stamp the server
        // had wiped - or on any unmarked box, which no longer carries one - minted
        // a FRESH uuid and a second entry beside the one that already owned this
        // slot, so the mark appeared to do nothing and unmarking took several
        // tries, each one toggling a different duplicate.
        val invLocKey = if (player != null && slot.container == player.inventory) {
            inventorySlotToKey(playerSlotIndexOf(player, slot))
        } else null
        val existingHere = if (getItemUUID(stack) != null || invLocKey == null) null else {
            trackedShulkers.values.firstOrNull { it.state == "inv" && it.coords == invLocKey }
        }
        var uuid = existingHere?.uuid ?: ensureItemUUID(stack)
        if (existingHere != null) log("toggle: adopted entry ${uuid.take(8)} already at $invLocKey")
        // A literal item clone (creative dupe, /give copy, etc) copies the
        // wtf:uuid tag along with everything else - byte-identical NBT, no
        // hash difference, nothing to disambiguate by content. Per-slot/
        // per-physical-instance identity is the whole point of tracking, so
        // if this exact stamp is ALSO sitting on a different slot right now,
        // split this one off into its own fresh identity instead of sharing
        // the clone's marker (which made marking one toggle both at once).
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu
            ?: holder.inventoryMenu
        val clonedElsewhere = menu.slots.any { s -> s.index != slot.index && getItemUUID(s.item) == uuid }
        if (clonedElsewhere) {
            val oldUuid = uuid
            uuid = java.util.UUID.randomUUID().toString()
            injectItemUUID(stack, uuid)
            log("toggle: stamp ${oldUuid.take(8)} also present elsewhere - split into fresh ${uuid.take(8)}")
        }
        if (isExternal) {
            val chestLoc = if (openChestIsEnderChest) "Ender Chest"
                else mc.level?.let { lv -> openChestPos?.let { blockLocation(it, lv) } } ?: ""
            slotLedger[posKey] = uuid
            if (chestLoc.isNotEmpty()) persistedChestLedger.getOrPut(chestLoc) { mutableMapOf() }[posKey] = uuid
        }

        // Marking a box that is sitting in a chest recorded the state ("ex-inv")
        // but never where the chest was, so the list rendered the entry's
        // location as an empty "dim:coords" and Locate had nothing to point at.
        // The ender chest is deliberately excluded: it is a per-player inventory,
        // not a place, and its entries carry no coordinates by design.
        val extDim = if (isExternal && !openChestIsEnderChest) {
            mc.level?.dimension()?.identifier()?.toString() ?: ""
        } else ""
        val extCoords = if (isExternal && !openChestIsEnderChest) {
            openChestPos?.let { "${it.x},${it.y},${it.z}" } ?: ""
        } else ""


        // Evict any duplicate uuid stamps from other chest slots now that this
        // slot is authoritatively pinned in the ledger. Stale stamps from
        // pre-1.4.9 blanket name-matching would otherwise make every same-named
        // box show the marker icon.
        if (isExternal) {
            val containerScreen = screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
            if (containerScreen != null && !isWorkbenchMenu(containerScreen.menu)) {
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
                dim = extDim,
                coords = extCoords,
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
            if (extCoords.isNotEmpty()) {
                entry.dim = extDim
                entry.coords = extCoords
            }
            if (isExternal) entry.slotIndex = slot.index
        }
        if (newHappy) notify("§a${getMarkerIcon()}§f marked: §e${displayName}§f")
        else notify("§7☹§f unmarked: §e${displayName}§f")
        save()
    }

    internal fun log(msg: String) {
        if (!debugMode) return
        val w = logWriter ?: return
        if (logBytesWritten >= MAX_LOG_BYTES) return
        w.println(msg)
        // Flushed once per tick instead of once per line (see the tick handler).
        // Debug is the normal working mode here and these calls sit inside tick
        // loops, so per-line flushing was a syscall per log statement. A hard
        // crash now costs at most one tick's worth of lines.
        logDirty = true
        logBytesWritten += msg.toByteArray(Charsets.UTF_8).size + 1
    }

    // Strip Private Use Area chars: some resource packs (e.g. Redstone Tweaks)
    // map these to large guide-table bitmaps, blowing up chat if present in item names.
    private val PUA = Regex("[\\uE000-\\uF8FF]")

    // Strip at every boundary where a name is DRAWN, never where one is stored or
    // hashed. Twelve boxes on mc.haphazarddamage.com carry these characters in the
    // server's own CUSTOM_NAME, so they are in the real item and every fresh
    // fingerprint has them too; sanitizing what we store would change twelve hashes
    // and orphan the entries that own them. Raw on both sides of every comparison,
    // clean on the way to a font. See ticket 004.
    private fun stripPua(s: String) = s.replace(PUA, "")

    // The one chat printer. Every tier below goes through it, so the sanitizer
    // cannot be forgotten by whichever tier a new message picks.
    private fun chat(msg: String) {
        try {
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                Component.literal("§7[§fWTF§7] §f${stripPua(msg)}")
            )
        } catch (e: Exception) {
            log("chat: could not reach chat: $e")
        }
        log(msg)
    }

    // The user did this, or was refused it. ALWAYS ON, release included: a
    // refusal nobody sees is indistinguishable from a broken keybind, which is
    // exactly how "can't mark a stack" read as a dead keybind for a whole
    // release line. Anything that fires without the user acting belongs in
    // trace() below, or in warn() if losing it would cost them data.
    private fun notify(msg: String) = chat(msg)

    // State the mod worked out on its own, that nobody asked to hear about:
    // "chest <- ex-inv" on every container close, every last-known flip, every
    // ledger move. Useful while watching the mod work, noise during play.
    private fun trace(msg: String) {
        if (!debugMode) return
        chat(msg)
    }

    private fun serializeShulkerContents(stack: ItemStack): ByteArray? {
        if (stack.isEmpty || !isShulkerItem(stack)) return null
        val container = stack.get(DataComponents.CONTAINER)
        val items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY)
        container?.copyInto(items)
        return serializeItemListToNbt(items)
    }

    private fun serializeItemListToNbt(items: List<ItemStack>): ByteArray? {
        return try {
            val registries = Minecraft.getInstance().level?.registryAccess() ?: return null
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val listTag = ListTag()
            for (i in 0 until SHULKER_SLOTS) {
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
        // The whole cache format is SHULKER_SLOTS wide, so a bigger container
        // (a modded box) loses its tail. Say so instead of truncating quietly.
        if (container.containerSize > SHULKER_SLOTS) {
            log("serializeContainerToNbt: container has ${container.containerSize} slots, caching first $SHULKER_SLOTS")
        }
        val items = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY)
        for (i in 0 until minOf(container.containerSize, SHULKER_SLOTS)) {
            items[i] = container.getItem(i)
        }
        return serializeItemListToNbt(items)
    }

    fun deserializeNbtToItems(data: ByteArray?): List<ItemStack> {
        if (data == null || data.isEmpty()) {
            return List(SHULKER_SLOTS) { ItemStack.EMPTY }
        }
        return try {
            val registries = Minecraft.getInstance().level?.registryAccess()
                ?: return List(SHULKER_SLOTS) { ItemStack.EMPTY }
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtAccounter.unlimitedHeap())
            val listTag = root.getListOrEmpty("items")
            val result = MutableList(SHULKER_SLOTS) { ItemStack.EMPTY }
            for (i in 0 until minOf(SHULKER_SLOTS, listTag.size)) {
                val tag = listTag.get(i) as? CompoundTag ?: continue
                if (!tag.isEmpty) {
                    result[i] = ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY)
                }
            }
            result
        } catch (e: Exception) {
            log("deserializeNbtToItems failed: $e")
            List(SHULKER_SLOTS) { ItemStack.EMPTY }
        }
    }

    private fun save() {
        // The marker caches are keyed on the STACK's content hash, so marking a
        // box with the keybind changed nothing they could see - the stack is
        // identical, only the tracked entry's happy flag moved, and the cached
        // "not marked" answer stood forever. Every path that changes happiness
        // (both toggles, new entries, removals, clear-all) saves immediately
        // after, so invalidating here covers all of them at once and cannot be
        // forgotten by a future one.
        markerMenuCache.clear()

        val id = currentWorldId ?: return
        if (saveBlocked) return
        val file = getConfigFile(id)
        file.parentFile.mkdirs()

        val happyShulkers = trackedShulkers.filterValues { it.happy }

        val totalCached = happyShulkers.values.count { it.cachedContents != null }
        val totalBytes = happyShulkers.values.mapNotNull { it.cachedContents?.size ?: 0 }.sum()

        if (happyShulkers.isEmpty() && !file.exists() && markerIcon == markerIcons[0] && markerColorIdx == 0) return

        val prunedLedger = persistedChestLedger.mapValues { (_, slots) ->
            slots.filterValues { it in happyShulkers }
        }.filterValues { it.isNotEmpty() }

        val json = gson.toJson(ShulkerSave(
            version = SAVE_VERSION,
            tracked_shulkers = happyShulkers,
            markerIcon = markerIcon,
            markerColorIdx = markerColorIdx,
            chestSlotLedger = prunedLedger
        ))
        log("save: tracked_shulkers=${happyShulkers.size}, cachedContents=$totalCached (${totalBytes}B), json=${json.length} chars")
        persist(file, json)
    }

    private fun persist(file: File, json: String) {
        try {
            writeAtomically(file, json, ::log)
        } catch (e: Exception) {
            // Say so out loud - a silently failing save is how you find out at
            // the next login.
            saveBlocked = true
            warn("§cCould not write your WTF save: $e§f")
            log("save: FAILED, saves blocked: $e")
        }
    }
}

// Slot count of a vanilla shulker box, and the fixed width of the cached
// contents format. serializeContainerToNbt truncates past it (and logs).
internal const val SHULKER_SLOTS = 27

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

// A marked entry, as the marker allocation needs it: where it last remembered
// being in the inventory (null if it is not in the inventory at all), and what
// its contents hash to.
data class MarkerEntry(val uuid: String, val inventorySlotKey: String?, val contentHash: String)

// Which of the player's stacks wear a marker, when the server has just wiped
// their wtf:uuid stamps and nothing can answer for itself. Free of Minecraft
// types on purpose - this is the part with a rule in it, so it is the part
// worth testing (MarkerAllocationTest).
//
// Pass A is position memory, the same authority the scan's pass 0 uses: an
// entry that says it is in the inventory AT THIS SLOT owns the stack sitting
// there. Without it, closing a chest flashed the marker onto whichever twin
// sat lowest until the next scan moved it back.
//
// Pass B has only content left, and content is not identity - so it allocates
// by COUNT rather than matching: one marked entry with this fingerprint still
// unaccounted for means exactly one stack gets the marker. It may sit on the
// wrong twin, which nobody can tell apart anyway; the number is right, and the
// number is what the eye reads. Deciding per stack instead lit up ALL of a set
// of identical boxes whenever one of them was marked.
//
// `fingerprint` is only asked about stacks pass A did not resolve, and returns
// null for a stack whose hash cannot identify anything (an empty box, whose
// hash every empty box of that colour shares).
fun allocateMarkers(
    candidates: List<Pair<Int, String>>,
    entries: List<MarkerEntry>,
    claimed: Set<String>,
    fingerprint: (Int) -> String?,
): Set<Int> {
    val taken = claimed.toMutableSet()
    val awarded = mutableSetOf<Int>()
    val leftovers = mutableListOf<Int>()

    for ((index, slotKey) in candidates.sortedBy { it.first }) {
        val remembered = entries.firstOrNull { it.inventorySlotKey == slotKey && it.uuid !in taken }
        if (remembered == null) {
            leftovers.add(index)
            continue
        }
        taken.add(remembered.uuid)
        awarded.add(index)
    }
    if (leftovers.isEmpty()) return awarded

    val unaccounted = entries
        .filter { it.uuid !in taken && it.contentHash.isNotEmpty() }
        .groupingBy { it.contentHash }
        .eachCount()
        .toMutableMap()
    for (index in leftovers) {
        val fp = fingerprint(index) ?: continue
        val left = unaccounted[fp] ?: 0
        if (left <= 0) continue
        unaccounted[fp] = left - 1
        awarded.add(index)
    }
    return awarded
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


internal fun slotLabel(loc: String): String {
    return keyToLabel(loc)
}
