package dev.matt.wtf.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.matt.wtf.client.mixin.AbstractContainerScreenAccessor
import dev.matt.wtf.client.mixin.ShulkerBoxMenuAccessor
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
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

    data class PendingItemEntity(
        val uuid: String,
        val pos: BlockPos,
        val tick: Int,
        var found: Boolean = false
    )

    fun onEntityRemoved(entityId: Int, reason: net.minecraft.world.entity.Entity.RemovalReason) {
        val eidStr = entityId.toString()
        val shulker = trackedShulkers.values.find { it.entity_id == eidStr && it.state == "item" } ?: return

        val mc = Minecraft.getInstance()
        val player = mc.player ?: return

        // Check if player is near last known coords
        val coords = shulker.coords.split(",").mapNotNull { it.toDoubleOrNull() }
        if (coords.size == 3) {
            val distSq = player.distanceToSqr(coords[0], coords[1], coords[2])
            if (distSq < 64.0) { // 8 blocks radius
                shulker.state = "inv"
                shulker.entity_id = ""
                shulker.last_update_time = System.currentTimeMillis().toString()
                shulker.from = "entity_removed"
                transitOrder.remove(shulker.uuid)
                transitOrder.addLast(shulker.uuid)
                if (shulker.happy) notify("§7item§f → §ainv§f §7(${shulker.name})§f")
                scanQueued = true
                throttleTicks = 10 // Increase buffer to 10 ticks (~500ms) for network sync
                save()
            }
        }
    }

    private var prevHeldUUID: String? = null
    private var prevSelectedSlot: Int = -1
    private var scanQueued: Boolean = false
    private var throttleTicks: Int = 0
    private val THROTTLE_WINDOW = 5

    private const val TRACK_PREFIX = "?:"

    private fun isMarkedForTracking(stack: ItemStack): Boolean {
        val name = stack.get(DataComponents.CUSTOM_NAME)?.string
        return name?.startsWith(TRACK_PREFIX, ignoreCase = true) == true
    }

    private fun getDisplayNameFromStack(stack: ItemStack): String {
        val name = stack.get(DataComponents.CUSTOM_NAME)?.string
        val hasPrefix = name?.startsWith(TRACK_PREFIX, ignoreCase = true) == true
        return if (hasPrefix && name!!.length > TRACK_PREFIX.length) {
            name.substring(TRACK_PREFIX.length).trim()
        } else {
            "Shulker Box"
        }
    }

    private var openChestPos: BlockPos? = null
    private var openChestInvSnapshot: MutableMap<String, ItemStack>? = null

    private fun handleChestScreen(mc: Minecraft, screen: Any) {
        val level = mc.level ?: return
        val hr = mc.hitResult
        if (hr !is net.minecraft.world.phys.BlockHitResult) return
        val pos = hr.blockPos
        val state = level.getBlockState(pos)
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        if (!blockId.contains("chest")) return

        openChestPos = pos
        openChestInvSnapshot = captureInventorySnapshot()
        log("handleChestScreen: chest at ${blockLocation(pos, level)}, snapshot taken")
    }

    private fun handleChestClosed() {
        val pos = openChestPos ?: return
        val snapshot = openChestInvSnapshot ?: return
        openChestPos = null
        openChestInvSnapshot = null

        val mc = Minecraft.getInstance() ?: return
        val level = mc.level ?: return

        val be = level.getBlockEntity(pos) as? BaseContainerBlockEntity ?: return
        val chestLoc = blockLocation(pos, level)

        for (i in 0 until be.containerSize) {
            val stack = be.getItem(i)
            if (stack.isEmpty || !isShulkerItem(stack)) continue

            val stackUUID = getItemUUID(stack) ?: continue
            if (!snapshot.containsKey(stackUUID)) continue

            val existing = trackedShulkers[stackUUID] ?: continue

            val cachedNbt = serializeShulkerContents(stack)
            trackedShulkers[stackUUID] = existing.copy(
                state = "block",
                entity_id = "",
                dim = level.dimension().identifier().toString(),
                coords = "${pos.x},${pos.y},${pos.z}",
                last_update_time = System.currentTimeMillis().toString(),
                from = "chest:inv→block",
                cachedContents = cachedNbt
            )

            injectItemUUID(stack, stackUUID)
            notify("§echest§f ← §ainv§f §7(${existing.name})§f")
            log("handleChestClosed: tracked shulker in chest at $chestLoc, uuid=$stackUUID")
        }
        save()
    }

    private fun captureInventorySnapshot(): MutableMap<String, ItemStack> {
        val mc = Minecraft.getInstance() ?: return mutableMapOf()
        val player = mc.player ?: return mutableMapOf()
        val snapshot = mutableMapOf<String, ItemStack>()
        for (i in 0 until player.inventoryMenu.slots.size) {
            val stack = player.inventoryMenu.getSlot(i).item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            val uuid = getItemUUID(stack) ?: continue
            snapshot[uuid] = stack.copy()
        }
        return snapshot
    }

    data class ShulkerState(
        val uuid: String,
        var state: String, // "block", "item", "inv"
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
        var from: String = ""
    )

    data class ShulkerSave(
        val tracked_shulkers: Map<String, ShulkerState> = emptyMap(),
        val version: Int = 13,
        val nextSerial: Int = 1
    )

    override fun onInitializeClient() {
        val category = net.minecraft.client.KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("wtf", "mod")
        )
        val keyBinding = net.minecraft.client.KeyMapping(
            "key.wtf.shulker_list", InputConstants.Type.KEYSYM, -1, category
        )
        KeyBindingHelper.registerKeyBinding(keyBinding)

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
                    }
                    iterator.remove()
                }
            }

            // 2. Track item entity positions
            for (shulker in trackedShulkers.values) {
                if (shulker.state == "item" && shulker.entity_id.isNotEmpty()) {
                    val entityId = shulker.entity_id.toIntOrNull() ?: continue
                    val entity = level.getEntity(entityId)
                    if (entity != null) {
                        shulker.coords = "${entity.x},${entity.y},${entity.z}"
                        shulker.last_update_time = System.currentTimeMillis().toString()
                    }
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
            dispatcher.register(ClientCommandManager.literal("wtf")
                .executes {
                    val entries = resolveHappyShulkers()
                    Minecraft.getInstance().setScreen(ShulkerGridScreen(entries))
                    1
                }
                .then(ClientCommandManager.literal("debug")
                    .executes {
                        debugMode = !debugMode
                        debugVerbose = false
                        val status = if (debugMode) "§aon§f" else "§coff§f"
                        Minecraft.getInstance().gui.chat.addMessage(
                            Component.literal("§7[§fWTF§7] Debug $status")
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
                    .then(ClientCommandManager.literal("verbose")
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
                            Minecraft.getInstance().gui.chat.addMessage(
                                Component.literal("§7[§fWTF§7] Debug $status")
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
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register { entity, world ->
            if (entity is ItemEntity && isShulkerItem(entity.item)) {
                val mc = Minecraft.getInstance()
                val player = mc.player ?: return@register
                
                // If shulker spawns very close to player, it's likely a drop
                val distSq = entity.distanceToSqr(player)
                if (distSq < 4.0) {
                    val hash = fingerprintFromItem(entity.item) ?: ""
                    // Find a shulker in 'inv' state that is missing from recent scan
                    // Or just find the best match in 'inv' state
                    val match = trackedShulkers.values.firstOrNull { 
                        it.state == "inv" && it.contentHash == hash
                    }
                    
                    if (match != null) {
                        match.state = "item"
                        match.entity_id = entity.id.toString()
                        match.dim = world.dimension().identifier().toString()
                        match.coords = "${entity.x},${entity.y},${entity.z}"
                        match.last_update_time = System.currentTimeMillis().toString()
                        match.from = "spawn:drop"
                        if (match.happy) notify("§ainv§f → §7item§f §7(${match.name})§f")
                        save()
                    }
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
                    handleChestClosed()
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
        Screens.getButtons(screen).add(button)
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
                    log("scan: matched UUID $uuid in ${ss.second}, state update ${entry.state} -> inv")
                    entry.state = "inv"
                    entry.entity_id = ""
                    entry.coords = ss.second
                    entry.dim = level.dimension().identifier().toString()
                    entry.last_update_time = System.currentTimeMillis().toString()
                    entry.from = "scan:uuid"
                    changed = true
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
                    if (candidate.from != "entity_removed" || ageMs > 10_000) return@mapIndexedNotNull null
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
                foundUUIDs.add(match.uuid)
                transitOrder.remove(match.uuid)
                changed = true
                continue
            }

            if (hash.isEmpty()) continue

            // Try to match with an unclaimed shulker in our map
            val match = trackedShulkers.values.firstOrNull { 
                it.uuid !in foundUUIDs && it.contentHash == hash && (it.state == "inv" || it.state == "item" || it.state == "block")
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
                foundUUIDs.add(uuid)
                transitOrder.remove(uuid)
                changed = true
            } else {
                val nameMatch = trackedShulkers.values.firstOrNull {
                    it.uuid !in foundUUIDs &&
                        it.happy &&
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
                    foundUUIDs.add(nameMatch.uuid)
                    transitOrder.remove(nameMatch.uuid)
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

        // 4. Cleanup: Handle items leaving inventory
        for (entry in trackedShulkers.values) {
            if (entry.state == "inv" && entry.uuid !in foundUUIDs) {
                val lastUpdate = entry.last_update_time.toLongOrNull() ?: 0L
                val elapsed = System.currentTimeMillis() - lastUpdate
                if (elapsed > 5000) { // 5 seconds grace
                     log("scan: shulker ${entry.uuid} (${entry.name}) is missing from inventory for ${elapsed}ms, but NOT flipping to item state per user instruction")
                }
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
        
        log("shulker placed: trackedShulkers updated ($coordStr, name=$displayName, uuid=$finalUUID, from_slot=$locKey)")
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
            
            val shulkerEntry = ShulkerEntry(
                id = entry.uuid,
                name = Component.literal(entry.name),
                stack = stack,
                section = entry.state,
                location = if (entry.state == "block") "${entry.dim}:${entry.coords}" else entry.coords,
                shortHash = entry.uuid.take(6),
                serial = 0,
                items = items,
                cachedContentsNbt = entry.cachedContents
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

    private fun isShulkerItem(stack: ItemStack): Boolean {
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        return id.contains("shulker_box")
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
        Minecraft.getInstance().gui.chat.addMessage(Component.literal("§7[§fWTF§7] §f$msg"))
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
            val itemMaps = mutableListOf<Map<String, Any?>>()
            for (i in 0 until 27) {
                val stack = items.getOrNull(i) ?: ItemStack.EMPTY
                if (!stack.isEmpty) {
                    val itemMap = mutableMapOf<String, Any?>()
                    itemMap["id"] = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                    itemMap["count"] = stack.count
                    itemMaps.add(itemMap)
                } else {
                    itemMaps.add(emptyMap())
                }
            }
            gson.toJson(itemMaps).toByteArray(Charsets.UTF_8)
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
        if (data == null) {
            return List(27) { ItemStack.EMPTY }
        }
        if (data.isEmpty()) {
            return List(27) { ItemStack.EMPTY }
        }
        return try {
            val json = String(data, Charsets.UTF_8)
            val items: List<Map<String, Any>> = gson.fromJson(json, ArrayList::class.java) as List<Map<String, Any>>
            val result = MutableList(27) { ItemStack.EMPTY }
            for (i in items.indices) {
                val itemMap = items[i]
                val id = itemMap["id"] as? String
                val count = (itemMap["count"] as? Number)?.toInt() ?: 1
                if (id != null) {
                    val item = BuiltInRegistries.ITEM.get(Identifier.parse(id)).orElse(null)
                    if (item != null) {
                        result[i] = ItemStack(item, count)
                    }
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
            version = 13,
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
