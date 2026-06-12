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
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
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
    private var currentWorldId: String? = null
    private var nextSerial = 1
    private var debugMode = true
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
        (entity as EntityAccessor).invokeSetSharedFlag(6, true)
    }

    private fun transferToInv(entry: ShulkerState, from: String) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val coords = entry.coords.split(",").mapNotNull { it.toDoubleOrNull() }
        if (coords.size == 3) {
            val distSq = player.distanceToSqr(coords[0], coords[1], coords[2])
            if (distSq < 64.0) {
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


    private var openChestPos: BlockPos? = null
    private var openChestInvSnapshot: MutableMap<String, InventorySnapshotEntry>? = null

    data class InventorySnapshotEntry(
        val slotIndex: Int,
        val locKey: String,
        val stack: ItemStack
    )

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

        openChestPos = null
        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu
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

        openChestInvSnapshot = if (menu != null) captureInventorySnapshot(menu, mc.player) else mutableMapOf()
    }

    private fun handleChestClosed(screen: Any) {
        val mc = Minecraft.getInstance() ?: return
        val level = mc.level ?: return
        val player = mc.player ?: return

        val menu = (screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>)?.menu ?: return

        // Derive block position from the container's block entity (reliable, not hitResult-dependent)
        val containerInfo = getContainerBlockPos(menu, level)
        val containerPos = containerInfo?.first ?: openChestPos ?: return

        val chestLoc = blockLocation(containerPos, level)
        val coordStr = "${containerPos.x},${containerPos.y},${containerPos.z}"
        val dimStr = level.dimension().identifier().toString()

        // 1. Scan all slots in the container to find which shulkers are currently inside
        val shulkersInChest = mutableSetOf<String>()
        for (slot in menu.slots) {
            if (slot.container == player.inventory) continue
            val stack = slot.item
            if (stack.isEmpty || !isShulkerItem(stack)) continue

            val stackUUID = getItemUUID(stack)
            val stackHash = fingerprintFromItem(stack) ?: ""
            val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
            val stackType = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val matchedUUID = stackUUID?.takeIf { it in trackedShulkers }
                ?: resolveTrackedChestStack(stackHash, stackName, stackType, shulkersInChest)
            val uuid = if (matchedUUID != null) {
                if (stackUUID != matchedUUID) {
                    injectItemUUID(stack, matchedUUID)
                }
                matchedUUID
            } else {
                // Assign UUID to untracked shulker found in chest
                val newUuid = ensureItemUUID(stack)
                // Create a tracking entry if not already tracked (discovered in chest)
                if (newUuid !in trackedShulkers) {
                    trackedShulkers[newUuid] = ShulkerState(
                        uuid = newUuid,
                        state = "ex-inv",
                        dim = dimStr,
                        coords = coordStr,
                        last_update_time = System.currentTimeMillis().toString(),
                        name = stackName,
                        happy = false,
                        contentHash = stackHash,
                        from = "chest:new_discovery",
                        type = stackType,
                        cachedContents = serializeShulkerContents(stack)
                    )
                    log("handleChestClosed: new untracked shulker discovered in chest at $chestLoc, uuid=$newUuid name=$stackName")
                }
                newUuid
            }
            shulkersInChest.add(uuid)

            val entry = trackedShulkers[uuid]
            if (entry != null) {
                val cachedNbt = serializeShulkerContents(stack)
                val oldState = entry.state
                val oldCoords = entry.coords
                val oldFrom = entry.from

                if (oldCoords != coordStr || entry.dim != dimStr) {
                    entry.lastLocation = "${entry.dim}:${oldCoords}"
                }
                entry.state = "ex-inv"
                entry.lastKnown = false
                entry.entity_id = ""
                entry.dim = dimStr
                entry.coords = coordStr
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = if (stackUUID == uuid) "chest:uuid" else "chest:matched"
                entry.cachedContents = cachedNbt
                if (stackHash.isNotEmpty()) entry.contentHash = stackHash
                entry.type = stackType

                if (oldState != "ex-inv" || oldCoords != coordStr) {
                    if (entry.happy) notify("§echest§f ← §a${oldState}§f §7(${entry.name})§f")
                    log("handleChestClosed: tracked shulker placed/found in chest at $chestLoc, uuid=$uuid from=${entry.from} oldFrom=$oldFrom")
                }
            }
        }

        var corrected = false

        // If a hopper pulls the shulker out before close, it never appears in the final chest scan.
        // Use the open/close inventory delta to still record the chest as the last known external location.
        val currentInventoryByUuid = captureInventorySnapshot(menu, player)
        val currentInventoryBySlot = captureInventoryBySlot(menu, player)
        for ((uuid, snapshot) in openChestInvSnapshot ?: emptyMap()) {
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

        // 2. Self-Correction: Find shulkers previously marked in THIS chest that are now MISSING
        for (entry in trackedShulkers.values) {
            if (entry.state == "ex-inv" && entry.coords == coordStr && entry.dim == dimStr) {
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
                    } else if (!entry.lastKnown) {
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

    private fun resolveTrackedChestStack(
        stackHash: String,
        stackName: String,
        stackType: String,
        alreadyResolved: Set<String>
    ): String? {
        if (stackHash.isNotEmpty()) {
            val hashMatches = trackedShulkers.values.filter {
                it.uuid !in alreadyResolved &&
                    it.type == stackType &&
                    it.contentHash == stackHash &&
                    (it.state == "inv" || it.state == "item" || it.state == "block" || it.state == "ex-inv")
            }
            val hashMatch = hashMatches.firstOrNull { it.happy } ?: hashMatches.firstOrNull()
            if (hashMatch != null) {
                log("chest resolve: hash-matched $stackName to tracked UUID ${hashMatch.uuid}")
                return hashMatch.uuid
            }
        }

        val nameMatch = trackedShulkers.values.firstOrNull {
            it.uuid !in alreadyResolved &&
                it.happy &&
                it.type == stackType &&
                it.name == stackName
        }
        if (nameMatch != null) {
            log("chest resolve: name-matched $stackName to tracked UUID ${nameMatch.uuid}")
            return nameMatch.uuid
        }

        return null
    }

    private fun findInventoryStackForEntry(
        entry: ShulkerState,
        menu: net.minecraft.world.inventory.AbstractContainerMenu,
        player: Player
    ): Pair<String, ItemStack>? {
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
        var lastKnown: Boolean = false
    )

    data class ShulkerSave(
        val tracked_shulkers: Map<String, ShulkerState> = emptyMap(),
        val version: Int = 14,
        val nextSerial: Int = 1
    )

    override fun onInitializeClient() {
        val category = net.minecraft.client.KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("wtf", "mod")
        )
        val keyBinding = net.minecraft.client.KeyMapping(
            "key.wtf.shulker_list", InputConstants.Type.KEYSYM, -1, category
        )
        KeyMappingHelper.registerKeyMapping(keyBinding)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (keyBinding.consumeClick()) {
                val entries = resolveHappyShulkers()
                if (entries.isNotEmpty()) {
                    client.setScreen(ShulkerGridScreen(entries))
                }
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
                if (tickCounter - pending.tick > 20) {
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
                        (found as EntityAccessor).invokeSetSharedFlag(6, true)
                    }
                    iterator.remove()
                }
            }
            }

            // 1b. Handle pending drop entities (Q-drop): item data may not have
            // synced when ENTITY_LOAD fired, so retry for a few ticks.
            run {
                val dropIterator = pendingDropEntities.iterator()
                val readyEntities = mutableListOf<ItemEntity>()
                while (dropIterator.hasNext()) {
                    val (entityId, spawnTick) = dropIterator.next()
                    if (tickCounter - spawnTick > 20) {
                        dropIterator.remove()
                        continue
                    }
                    val entity = level.getEntity(entityId) as? ItemEntity ?: continue
                    if (!isShulkerItem(entity.item)) continue
                    dropIterator.remove()
                    readyEntities.add(entity)
                }

                if (readyEntities.isNotEmpty()) {
                    val claimedUUIDs = mutableSetOf<String>()
                    var anyMatched = false

                    // A "block" entry can also match if the drop spawned at its
                    // block position (broken by another player, explosion, ...).
                    fun matchableState(e: ShulkerState, entity: ItemEntity): Boolean {
                        if (e.state == "inv" || e.state == "ex-inv") return true
                        if (e.state == "block") {
                            val c = parseVec3(e.coords) ?: return false
                            return entity.distanceToSqr(c.first + 0.5, c.second + 0.5, c.third + 0.5) < 9.0
                        }
                        return false
                    }

                    // Pass 1: identity match via wtf:uuid (survives if uuid is still
                    // present in CUSTOM_DATA on the dropped stack).
                    val unmatched = mutableListOf<ItemEntity>()
                    for (entity in readyEntities) {
                        val itemUUID = getItemUUID(entity.item)
                        val match = itemUUID?.let { trackedShulkers[it] }
                            ?.takeIf { matchableState(it, entity) && it.uuid !in claimedUUIDs }
                        if (match != null) {
                            claimedUUIDs.add(match.uuid)
                            applyDropMatch(match, entity, level)
                            anyMatched = true
                        } else {
                            unmatched.add(entity)
                        }
                    }

                    // Pass 2: content-hash match, but only when the hash is
                    // distinguishing (non-empty/named box). A generic empty/unnamed
                    // box's hash is shared by every box of that color (tracked or
                    // not), so it's never used to match - that's what let untracked
                    // Y steal a tracked entry's identity before.
                    for (entity in unmatched) {
                        val hash = fingerprintFromItem(entity.item) ?: continue
                        val itemId = BuiltInRegistries.ITEM.getKey(entity.item.item).toString()
                        if (hash == genericEmptyHash(itemId)) continue
                        val match = trackedShulkers.values.singleOrNull {
                            matchableState(it, entity) && it.uuid !in claimedUUIDs && it.contentHash == hash
                        }
                        if (match != null) {
                            claimedUUIDs.add(match.uuid)
                            applyDropMatch(match, entity, level)
                            anyMatched = true
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
            if (tickCounter % 5 == 0) {
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
                                        level.getBlockEntity(pos) is BaseContainerBlockEntity
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

            if (scanQueued) {
                if (throttleTicks > 0) {
                    throttleTicks--
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
                val nearPlayer = player != null && entity.distanceToSqr(player) < 4.0
                // Also catch drops near a tracked placed block: covers breaks by
                // other players, explosions, etc. while we're in render distance.
                val nearTrackedBlock = !nearPlayer && trackedShulkers.values.any { e ->
                    e.state == "block" && parseVec3(e.coords)?.let { c ->
                        entity.distanceToSqr(c.first + 0.5, c.second + 0.5, c.third + 0.5) < 9.0
                    } == true
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
            if (screen is AbstractContainerScreenAccessor) {
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
            val hashMatch = trackedShulkers.values.firstOrNull { k ->
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
                log("handleShulkerScreen: no match found, coords=$coordStr beUUID=$beUUID heldUUID=$heldUUID")
            }
        } else {
            log("handleShulkerScreen: matched key=$currentKey")
        }

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
            val key = currentKey
            val finalUUID = beUUID ?: key ?: java.util.UUID.randomUUID().toString()
            
            if (beUUID == null) {
                val uuidTag = CompoundTag()
                uuidTag.putString("wtf:uuid", finalUUID)
                val patch = DataComponentPatch.builder()
                    .set(DataComponents.CUSTOM_DATA, CustomData.of(uuidTag))
                    .build()
                be?.applyComponents(be.components(), patch)
            }

            val entry = trackedShulkers[finalUUID]
            val newHappy = !(entry?.happy ?: false)
            
            if (entry == null) {
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
                    cachedContents = chooseRicherCache(
                        serializeContainerToNbt(container),
                        be?.let { serializeContainerToNbt(it) }
                    )
                )
            } else {
                entry.happy = newHappy
                entry.name = displayName
                entry.contentHash = contentHash
                entry.type = shulkerType
                entry.cachedContents = chooseRicherCache(
                    serializeContainerToNbt(container),
                    be?.let { serializeContainerToNbt(it) },
                    entry.cachedContents
                )
                entry.last_update_time = System.currentTimeMillis().toString()
                entry.from = "toggle"
            }
            
            currentKey = finalUUID
            btn.setMessage(Component.literal(if (newHappy) "\u263A" else "\u2639"))
            if (newHappy) notify("§anew§f happy shulker: §e${displayName}§f")
            else notify("§etoggle§f ${displayName}: §c☹§f")
            save()
        }
            .pos(a.leftPos + a.imageWidth / 2 - 6, a.topPos + 3)
            .size(12, 12)
            .build()
        Screens.getWidgets(screen).add(button)
    }

    private fun handleShulkerScreenClosed(screen: ShulkerBoxScreen) {
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
                entry.cachedContents = newNbt
                entry.contentHash = newHash
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
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            inventoryShulkers.add(Triple(stack, indexToKey(i), fingerprintFromItem(stack) ?: ""))
        }
        if (!inv.carried.isEmpty && isShulkerItem(inv.carried)) {
            inventoryShulkers.add(Triple(inv.carried, "cursor", fingerprintFromItem(inv.carried) ?: ""))
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
                    if (candidate.name != stackName && candidate.name.isNotEmpty()) return@mapIndexedNotNull null

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

            // Try to match with an unclaimed shulker in our map
            val match = trackedShulkers.values.firstOrNull { 
                it.uuid !in foundUUIDs && it.contentHash == hash && (it.state == "inv" || it.state == "item")
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
                val nameMatch = trackedShulkers.values.firstOrNull {
                    it.uuid !in foundUUIDs &&
                        it.happy &&
                        (it.state == "inv" || it.state == "item") &&
                        it.type == stackType &&
                        it.name == stackName
                }

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

                // Ex-inv recovery: re-link ex-inv entries that reappeared in inventory
                val recoveryMatch = trackedShulkers.values.firstOrNull {
                    it.uuid !in foundUUIDs &&
                        it.state == "ex-inv" &&
                        it.contentHash == hash
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
                    type = BuiltInRegistries.ITEM.getKey(ss.first.item).toString()
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
            be.applyComponents(be.components(), patch)
        }
        
        val blockHash = if (be != null) {
            val bType = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString()
            fingerprintContainer(be, bType, be.components().get(DataComponents.CUSTOM_NAME))
        } else hash
        val cachedNbt = if (be != null) serializeContainerToNbt(be) else serializeShulkerContents(stack)
        
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
                cachedContents = cachedNbt
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
                intToBytes(ItemStack.hashItemAndComponents(s)).forEach { digest.update(it) }
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
            nextSerial = save.nextSerial
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

    private fun ensureItemUUID(stack: ItemStack): String {
        val existing = getItemUUID(stack)
        if (existing != null) return existing
        val uuid = java.util.UUID.randomUUID().toString()
        injectItemUUID(stack, uuid)
        return uuid
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

        if (happyShulkers.isEmpty() && !file.exists()) return

        val json = gson.toJson(ShulkerSave(
            version = 14,
            nextSerial = nextSerial,
            tracked_shulkers = happyShulkers
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
