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
import net.minecraft.core.BlockPos
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
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
import net.minecraft.world.phys.AABB
import java.io.File
import java.io.PrintWriter
import java.security.MessageDigest

object WTFClient : ClientModInitializer {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val blockMoods = HashMap<String, ShulkerState>()
    private val invMoods = HashMap<String, ShulkerState>()
    private val transitMoods = HashMap<String, ShulkerState>()
    private var currentWorldId: String? = null
    private var nextSerial = 1
    private var prevInvFingerprint = 0
    private var scanTimer = 0
    private var debugMode = false
    private var debugVerbose = false
    private var logWriter: PrintWriter? = null
    private var logBytesWritten = 0
    private val MAX_LOG_BYTES = 1_000_000
    private val MAX_TRANSIT_SPARE = 20
    private val taggedItems = HashMap<Int, String>()
    private var prevSelectedSlot = -1
    private var tickCounter = 0
    private val transitOrder = ArrayDeque<String>()

    data class ShulkerState(
        val loc: String,
        val name: String,
        val happy: Boolean,
        val uuid: String = "",
        val contentHash: String = "",
        val from: String = "",
        val type: String = "minecraft:shulker_box"
    )

    data class ShulkerSave(
        val version: Int = 10,
        val nextSerial: Int = 1,
        val block: Map<String, ShulkerState> = emptyMap(),
        val inv: Map<String, ShulkerState> = emptyMap(),
        val transit: Map<String, ShulkerState> = emptyMap()
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
                    client.setScreen(ShulkerListScreen(entries))
                }
            }
            val player = client.player ?: return@register
            pickupScan(player)
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(ClientCommandManager.literal("wtf")
                .executes {
                    val entries = resolveHappyShulkers()
                    Minecraft.getInstance().setScreen(ShulkerListScreen(entries))
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
                                val file = FabricLoader.getInstance().configDir.resolve("wtf/$id/debug.log").toFile()
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
                                    val file = FabricLoader.getInstance().configDir.resolve("wtf/$id/debug.log").toFile()
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

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            currentWorldId = null
            blockMoods.clear()
            invMoods.clear()
            transitMoods.clear()
            nextSerial = 1
            prevInvFingerprint = 0
            scanTimer = 0
            prevSelectedSlot = -1
            tickCounter = 0
            transitOrder.clear()
            logWriter?.close()
            logWriter = null
            logBytesWritten = 0
            taggedItems.clear()
        }

        ClientPlayerBlockBreakEvents.AFTER.register { world, _, pos, state ->
            val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            if (!blockId.contains("shulker_box")) return@register
            val loc = blockLocation(pos, world)
            
            // Note: world.getBlockEntity(pos) is likely null here as the block is already broken.
            // We rely on the blockMoods entry matching the location.
            val key = blockMoods.keys.firstOrNull { blockMoods[it]?.loc == loc } ?: return@register
            val entry = blockMoods.remove(key)!!
            
            transitMoods[key] = entry.copy(
                contentHash = entry.contentHash, // Can't easily re-fingerprint if BE is gone
                from = "block→transit",
                type = blockId
            )
            transitOrder.addLast(key)
            save()
            log("block break: $loc → transitMoods (${entry.name}, uuid=${key.take(8)}, type=$blockId)")
            if (entry.happy) notify("§eblock§f → §7transit§f §7(${entry.name}§7)")
        }

        ScreenEvents.AFTER_INIT.register { mc, screen, _, _ ->
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            scanInventory(level, player)
            if (screen is ShulkerBoxScreen) {
                handleShulkerScreen(mc, level, screen)
            }
        }
    }

    private fun handleShulkerScreen(mc: Minecraft, level: Level, screen: ShulkerBoxScreen) {
        val a = screen as AbstractContainerScreenAccessor
        val container = (screen.menu as ShulkerBoxMenuAccessor).container
        val pos = getValidShulkerPos(mc, level) ?: return
        val be = level.getBlockEntity(pos) as? BaseContainerBlockEntity
        val shulkerType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        val displayName = screen.title.string
        val contentHash = fingerprintContainer(container, shulkerType, be?.components()?.get(DataComponents.CUSTOM_NAME))
        val loc = blockLocation(pos, level)

        val beUUID = be?.let { getBlockEntityUUID(it) }
        var currentKey = when {
            beUUID != null && blockMoods.containsKey(beUUID) -> beUUID
            beUUID != null && invMoods.containsKey(beUUID) -> {
                val entry = invMoods.remove(beUUID)!!
                blockMoods[beUUID] = entry.copy(loc = loc, from = "hss:inv→block (uuid)")
                if (entry.happy && entry.loc != loc) notify("§ainv§f → §eblock§f §7(${entry.name})§7")
                beUUID
            }
            beUUID != null && transitMoods.containsKey(beUUID) -> {
                val entry = transitMoods.remove(beUUID)!!
                blockMoods[beUUID] = entry.copy(loc = loc, from = "hss:transit→block (uuid)")
                if (entry.happy && entry.loc != loc) notify("§7transit§f → §eblock§f §7(${entry.name})§7")
                beUUID
            }
            else -> blockMoods.keys.firstOrNull { blockMoods[it]?.loc == loc }
        }

        // Fallback to hash if UUID didn't find anything or is missing
        if (currentKey == null) {
            val invKey = invMoods.keys.firstOrNull { k -> invMoods[k]?.contentHash == contentHash }
            if (invKey != null) {
                val entry = invMoods.remove(invKey)!!
                blockMoods[invKey] = entry.copy(loc = loc, from = "hss:inv→block (hash)")
                if (entry.happy && entry.loc != loc) notify("§ainv§f → §eblock§f §7(${entry.name})§7")
                currentKey = invKey
            } else {
                val transitKey = transitMoods.keys.firstOrNull { k -> transitMoods[k]?.contentHash == contentHash }
                if (transitKey != null) {
                    val entry = transitMoods.remove(transitKey)!!
                    blockMoods[transitKey] = entry.copy(loc = loc, from = "hss:transit→block (hash)")
                    if (entry.happy && entry.loc != loc) notify("§7transit§f → §eblock§f §7(${entry.name})§7")
                    currentKey = transitKey
                }
            }
        }

        val currentEntry = currentKey?.let { blockMoods[it] }
        val isHappy = currentEntry?.happy ?: false

        if (currentKey != null) {
            blockMoods[currentKey] = blockMoods[currentKey]!!.copy(name = displayName, contentHash = contentHash, type = shulkerType)
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

            val entry = blockMoods[finalUUID]
            val newHappy = !(entry?.happy ?: false)
            
            blockMoods[finalUUID] = ShulkerState(
                loc = loc,
                name = displayName,
                happy = newHappy,
                uuid = finalUUID,
                contentHash = contentHash,
                from = if (entry == null) "new-entry" else "toggle",
                type = shulkerType
            )
            
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

    private fun scanInventory(level: Level, player: Player) {
        val inv = player.inventoryMenu
        var changed = false
        for (i in 0 until inv.slots.size) {
            val stack = inv.getSlot(i).item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            
            val hash = fingerprintFromItem(stack) ?: continue
            val stackUUID = ensureItemUUID(stack)
            val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            
            // Priority 1: Direct UUID match in inventory
            val existingInInv = invMoods[stackUUID]
            if (existingInInv != null) {
                if (existingInInv.loc != i.toString()) {
                    invMoods[stackUUID] = existingInInv.copy(loc = i.toString(), from = "scan:uuid-reloc", type = type)
                    changed = true
                }
                continue
            }

            // Priority 2: UUID match in transit or block
            val transitEntry = transitMoods.remove(stackUUID)
            if (transitEntry != null) {
                invMoods[stackUUID] = transitEntry.copy(loc = i.toString(), from = "scan:transit→inv", type = type)
                if (transitEntry.happy) notify("§7transit§f → §ainv§f §7(${transitEntry.name})§7")
                changed = true
                continue
            }

            val blockEntry = blockMoods[stackUUID]
            if (blockEntry != null && isBlockStale(blockEntry.loc, level)) {
                blockMoods.remove(stackUUID)
                invMoods[stackUUID] = blockEntry.copy(loc = i.toString(), from = "scan:block→inv", type = type)
                if (blockEntry.happy) notify("§eblock§f → §ainv§f §7(${blockEntry.name})§7")
                changed = true
                continue
            }

            // Priority 3: Fallback to hash if UUID is new or mismatching
            val hashMatch = transitMoods.keys.firstOrNull { transitMoods[it]?.contentHash == hash }
            if (hashMatch != null) {
                val entry = transitMoods.remove(hashMatch)!!
                // If we match by hash, we merge the identity
                invMoods[stackUUID] = entry.copy(loc = i.toString(), uuid = stackUUID, from = "scan:hash:transit→inv", type = type)
                if (entry.happy) notify("§7transit§f → §ainv§f §7(${entry.name})§7")
                changed = true
                continue
            }

            // New shulker discovery
            invMoods[stackUUID] = ShulkerState(
                loc = i.toString(),
                name = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "???",
                happy = false,
                uuid = stackUUID,
                contentHash = hash,
                from = "scan:new",
                type = type
            )
            // No notify for new "sad" shulkers to avoid spam
        }
        if (changed) save()
    }

    private fun pickupScan(player: Player) {
        val inv = player.inventoryMenu
        val currentSlot = player.inventory.selectedSlot

        if (currentSlot != prevSelectedSlot) {
            prevSelectedSlot = currentSlot
            scanTimer = 0
            quickSlotScan(player, 36 + currentSlot)
            return
        }

        tickCounter++
        if (tickCounter % 2 != 0) return

        val currentFp = (0 until inv.slots.size).map { ItemStack.hashItemAndComponents(inv.getSlot(it).item) }.hashCode()
        scanTimer++
        if (currentFp == prevInvFingerprint && scanTimer < 5) return
        prevInvFingerprint = currentFp
        scanTimer = 0
        
        var changed = false
        if (debugVerbose) log("pickupScan: scan start, ${inv.slots.size} slots")
        for (i in 0 until inv.slots.size) {
            val stack = inv.getSlot(i).item
            if (stack.isEmpty || !isShulkerItem(stack)) continue
            if (!stack.has(DataComponents.CONTAINER)) {
                if (debugVerbose) log("pickupScan: slot $i is shulker but NO CONTAINER")
                continue
            }

            val hash = fingerprintFromItem(stack)!!
            val stackUUID = getItemUUID(stack)
            val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            if (debugVerbose) log("pickupScan: slot $i shulker uuid=$stackUUID hash=${hash.take(8)} type=$type")

            if (stackUUID != null) {
                if (invMoods.containsKey(stackUUID)) {
                    val entry = invMoods[stackUUID]!!
                    if (entry.loc != i.toString()) {
                        invMoods[stackUUID] = entry.copy(loc = i.toString(), from = "pickup:reloc", type = type)
                        log("pickupScan: reloc $stackUUID to $i")
                        changed = true
                    }
                    continue
                }

                val transitEntry = transitMoods.remove(stackUUID)
                if (transitEntry != null) {
                    invMoods[stackUUID] = transitEntry.copy(loc = i.toString(), from = "pickup:transit→inv", type = type)
                    log("pickupScan: transit→inv $stackUUID at $i")
                    if (transitEntry.happy) notify("§7transit§f → §ainv§f §7(${transitEntry.name})§7")
                    changed = true
                    continue
                }

                val blockEntry = blockMoods[stackUUID]
                if (blockEntry != null && isBlockStale(blockEntry.loc, player.level())) {
                    blockMoods.remove(stackUUID)
                    invMoods[stackUUID] = blockEntry.copy(loc = i.toString(), from = "pickup:block→inv", type = type)
                    log("pickupScan: block→inv $stackUUID at $i")
                    if (blockEntry.happy) notify("§eblock§f → §ainv§f §7(${blockEntry.name})§7")
                    changed = true
                    continue
                }
            }

            // Fallback 1: Match by hash if UUID is unknown or missing
            val hashMatch = transitMoods.keys.firstOrNull { transitMoods[it]?.contentHash == hash }
            if (hashMatch != null) {
                val entry = transitMoods.remove(hashMatch)!!
                val finalUUID = stackUUID ?: ensureItemUUID(stack)
                invMoods[finalUUID] = entry.copy(loc = i.toString(), uuid = finalUUID, from = "pickup:hash:transit→inv", type = type)
                log("pickupScan: hash match transit→inv, uuid=$finalUUID at $i")
                if (entry.happy) notify("§7transit§f → §ainv§f §7(${entry.name})§7")
                changed = true
                continue
            }

            // Fallback 2: Robust Reconciliation if UUID and Hash failed
            // If there's exactly one shulker of this type in transit, it's highly likely to be it
            val plausibleMatches = transitMoods.filterValues { it.type == type }
            if (plausibleMatches.size == 1) {
                val matchKey = plausibleMatches.keys.first()
                val entry = transitMoods.remove(matchKey)!!
                val finalUUID = stackUUID ?: ensureItemUUID(stack)
                invMoods[finalUUID] = entry.copy(loc = i.toString(), uuid = finalUUID, from = "pickup:recon:transit→inv", contentHash = hash, type = type)
                log("pickupScan: recon match transit→inv, uuid=$finalUUID at $i (was $matchKey)")
                if (entry.happy) notify("§7transit§f → §ainv§f §7(${entry.name})§7 §7(reconciled)§f")
                changed = true
                continue
            }

            // Unknown shulker - assign ID and track in memory
            val finalUUID = stackUUID ?: ensureItemUUID(stack)
            if (!invMoods.containsKey(finalUUID)) {
                invMoods[finalUUID] = ShulkerState(
                    loc = i.toString(),
                    name = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "???",
                    happy = false,
                    uuid = finalUUID,
                    contentHash = hash,
                    from = "pickup:auto",
                    type = type
                )
                log("pickupScan: new shulker $finalUUID at $i")
                changed = true
            }
        }

        // Detect items leaving inventory
        for ((k, entry) in invMoods.toList()) {
            val slotIdx = entry.loc.toIntOrNull() ?: continue
            if (slotIdx < 0 || slotIdx >= inv.slots.size) continue
            val stack = inv.getSlot(slotIdx).item
            if (!stack.isEmpty && isShulkerItem(stack)) {
                val currentUUID = getItemUUID(stack)
                if (currentUUID == k) continue // Still there
            }
            
            // Shulker is gone from this slot
            transitMoods[k] = entry.copy(from = "inv→transit")
            transitOrder.addLast(k)
            invMoods.remove(k)
            if (entry.happy) notify("§ainv§f → §7transit§f §7(${entry.name})§f")
            changed = true
        }
        
        if (changed) {
            trimTransitCache()
            save()
        }
    }

    private fun quickSlotScan(player: Player, hotbarIdx: Int) {
        val stack = player.inventoryMenu.getSlot(hotbarIdx).item
        if (stack.isEmpty || !isShulkerItem(stack)) return
        val hash = fingerprintFromItem(stack) ?: return
        val stackUUID = ensureItemUUID(stack)
        val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()

        if (invMoods.containsKey(stackUUID)) {
            val entry = invMoods[stackUUID]!!
            if (entry.loc != hotbarIdx.toString()) {
                invMoods[stackUUID] = entry.copy(loc = hotbarIdx.toString(), from = "scroll-reloc", type = type)
                save()
            }
            return
        }

        val transitEntry = transitMoods.remove(stackUUID)
        if (transitEntry != null) {
            invMoods[stackUUID] = transitEntry.copy(loc = hotbarIdx.toString(), from = "scroll:transit→inv", type = type)
            if (transitEntry.happy) notify("§7transit§f → §ainv§f §7(${transitEntry.name})§7")
            save()
            return
        }

        val hashMatch = transitMoods.keys.firstOrNull { transitMoods[it]?.contentHash == hash }
        if (hashMatch != null) {
            val entry = transitMoods.remove(hashMatch)!!
            invMoods[stackUUID] = entry.copy(loc = hotbarIdx.toString(), uuid = stackUUID, from = "scroll:hash:transit→inv", type = type)
            if (entry.happy) notify("§7transit§f → §ainv§f §7(${entry.name})§7")
            save()
            return
        }

        invMoods[stackUUID] = ShulkerState(
            loc = hotbarIdx.toString(),
            name = stack.get(DataComponents.CUSTOM_NAME)?.string ?: "???",
            happy = false,
            uuid = stackUUID,
            contentHash = hash,
            from = "scroll-detect",
            type = type
        )
    }

    @JvmStatic
    fun onShulkerPlaced(player: Player, pos: BlockPos, hand: InteractionHand) {
        if (!player.level().isClientSide) return
        val stack = player.inventory.getItem(if (hand == InteractionHand.MAIN_HAND) player.inventory.selectedSlot else 40)
        if (stack.isEmpty || !isShulkerItem(stack)) return

        val type = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val hash = fingerprintFromItem(stack) ?: ""
        val uuid = getItemUUID(stack)
        
        // Priority 1: Exact UUID or Hash match
        var resolvedKey = uuid ?: findPlacedKey(player, pos)
        
        // Priority 2: Reconciliation fallback
        if (resolvedKey == null) {
            val plausibleMatches = transitMoods.filterValues { it.type == type }
            if (plausibleMatches.size == 1) {
                resolvedKey = plausibleMatches.keys.first()
                log("onShulkerPlaced: recon match transit→block, uuid=$resolvedKey (was $resolvedKey)")
            }
        }

        if (resolvedKey == null) return

        val entry = invMoods.remove(resolvedKey) ?: transitMoods.remove(resolvedKey) ?: return
        
        val loc = blockLocation(pos, player.level())
        val be = player.level().getBlockEntity(pos) as? BaseContainerBlockEntity
        
        if (be != null) {
            val uuidTag = CompoundTag()
            uuidTag.putString("wtf:uuid", resolvedKey)
            val patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_DATA, CustomData.of(uuidTag))
                .build()
            be.applyComponents(be.components(), patch)
        }
        
        val blockHash = if (be != null) {
            val bType = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).block).toString()
            fingerprintContainer(be, bType, be.components().get(DataComponents.CUSTOM_NAME))
        } else hash
        
        blockMoods[resolvedKey] = entry.copy(loc = loc, contentHash = blockHash, from = "place:inv→block", type = type)
        if (entry.happy) notify("§ainv§f → §eblock§f §7(${entry.name})§f")
        log("shulker placed: inv→block (${entry.name}, uuid=$resolvedKey, type=$type)")
        save()
    }

    private fun findPlacedKey(player: Player, pos: BlockPos): String? {
        val level = player.level()
        val be = level.getBlockEntity(pos) as? BaseContainerBlockEntity ?: return null
        val beUUID = getBlockEntityUUID(be)
        if (beUUID != null) return beUUID

        val type = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
        val hash = fingerprintContainer(be, type, be.getCustomName())
        
        return invMoods.keys.firstOrNull { invMoods[it]?.contentHash == hash }
            ?: transitMoods.keys.firstOrNull { transitMoods[it]?.contentHash == hash }
    }

    private fun isBlockStale(loc: String, level: Level): Boolean {
        val pos = parseBlockLoc(loc) ?: return true
        val state = level.getBlockState(pos)
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        return !blockId.contains("shulker_box")
    }

    private fun trimTransitCache() {
        transitOrder.removeAll { it !in transitMoods }
        val nonHappy = transitMoods.filterValues { !it.happy }
        if (nonHappy.size <= MAX_TRANSIT_SPARE) return
        val excess = nonHappy.size - MAX_TRANSIT_SPARE
        val evict = transitOrder.filter { it in nonHappy }.take(excess)
        for (key in evict) {
            val entry = transitMoods.remove(key)
            log("trimTransitCache: removed ${entry?.name ?: "?"} (non-happy, cache full, uuid=$key)")
        }
        transitOrder.removeAll(evict.toSet())
    }

    private fun resolveHappyShulkers(): List<ShulkerEntry> {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return emptyList()
        val player = mc.player ?: return emptyList()
        val entries = mutableListOf<ShulkerEntry>()

        for ((key, entry) in blockMoods) {
            if (!entry.happy) continue
            val shulkerEntry = resolveBlockEntry(level, entry.loc, entry.name, key, 0)
            if (shulkerEntry != null) entries.add(shulkerEntry)
        }

        for ((key, entry) in invMoods) {
            if (!entry.happy) continue
            val shulkerEntry = resolveInvEntry(player, entry.loc, entry.name, key, 0)
            if (shulkerEntry != null) entries.add(shulkerEntry)
        }

        for ((key, entry) in transitMoods) {
            if (!entry.happy) continue
            val item = BuiltInRegistries.ITEM.get(Identifier.parse(entry.type)).orElse(null)
            val stack = if (item != null) ItemStack(item) else ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX)
            entries.add(ShulkerEntry(Component.literal(entry.name), stack, "transit", entry.loc, key.take(8), 0))
        }

        return entries
    }

    private fun resolveBlockEntry(level: Level, loc: String, storedName: String, uuid: String, serial: Int): ShulkerEntry? {
        val pos = parseBlockLoc(loc) ?: return null
        if (!level.isLoaded(pos)) {
            val entry = blockMoods[uuid]
            val item = entry?.let { BuiltInRegistries.ITEM.get(Identifier.parse(it.type)).orElse(null) }
            val stack = if (item != null) ItemStack(item) else ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX)
            return ShulkerEntry(Component.literal(storedName), stack, "block", loc, uuid.take(8), serial)
        }
        
        val state = level.getBlockState(pos)
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        val stack = if (blockId.contains("shulker_box"))
            ItemStack(state.block)
        else {
            val entry = blockMoods[uuid]
            val item = entry?.let { BuiltInRegistries.ITEM.get(Identifier.parse(it.type)).orElse(null) }
            if (item != null) ItemStack(item) else ItemStack(net.minecraft.world.level.block.Blocks.SHULKER_BOX)
        }
        return ShulkerEntry(Component.literal(storedName), stack, "block", loc, uuid.take(8), serial)
    }

    private fun resolveInvEntry(player: Player, loc: String, storedName: String, uuid: String, serial: Int): ShulkerEntry? {
        val slot = loc.toIntOrNull() ?: return null
        val stack = player.inventoryMenu.getSlot(slot).item
        if (stack.isEmpty || !isShulkerItem(stack)) return null
        return ShulkerEntry(Component.literal(storedName), stack, "inv", loc, uuid, serial)
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
        if (debugVerbose) log("fingerprintItems: => ${result.take(8)}")
        return result
    }

    private fun fingerprintContainer(container: Container, shulkerType: String, customName: Component?): String {
        val items = NonNullList.withSize(container.containerSize, ItemStack.EMPTY)
        for (i in 0 until container.containerSize) items[i] = container.getItem(i)
        if (debugVerbose) log("fingerprintContainer: size=${container.containerSize} type=$shulkerType name=${customName?.string ?: ""}")
        return fingerprintItems(shulkerType, customName?.string ?: "", items)
    }

    private fun fingerprintFromItem(stack: ItemStack): String? {
        val container = stack.get(DataComponents.CONTAINER) ?: return null
        val items = NonNullList.withSize(27, ItemStack.EMPTY)
        container.copyInto(items)
        val itemId = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val stackName = stack.get(DataComponents.CUSTOM_NAME)?.string ?: ""
        if (debugVerbose) log("fingerprintFromItem: name=$stackName via CONTAINER")
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

    private fun getConfigFile(worldId: String): File =
        FabricLoader.getInstance().configDir.resolve("wtf/$worldId/moods.json").toFile()

    private fun sanitize(s: String) = s.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

    private fun load() {
        val id = getWorldId() ?: return
        currentWorldId = id
        prevInvFingerprint = 0
        scanTimer = 0
        if (debugMode) {
            val logFile = FabricLoader.getInstance().configDir.resolve("wtf/$id/debug.log").toFile()
            logFile.parentFile.mkdirs()
            logFile.delete()
            logWriter = logFile.bufferedWriter().let { PrintWriter(it) }
            logBytesWritten = 0
            log("--- debug logging started ---")
        }
        val file = getConfigFile(id)
        if (!file.exists()) {
            blockMoods.clear()
            invMoods.clear()
            transitMoods.clear()
            nextSerial = 1
            return
        }
        try {
            val json = gson.fromJson(file.readText(), JsonObject::class.java) ?: return
            val version = json.getAsJsonPrimitive("version")?.asInt ?: return
            if (version !in 5..10) return
            val save = gson.fromJson(json, ShulkerSave::class.java)
            blockMoods.clear()
            invMoods.clear()
            transitMoods.clear()
            blockMoods.putAll(save.block)
            invMoods.putAll(save.inv)
            transitMoods.putAll(save.transit)
            nextSerial = save.nextSerial
        } catch (_: Exception) { }
    }

    private fun getItemUUID(stack: ItemStack): String? {
        val data = stack.get(DataComponents.CUSTOM_DATA)
        val tag = data?.copyTag()
        val uuid = tag?.getString("wtf:uuid")?.orElse("")?.takeIf { it.isNotEmpty() }
        if (debugVerbose && uuid != null) log("getItemUUID: stack has $uuid")
        return uuid
    }

    private fun getBlockEntityUUID(be: BaseContainerBlockEntity): String? {
        return be.components().get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("wtf:uuid")?.orElse("")
            ?.takeIf { it.isNotEmpty() }
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
        if (tag.getString("wtf:uuid").orElse("") == uuid) return
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

    private fun save() {
        val id = currentWorldId ?: return
        val file = getConfigFile(id)
        file.parentFile.mkdirs()
        
        // Persistence Filter: Only save "happy" shulkers to disk
        val happyBlock = blockMoods.filterValues { it.happy }
        val happyInv = invMoods.filterValues { it.happy }
        val happyTransit = transitMoods.filterValues { it.happy }
        
        if (happyBlock.isEmpty() && happyInv.isEmpty() && happyTransit.isEmpty() && !file.exists()) return

        file.writeText(gson.toJson(ShulkerSave(
            version = 9,
            nextSerial = nextSerial,
            block = happyBlock,
            inv = happyInv,
            transit = happyTransit
        )))
    }
}

internal fun slotLabel(loc: String): String {
    val slot = loc.toIntOrNull() ?: return loc
    return when (slot) {
        in 0..8 -> "Hotbar ${slot + 1}"
        in 9..35 -> {
            val idx = slot - 9
            "Inv row ${idx / 9 + 1}, col ${idx % 9 + 1}"
        }
        36 -> "Offhand"
        else -> "Slot $slot"
    }
}
