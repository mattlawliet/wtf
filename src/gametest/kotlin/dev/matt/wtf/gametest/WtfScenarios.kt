package dev.matt.wtf.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import java.util.Properties

// Inventory indices, as the server's /item command and Inventory.getItem see
// them. The mod's keys are 1-based: hotbar index 2 is "hotbar:3".
private const val OFFHAND = 40

class WtfScenarios : FabricClientGameTest {
    override fun runTest(ctx: ClientGameTestContext) {
        // Fuzzing is its own run (WtfFuzz); the scenarios step aside for it.
        if (System.getenv("WTF_FUZZ") != null) return
        // Silent: the window is virtual, the speakers are not.
        ctx.runOnClient<RuntimeException> { mc ->
            mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0)
        }
        val h = Harness(ctx)
        val props = Properties().apply {
            setProperty("level-type", "minecraft:flat")
            setProperty("difficulty", "peaceful")
            setProperty("gamemode", "survival")
            setProperty("spawn-protection", "0")
            setProperty("generate-structures", "false")
        }
        ctx.worldBuilder().createServer(props).use { server ->
            h.server = server
            var conn = server.connect()
            // The mod holds off for 100 ticks after a join.
            fun joined() {
                conn.clientLevel.waitForChunksRender()
                ctx.waitTicks(120)
            }
            // `offline` runs between disconnect and reconnect - the only moment
            // the save file can be edited without the mod writing over it.
            val relog = { offline: () -> Unit ->
                h.beforeRelog = h.logLines()
                conn.close()
                offline()
                conn = server.connect()
                joined()
            }
            try {
                joined()
                baseline(h)
                swapTwoMarkedBoxes(h)
                swapWithPlacedTwin(h)
                guiSwapStillFollowed(h)
                massMoveIdenticalBoxes(h)
                hopperCarriesABox(h)
                resyncWithChestOpen(h)
                twinsInAChest(h)
                hopperTwinToAFullerChest(h)
                markedInHandTwinInChest(h)
                swapPlacesWithAnUntrackedTwin(h)
                gridRoundTrip(h)
                placeAndBreakAMarkedTwin(h)
                hopperSnatchRace(h)
                adjacentChests(h)
                vanillaGhostControl(h)
                slotTakenByADifferentBox(h)
                fastClicksLeaveNoGhost(h)
                dyeAMarkedBox(h)
                bulkMove(h)
                handRestock(h)
                tooltipHover(h)
                groundTwinAcrossRelog(h, relog)
                enderAcrossRelog(h, relog)
            } finally {
                conn.close()
            }
        }
        h.finish()
    }

    // Is the harness itself honest: a box handed over by the server, marked
    // with the keybind, shows up as one marked record at the slot it is in.
    private fun baseline(h: Harness) = h.scenario("baseline: mark a box in hand") {
        h.area()
        h.put("hotbar.0", h.box("Alpha", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.ctx.waitTicks(20)
        val r = h.one("Alpha")
        h.check(r.happy, "Alpha not marked: $r")
        h.check(r.state == "inv" && r.coords == "hotbar:1", "Alpha at ${r.state} ${r.coords}")
        h.check(h.stampAt(0) == r.uuid, "stamp on hotbar 1 is ${h.stampAt(0)}, record is ${r.uuid}")
    }

    // Two different marked boxes, one in each hand, F with no screen open. The
    // server swaps them and answers with stamp-less copies of both; the client
    // never predicted the move, so position memory still names the old slots.
    private fun swapTwoMarkedBoxes(h: Harness) = h.scenario("015: F swaps two marked boxes (hands both full)") {
        h.area()
        h.put("hotbar.2", h.box("Left", h.uniqueFill()))
        h.put("hotbar.3", h.box("Right", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(2)
        h.markInHand(3)
        // Right goes to the offhand the ordinary way - F on it - and is checked
        // there before the gesture under test.
        h.pressF()
        h.ctx.waitTicks(60)
        val right0 = h.one("Right")
        h.note("after first F: Right at ${right0.coords}")
        h.check(right0.coords == "offhand", "setup: Right should be in the offhand, is at ${right0.coords}")

        h.select(2)
        h.pressF()
        h.ctx.waitTicks(80)
        val left = h.one("Left")
        val right = h.one("Right")
        h.note("after swap: Left=${left.state}/${left.coords} Right=${right.state}/${right.coords}")
        h.check(h.itemNameAt(OFFHAND) == "Left", "offhand holds ${h.itemNameAt(OFFHAND)}, expected Left")
        h.check(left.coords == "offhand", "Left record says ${left.coords}, box is in the offhand")
        h.check(right.coords == "hotbar:3", "Right record says ${right.coords}, box is in hotbar:3")
        h.check(h.stampAt(OFFHAND) == left.uuid, "offhand stamp ${h.stampAt(OFFHAND)?.take(8)} != Left ${left.uuid.take(8)}")
        h.check(h.stampAt(2) == right.uuid, "hotbar:3 stamp ${h.stampAt(2)?.take(8)} != Right ${right.uuid.take(8)}")
        h.check(left.happy && right.happy, "a mark was lost: Left=${left.happy} Right=${right.happy}")
    }

    // Ticket 015 as written: F moves a box to an empty offhand, and it is found
    // again by content alone - where an OLDER identical twin, placed as a block,
    // is the first candidate the hash match offers.
    private fun swapWithPlacedTwin(h: Harness) = h.scenario("015: F with an identical twin placed nearby") {
        val a = h.area()
        val fill = h.uniqueFill()
        h.put("hotbar.0", h.box("Twin", fill))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.placeFromHotbar(0, a.below())
        h.ctx.waitTicks(40)
        val placed = h.one("Twin")
        h.check(placed.state == "block", "setup: first twin should be placed, is ${placed.state}")

        h.put("hotbar.1", h.box("Twin", fill))
        h.ctx.waitTicks(60)
        h.markInHand(1)
        h.ctx.waitTicks(20)
        val held = h.named("Twin").singleOrNull { it.state == "inv" }
        h.check(held != null, "setup: no inv record for the held twin: ${h.named("Twin")}")
        if (held == null) return@scenario

        h.pressF()
        h.ctx.waitTicks(80)
        val after = h.named("Twin")
        h.note("after F: " + after.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}" })
        val placedNow = after.single { it.uuid == placed.uuid }
        val heldNow = after.single { it.uuid == held.uuid }
        h.check(placedNow.state == "block" && placedNow.coords == placed.coords,
            "the PLACED twin's record moved: ${placedNow.state}/${placedNow.coords}")
        h.check(heldNow.state == "inv" && heldNow.coords == "offhand",
            "the held twin's record is ${heldNow.state}/${heldNow.coords}, box is in the offhand")
        h.check(h.stampAt(OFFHAND) == held.uuid, "offhand stamp ${h.stampAt(OFFHAND)?.take(8)} != ${held.uuid.take(8)}")
        h.check(after.size == 2, "expected two Twin records, have ${after.size}")

        h.pressF()
        h.ctx.waitTicks(80)
        val back = h.named("Twin").single { it.uuid == held.uuid }
        h.check(back.coords == "hotbar:2", "after F back, held twin record says ${back.coords}")
    }

    // Ticket 010's open demo: three boxes nothing can tell apart, moved into a
    // chest and back out in single ticks. Three records must come back, not one
    // and two ghosts.
    private fun massMoveIdenticalBoxes(h: Harness) = h.scenario("010: three identical boxes moved in one gesture") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        val fill = h.uniqueFill()
        for (i in 3..5) h.put("hotbar.$i", h.box("Trio", fill))
        h.ctx.waitTicks(60)
        for (i in 3..5) h.markInHand(i)
        h.ctx.waitTicks(20)
        val before = h.named("Trio")
        h.check(before.size == 3 && before.all { it.happy }, "setup: $before")

        h.openAt(a)
        h.quickMoveAll((3..5).map { h.menuSlotOfInventory(it) })
        h.closeScreen()
        h.ctx.waitTicks(40)
        val inChest = h.named("Trio")
        h.note("in chest: " + inChest.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}#${it.slotIndex}" })
        h.check(inChest.size == 3, "expected 3 records after the move in, have ${inChest.size}")
        // A box in a chest is "ex-inv" at the chest's coords, slotIndex set.
        h.check(inChest.all { it.state == "ex-inv" && it.coords == "${a.x},${a.y},${a.z}" }, "not all in the chest: $inChest")
        h.check(inChest.map { it.slotIndex }.toSet() == setOf(0, 1, 2), "chest slots: ${inChest.map { it.slotIndex }}")

        h.openAt(a)
        h.quickMoveAll((0..2).map { h.menuSlotOfContainer(it) })
        h.closeScreen()
        h.ctx.waitTicks(80)
        val out = h.named("Trio")
        h.note("back out: " + out.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}" })
        h.check(out.size == 3, "expected 3 records, have ${out.size}")
        h.check(out.all { it.happy }, "a mark was lost: $out")
        h.check(out.all { it.state == "inv" }, "not all back in the inventory: $out")
        h.check(out.map { it.coords }.toSet().size == 3, "records share a slot: $out")
        val stamps = (0..35).mapNotNull { h.stampAt(it) }.toSet()
        h.check(out.all { it.uuid in stamps }, "a record's uuid is on no stack: $out vs ${stamps.map { it.take(8) }}")
        h.check(before.map { it.uuid }.toSet() == out.map { it.uuid }.toSet(), "identities changed: before $before after $out")
    }

    // Regression for the 015 fix: F over a slot INSIDE a screen is a menu
    // click, never the packet, and must still be followed by the ledger.
    private fun guiSwapStillFollowed(h: Harness) = h.scenario("015 regression: F inside the inventory screen") {
        h.area()
        h.put("hotbar.1", h.box("Gui", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(1)
        h.ctx.getInput().pressKey { it.keyInventory }
        h.ctx.waitTicks(10)
        h.ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            val slot = p.containerMenu.slots.first { it.container === p.inventory && it.containerSlot == 1 }.index
            mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, slot, OFFHAND, net.minecraft.world.inventory.ContainerInput.SWAP, p)
        }
        h.ctx.waitTicks(10)
        h.closeScreen()
        h.ctx.waitTicks(60)
        val r = h.one("Gui")
        h.check(r.coords == "offhand", "Gui record says ${r.coords}, box is in the offhand")
        h.check(h.stampAt(OFFHAND) == r.uuid, "offhand stamp ${h.stampAt(OFFHAND)?.take(8)} != ${r.uuid.take(8)}")
    }

    // Ticket 003, path 2: a hopper moves a marked box from one chest to another
    // while nobody is looking. Opening the destination must find the SAME
    // record, not mint a second one for the same box.
    private fun hopperCarriesABox(h: Harness) = h.scenario("003: hopper carries a box between chests") {
        val a = h.area()
        val chestA = a.above()
        val chestB = a.south()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:hopper[facing=south]")
        h.cmd("setblock ${a.x + 1} ${a.y} ${a.z} minecraft:redstone_block") // locked until we say
        h.cmd("setblock ${chestA.x} ${chestA.y} ${chestA.z} minecraft:chest")
        h.cmd("setblock ${chestB.x} ${chestB.y} ${chestB.z} minecraft:chest")
        h.put("hotbar.0", h.box("Hop", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.openAt(chestA)
        h.quickMoveAll(listOf(h.menuSlotOfInventory(0)))
        h.closeScreen()
        h.ctx.waitTicks(40)
        val inA = h.one("Hop")
        h.check(inA.state == "ex-inv" && inA.coords == "${chestA.x},${chestA.y},${chestA.z}", "setup: Hop at ${inA.state}/${inA.coords}")

        h.cmd("setblock ${a.x + 1} ${a.y} ${a.z} minecraft:air")
        h.ctx.waitTicks(60)
        h.openAt(chestB)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val all = h.named("Hop")
        h.note("after hopper: " + all.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}#${it.slotIndex} happy=${it.happy}" })
        h.check(all.size == 1, "one box, ${all.size} records")
        val r = all.firstOrNull { it.uuid == inA.uuid }
        h.check(r != null && r.coords == "${chestB.x},${chestB.y},${chestB.z}", "the original record did not follow the box to chest B: $r")
        h.check(r?.happy == true, "the mark was lost")
    }

    // Ticket 003, path 3: the server resends the whole open container - every
    // client-only stamp in it and in the inventory gone at once.
    private fun resyncWithChestOpen(h: Harness) = h.scenario("003: full resync while a chest is open") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        h.put("hotbar.0", h.box("Sync", h.uniqueFill()))
        h.put("hotbar.1", h.box("Stay", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.markInHand(1)
        h.openAt(a)
        h.quickMoveAll(listOf(h.menuSlotOfInventory(0)))
        h.server.runOnServer<RuntimeException> { s ->
            s.playerList.getPlayerByName("Player0")!!.containerMenu.sendAllDataToRemote()
        }
        h.ctx.waitTicks(40)
        h.closeScreen()
        h.ctx.waitTicks(40)
        h.openAt(a)
        h.closeScreen()
        h.ctx.waitTicks(60)
        val sync = h.named("Sync")
        val stay = h.named("Stay")
        h.note("Sync: $sync")
        h.check(sync.size == 1, "one box, ${sync.size} Sync records")
        h.check(sync.all { it.happy && it.state == "ex-inv" && it.coords == "${a.x},${a.y},${a.z}" }, "Sync not where it is: $sync")
        h.check(stay.size == 1 && stay[0].coords == "hotbar:2", "Stay (never moved) says $stay")
        h.check(stay.size == 1 && h.stampAt(1) == stay[0].uuid, "Stay's stamp was not put back: ${h.stampAt(1)}")
    }

    // Found by WtfFuzz (seed 14): the marked twin lying on the ground, its
    // unmarked twin in the hotbar. Entity ids do not survive a relog and
    // unmarked records are not saved, so the hotbar twin claimed the ground
    // twin's record - and its mark.
    private fun groundTwinAcrossRelog(h: Harness, relog: (() -> Unit) -> Unit) = h.scenario("marked twin on the ground across a relog") {
        h.area()
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "lime_shulker_box")
        h.put("hotbar.0", twin)
        h.put("hotbar.1", twin)
        h.ctx.waitTicks(60)
        h.markInHand(0)
        val marked = h.records().single { it.happy && it.state == "inv" && it.coords == "hotbar:1" }
        h.select(0)
        h.ctx.getInput().pressKey { it.keyDrop }
        h.ctx.waitTicks(60)
        val before = h.records().single { it.uuid == marked.uuid }
        h.note("before relog: ${before.state}/${before.coords.take(30)}")
        h.check(before.state == "item", "setup: the marked twin is not on the ground: ${before.state}")
        relog {}
        h.ctx.waitTicks(60)
        val rs = h.records().filter { it.hash == marked.hash }
        h.note("after relog: " + rs.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords.take(20)} happy=${it.happy}" })
        h.check(rs.singleOrNull { it.happy }?.let { it.uuid == marked.uuid && it.state == "item" } == true,
            "the mark left the ground twin: $rs")
        h.check(rs.filter { !it.happy }.all { it.state == "inv" }, "the hotbar twin is not an unmarked inv record: $rs")
    }

    // Ticket 003, path 4: a marked box in the ender chest across a relog. The
    // ender chest has no position, so the record's identity is slot + content.
    private fun enderAcrossRelog(h: Harness, relog: (() -> Unit) -> Unit) = h.scenario("003: ender chest box across a relog") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:ender_chest")
        h.put("hotbar.0", h.box("Ender", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.openAt(a)
        h.quickMoveAll(listOf(h.menuSlotOfInventory(0)))
        h.closeScreen()
        h.ctx.waitTicks(40)
        val before = h.one("Ender")
        h.check(before.state == "enderchest" && before.happy, "setup: Ender at ${before.state} happy=${before.happy}")

        // Save v18 added item counts to the fingerprint. Turn the save back into
        // a v17 one with a stale hash, and the load must recompute it from the
        // record's cached contents.
        relog {
            val save = java.io.File(h.gameDir, "config/wtf").listFiles()!!
                .map { java.io.File(it, "moods.json") }.filter { it.exists() }.maxBy { it.lastModified() }
            val root = com.google.gson.JsonParser.parseString(save.readText()).asJsonObject
            root.addProperty("version", 17)
            root.getAsJsonObject("tracked_shulkers").getAsJsonObject(before.uuid).addProperty("contentHash", "stale")
            save.writeText(root.toString())
        }
        h.check(h.named("Ender").size == 1, "after relog, before opening: ${h.named("Ender")}")
        val migrated = h.named("Ender").singleOrNull()
        h.check(migrated?.hash == before.hash, "v17 -> v18 did not recompute the hash: ${migrated?.hash} vs ${before.hash}")
        h.cmd("tp Player0 ${a.x - 2}.5 -60 ${a.z}.5 -90 30")
        h.ctx.waitTicks(20)
        h.openAt(a)
        h.closeScreen()
        h.ctx.waitTicks(60)
        val after = h.named("Ender")
        h.note("after relog + open: $after")
        h.check(after.size == 1, "one box, ${after.size} records")
        h.check(after.singleOrNull()?.uuid == before.uuid, "identity changed across the relog")
        h.check(after.all { it.happy && it.state == "enderchest" }, "not a marked ender record: $after")
        // Everything marked earlier in the run must have survived the relog too.
        if (h.ran.any { it.contains("full resync") }) {
            val sync = h.named("Sync")
            h.check(sync.size == 1 && sync[0].happy, "Sync after relog: $sync")
        }
    }

    // Reported 2026-09-24 in the dev client: two unnamed pink boxes with the same
    // contents in a chest, mark one, and both show the marker. Moving the
    // unmarked twin then carried the MARKED identity with it.
    private fun twinsInAChest(h: Harness) = h.scenario("twins in a chest: mark one, move the other, reopen") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "pink_shulker_box")
        h.cmd("item replace block ${a.x} ${a.y} ${a.z} container.11 with $twin")
        h.cmd("item replace block ${a.x} ${a.y} ${a.z} container.12 with $twin")
        h.openAt(a)
        val s11 = h.menuSlotOfContainer(11)
        val s12 = h.menuSlotOfContainer(12)
        h.hover(s12)
        h.pressToggle()
        h.ctx.waitTicks(10)
        val afterMark = h.markedMenuSlots()
        h.note("marked slots after marking container 12: $afterMark")
        h.check(afterMark == setOf(s12), "marking one twin lit $afterMark, expected only $s12")

        h.carry(s11, h.menuSlotOfContainer(20))
        h.ctx.waitTicks(10)
        val afterMove = h.markedMenuSlots()
        h.note("marked slots after moving the unmarked twin to 20: $afterMove")
        h.check(afterMove == setOf(s12), "after moving the unmarked twin, markers on $afterMove, expected only $s12")
        h.closeScreen()
        h.ctx.waitTicks(40)

        h.openAt(a)
        h.ctx.waitTicks(10)
        val reopened = h.markedMenuSlots()
        h.note("marked slots after reopening: $reopened")
        h.check(reopened == setOf(s12), "after reopening, markers on $reopened, expected only $s12")
        // His log's layout at the reopen: the unmarked twin in an EARLIER slot
        // than the marked one, which the per-slot seeding order used to hand
        // the marked record.
        h.carry(h.menuSlotOfContainer(20), s11)
        h.closeScreen()
        h.ctx.waitTicks(40)
        h.openAt(a)
        h.ctx.waitTicks(10)
        val earlier = h.markedMenuSlots()
        h.note("marked slots after reopening with the twin back in 11: $earlier")
        h.check(earlier == setOf(s12), "twin in the earlier slot: markers on $earlier, expected only $s12")
        h.closeScreen()
        val marked = h.records().filter { it.happy && it.type == "minecraft:pink_shulker_box" && it.coords == "${a.x},${a.y},${a.z}" }
        h.note("marked pink records here: " + marked.joinToString { "${it.uuid.take(8)}#${it.slotIndex}" })
        h.check(marked.size == 1, "expected one marked record in the chest, have ${marked.size}")
    }

    // Reported 2026-09-24: one of two identical pink boxes, marked, dropped into
    // a chest over a hopper. It showed marked in the chest below, but taking it
    // out gave no mark and no "retrieved" - the unmarked twin's record, still
    // sitting in the first chest, was claimed instead. The chest below already
    // held boxes, so the arrival landed at a different slot number than it left.
    private fun hopperTwinToAFullerChest(h: Harness) = h.scenario("hopper: marked twin lands in a fuller chest") {
        val a = h.area()
        val home = BlockPos(a.x - 1, a.y, a.z + 2)
        val top = a.above()
        val bottom = a.south()
        h.cmd("setblock ${home.x} ${home.y} ${home.z} minecraft:chest")
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:hopper[facing=south]")
        h.cmd("setblock ${top.x} ${top.y} ${top.z} minecraft:chest")
        h.cmd("setblock ${bottom.x} ${bottom.y} ${bottom.z} minecraft:chest")
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "pink_shulker_box")
        // The unmarked twin is seen first, so its record is the OLDER one - the
        // one a claim-the-oldest rule reaches for.
        h.cmd("item replace block ${home.x} ${home.y} ${home.z} container.19 with $twin")
        h.openAt(home)
        h.closeScreen()
        h.ctx.waitTicks(20)
        h.cmd("item replace block ${home.x} ${home.y} ${home.z} container.20 with $twin")
        for (i in 0..2) h.cmd("item replace block ${bottom.x} ${bottom.y} ${bottom.z} container.$i with ${h.box("Filler$i", h.uniqueFill())}")

        // Both twins get records; mark the one in 20 and take it out.
        h.openAt(home)
        val s20 = h.menuSlotOfContainer(20)
        h.hover(s20)
        h.pressToggle()
        h.quickMoveAll(listOf(s20))
        h.closeScreen()
        h.ctx.waitTicks(40)
        val marked = h.records().single { it.happy && it.type == "minecraft:pink_shulker_box" && it.state == "inv" }
        h.note("marked twin ${marked.uuid.take(8)} at ${marked.state}/${marked.coords}")

        // Into the top chest; the hopper takes it while the screen is open.
        h.openAt(top)
        val inv = marked.coords.removePrefix("hotbar:").toInt() - 1
        h.quickMoveAll(listOf(h.menuSlotOfInventory(inv)))
        // Long enough for the hopper (8 ticks), short of the scan's cleanup -
        // so it is the close handler that notices the box gone, as in his log.
        h.ctx.waitTicks(4)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val lk = h.records().single { it.uuid == marked.uuid }
        h.note("after the hopper: ${lk.state}/${lk.coords}#${lk.slotIndex} from=${lk.from}")

        h.openAt(bottom)
        h.ctx.waitTicks(10)
        val s3 = h.menuSlotOfContainer(3)
        h.check(s3 in h.markedMenuSlots(), "the arrival in slot 3 shows no marker: ${h.markedMenuSlots()}")
        h.check(h.logLines().any { it.contains("chest seed s3 -> ${marked.uuid.take(8)}") },
            "opening the bottom chest did not bind the arrival to the marked record")
        h.quickMoveAll(listOf(s3))
        h.closeScreen()
        h.ctx.waitTicks(60)
        val after = h.records().filter { it.contentHash() == marked.hash }
        h.note("pink records: " + after.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords} happy=${it.happy}" })
        val mine = after.single { it.uuid == marked.uuid }
        h.check(mine.state == "inv" && mine.happy, "the marked record did not come back with the box: ${mine.state} happy=${mine.happy}")
        h.check(after.count { it.happy } == 1, "marks: ${after.count { it.happy }}")
        h.check(after.filter { it.uuid != marked.uuid }.all { it.state == "ex-inv" && it.coords == "${home.x},${home.y},${home.z}" },
            "the unmarked twin's record moved: $after")
        val log = h.logLines()
        h.check(log.any { it.contains("[LK]") && it.contains("inv") }, "no retrieved line (LK -> inv) in the log")
    }

    // Reported 2026-09-24: a marked box in the hotbar, its identical unmarked twin
    // in a chest. Opening the chest resends the inventory without stamps, the
    // twin was seeded with the MARKED identity, and the two then fought over it
    // every scan - while itemscroller's alt-move grouped them or not depending
    // on which instant you clicked.
    private fun markedInHandTwinInChest(h: Harness) = h.scenario("marked in hand, twin in chest: open keeps them apart") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "pink_shulker_box")
        h.cmd("item replace block ${a.x} ${a.y} ${a.z} container.5 with $twin")
        h.put("hotbar.0", twin)
        h.ctx.waitTicks(60)
        h.markInHand(0)
        val marked = h.records().single { it.happy && it.state == "inv" && it.coords == "hotbar:1" }
        // First open gives the twin a record of its own.
        h.openAt(a)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val beforeLines = h.logLines().size

        h.openAtNow(a)
        val s5 = h.menuSlotOfContainer(5)
        val hotbarStamp = h.stampAt(0)
        val twinStamp = h.menuStamp(s5)
        val same = h.sameStack(0, s5)
        h.note("one tick after opening: hotbar stamp ${hotbarStamp?.take(8)}, twin stamp ${twinStamp?.take(8)}, same stack=$same")
        h.check(hotbarStamp == marked.uuid, "the marked box is bare one tick after the chest opened")
        h.check(twinStamp != marked.uuid, "the twin in the chest wears the marked identity")
        h.check(!same, "the marked box and its twin are the same stack to itemscroller")
        h.ctx.waitTicks(100)
        h.check(h.stampAt(0) == marked.uuid, "the marked box lost its stamp while the chest was open")
        h.check(s5 !in h.markedMenuSlots(), "the twin shows the marker")
        val fights = h.logLines().drop(beforeLines).count { it.contains("duplicate stamp stripped") }
        h.check(fights == 0, "$fights duplicate-stamp strips while the chest was open")
        h.closeScreen()
        h.ctx.waitTicks(40)
        val mine = h.records().filter { it.hash == marked.hash }
        h.note("records: " + mine.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords} happy=${it.happy}" })
        h.check(mine.count { it.happy } == 1 && mine.single { it.happy }.uuid == marked.uuid && mine.single { it.happy }.state == "inv",
            "the mark moved: $mine")
    }

    // Same session, the other direction: the marked box goes INTO the chest and
    // an identical twin the mod has no record of comes OUT. The marked record
    // still reads "inv" until the chest closes, so the scan's content match gave
    // the twin the marked identity - while the ledger knew the real box was in
    // the chest - and the duplicate eviction fought it every scan.
    private fun swapPlacesWithAnUntrackedTwin(h: Harness) = h.scenario("marked box in, untracked twin out") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "pink_shulker_box")
        h.put("hotbar.8", twin)
        h.ctx.waitTicks(60)
        h.markInHand(8)
        val marked = h.records().single { it.happy && it.hash.isNotEmpty() && it.state == "inv" && it.coords == "hotbar:9" }
        // The twin arrives while the chest is closed: no record, no stamp.
        h.cmd("item replace block ${a.x} ${a.y} ${a.z} container.19 with $twin")
        h.openAt(a)
        val before = h.logLines().size
        h.quickMoveAll(listOf(h.menuSlotOfInventory(8)))
        h.quickMoveAll(listOf(h.menuSlotOfContainer(19)))
        h.ctx.waitTicks(100)
        val strips = h.logLines().drop(before).count { it.contains("duplicate stamp stripped") }
        val out = (0..35).firstOrNull { h.itemIdAt(it) == "minecraft:pink_shulker_box" }
        val outStamp = out?.let { h.stampAt(it) }
        h.note("twin now at inv index $out wearing ${outStamp?.take(8)}; strips=$strips; markers=${h.markedMenuSlots()}")
        h.check(outStamp != marked.uuid, "the twin that came out wears the marked identity")
        h.check(strips == 0, "$strips duplicate-stamp strips")
        h.closeScreen()
        h.ctx.waitTicks(40)
        val mine = h.records().filter { it.hash == marked.hash }
        h.note("records: " + mine.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}#${it.slotIndex} happy=${it.happy}" })
        val m = mine.single { it.uuid == marked.uuid }
        h.check(m.happy && m.state == "ex-inv" && m.coords == "${a.x},${a.y},${a.z}", "the marked record is not in the chest: $m")
        h.check(mine.count { it.happy } == 1, "marks: ${mine.count { it.happy }}")
    }

    // Found by WtfFuzz: a box put in the 2x2 crafting grid comes back to the
    // inventory when the screen closes - a server move, no click - and the
    // player's own menu ledger kept its record out of the scan's reach.
    private fun gridRoundTrip(h: Harness) = h.scenario("2x2 grid: box handed back on close") {
        h.area()
        h.put("inventory.1", h.box("Grid", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.ctx.getInput().pressKey { it.keyInventory }
        h.ctx.waitTicks(10)
        val slot = h.menuSlotOfInventory(10)
        h.hover(slot)
        h.pressToggle()
        val before = h.one("Grid")
        h.check(before.happy, "setup: not marked")
        h.carry(slot, 2)
        h.closeScreen()
        h.ctx.waitTicks(80)
        val rs = h.named("Grid")
        h.note("records: " + rs.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords} happy=${it.happy}" })
        h.check(rs.size == 1, "one box, ${rs.size} records")
        h.check(rs.singleOrNull()?.let { it.uuid == before.uuid && it.happy && it.state == "inv" } == true, "the marked record did not come back with the box")
    }

    // Found by WtfFuzz (seed 3): placing a box took its record's content hash
    // from the placed block, whose contents the server had not sent yet - an
    // empty box's hash. Nothing matched the record by content afterwards.
    private fun placeAndBreakAMarkedTwin(h: Harness) = h.scenario("place a marked twin, break it, pick it up") {
        val a = h.area()
        val fill = h.uniqueFill()
        val twin = h.box(null, fill, color = "blue_shulker_box")
        h.put("hotbar.0", twin)
        h.put("hotbar.1", twin)
        h.ctx.waitTicks(60)
        h.markInHand(0)
        val marked = h.records().single { it.happy && it.state == "inv" && it.coords == "hotbar:1" }
        h.placeFromHotbar(0, a.below())
        h.ctx.waitTicks(20)
        val placed = h.records().single { it.uuid == marked.uuid }
        h.note("placed: ${placed.state}/${placed.coords} hash ${placed.hash.take(8)} (was ${marked.hash.take(8)})")
        h.check(placed.state == "block" && placed.hash == marked.hash, "the placed record's content hash changed: ${placed.hash.take(8)} vs ${marked.hash.take(8)}")
        // Wherever the box actually went - the crosshair-aimed placement is not exact.
        val at = placed.coords.split(',').map { it.toInt() }.let { BlockPos(it[0], it[1], it[2]) }
        h.cmd("setblock ${at.x} ${at.y} ${at.z} minecraft:air destroy")
        h.ctx.waitTicks(20)
        fun groundItems() = h.server.computeOnServer<List<String>, RuntimeException> { srv ->
            val lvl = srv.playerList.getPlayerByName("Player0")!!.level()
            lvl.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, net.minecraft.world.phys.AABB(at).inflate(3.0))
                .map { "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item.item).path}@${it.blockPosition().toShortString()}" }
        }
        h.note("on the ground after the break: ${groundItems()}")
        val drop = h.server.computeOnServer<net.minecraft.world.phys.Vec3?, RuntimeException> { srv ->
            srv.playerList.getPlayerByName("Player0")!!.level()
                .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java, net.minecraft.world.phys.AABB(at).inflate(3.0))
                .firstOrNull()?.position()
        }
        if (drop != null) h.cmd("tp Player0 ${drop.x} ${drop.y} ${drop.z}")
        h.ctx.waitTicks(60)
        h.note("on the ground after walking over: ${groundItems()}; hotbar: ${(0..8).map { h.itemIdAt(it)?.substringAfter(':') }}")
        val rs = h.records().filter { it.hash == marked.hash || it.uuid == marked.uuid }
        h.note("after pick-up: " + rs.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords} happy=${it.happy}" })
        h.check(rs.size == 2, "two twins, ${rs.size} records")
        h.check(rs.count { it.happy } == 1, "marks: ${rs.count { it.happy }}")
        h.check(rs.all { it.state == "inv" }, "not both back in the inventory: $rs")
    }

    // Found by WtfFuzz (seed 15): a box shift-clicked into a hopper and straight
    // back out. The hopper had already pushed it on, so the predicted move out
    // never happened on the server - and the record kept claiming the slot.
    private fun hopperSnatchRace(h: Harness) = h.scenario("hopper: snatch a box back that already moved on") {
        val a = h.area()
        val below = a.south()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:hopper[facing=south]")
        h.cmd("setblock ${below.x} ${below.y} ${below.z} minecraft:chest")
        for (gap in listOf(0, 1, 2, 3, 4, 6, 8, 10)) {
            val name = "Snatch$gap"
            h.put("hotbar.4", h.box(name, h.uniqueFill()))
            h.ctx.waitTicks(60)
            h.markInHand(4)
            h.openAt(a)
            h.quickMoveAll(listOf(h.menuSlotOfInventory(4)).also { h.ctx.waitTicks(0) })
            if (gap > 0) h.ctx.waitTicks(gap)
            h.ctx.runOnClient<RuntimeException> { mc ->
                val p = mc.player!!
                val s0 = p.containerMenu.slots.first { it.container !== p.inventory && it.containerSlot == 0 }.index
                mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, s0, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p)
            }
            h.closeScreen()
            h.ctx.waitTicks(80)
            val serverInv = h.server.computeOnServer<Int, RuntimeException> { srv ->
                val p = srv.playerList.getPlayerByName("Player0")!!
                (0..35).count { !p.inventory.getItem(it).isEmpty }
            }
            val r = h.one(name)
            h.note("gap $gap: record ${r.state}/${r.coords}; boxes in the server inventory: $serverInv")
            h.check(!(r.state == "inv" && serverInv == 0), "gap $gap: the record claims ${r.coords} but the inventory is empty")
            // Start the next round clean.
            h.cmd("clear Player0")
            h.cmd("item replace block ${below.x} ${below.y} ${below.z} container.0 with minecraft:air")
            h.ctx.waitTicks(30)
        }
    }

    // Found by WtfFuzz (seed 12): two single chests side by side were one
    // chest to the mod - canonicalChestPos paired any neighbouring chest block.
    // A storage wall of double chests has the same shape: a half next to the
    // NEIGHBOURING double's half.
    private fun adjacentChests(h: Harness) = h.scenario("adjacent chests keep their own identity") {
        val a = h.area()
        // Two singles, side by side, same facing.
        val s1 = a.north(2)
        val s2 = s1.east()
        h.cmd("setblock ${s1.x} ${s1.y} ${s1.z} minecraft:chest[facing=south,type=single]")
        h.cmd("setblock ${s2.x} ${s2.y} ${s2.z} minecraft:chest[facing=south,type=single]")
        // Two doubles in a row: [d1 d1'][d2 d2'].
        val d1 = a.south(2).west()
        h.cmd("setblock ${d1.x} ${d1.y} ${d1.z} minecraft:chest[facing=north,type=left]")
        h.cmd("setblock ${d1.x + 1} ${d1.y} ${d1.z} minecraft:chest[facing=north,type=right]")
        h.cmd("setblock ${d1.x + 2} ${d1.y} ${d1.z} minecraft:chest[facing=north,type=left]")
        h.cmd("setblock ${d1.x + 3} ${d1.y} ${d1.z} minecraft:chest[facing=north,type=right]")
        h.put("hotbar.0", h.box("InS2", h.uniqueFill()))
        h.put("hotbar.1", h.box("InD1", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.markInHand(1)
        h.openExact(s2)
        h.quickMoveAll(listOf(h.menuSlotOfInventory(0)))
        h.closeScreen()
        h.ctx.waitTicks(40)
        val r1 = h.one("InS2")
        h.note("InS2 recorded at ${r1.coords} (it is in ${s2.x},${s2.y},${s2.z})")
        h.check(r1.coords == "${s2.x},${s2.y},${s2.z}", "the box in the second single chest is recorded at ${r1.coords}")
        // Into the first double through its right-hand half...
        val d1r = d1.east()
        h.openExact(d1r)
        h.quickMoveAll(listOf(h.menuSlotOfInventory(1)))
        h.closeScreen()
        h.ctx.waitTicks(40)
        val r2 = h.one("InD1")
        h.note("InD1 recorded at ${r2.coords}")
        val d1Keys = setOf("${d1.x},${d1.y},${d1.z}", "${d1r.x},${d1r.y},${d1r.z}")
        h.check(r2.coords in d1Keys, "the box in the first double chest is recorded at ${r2.coords}, outside it")
        // ...and open it again through the left half: same chest, same identity.
        h.openExact(d1)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val r3 = h.one("InD1")
        h.check(r3.coords == r2.coords && r3.state == "ex-inv", "reopening through the other half moved the record: ${r2.coords} -> ${r3.state}/${r3.coords}")
    }

    // Control for the fuzzer's "vanilla desync" label: the same race with stone,
    // which the mod never stamps or tracks. Shift a stack into a hopper, snatch
    // it straight back, close in the same tick. Notes only - it measures vanilla.
    private fun vanillaGhostControl(h: Harness) = h.scenario("control: vanilla ghost after a refused click and a fast close") {
        val a = h.area()
        val below = a.south()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:hopper[facing=south]")
        h.cmd("setblock ${below.x} ${below.y} ${below.z} minecraft:chest")
        var ghosts = 0
        val tries = 12
        repeat(tries) { i ->
            h.cmd("clear Player0")
            h.put("hotbar.4", "minecraft:stone")
            h.ctx.waitTicks(10)
            h.openExact(a)
            h.ctx.runOnClient<RuntimeException> { mc ->
                val p = mc.player!!
                val m = p.containerMenu
                val mine = m.slots.first { it.container === p.inventory && it.containerSlot == 4 }.index
                mc.gameMode!!.handleContainerInput(m.containerId, mine, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p)
            }
            h.ctx.waitTicks(i % 4)
            h.ctx.runOnClient<RuntimeException> { mc ->
                val p = mc.player!!
                val m = p.containerMenu
                val s0 = m.slots.first { it.container !== p.inventory && it.containerSlot == 0 }.index
                mc.gameMode!!.handleContainerInput(m.containerId, s0, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p)
                p.closeContainer()
            }
            h.ctx.waitTicks(40)
            val client = (0..35).count { h.itemIdAt(it) == "minecraft:stone" }
            val server = h.server.computeOnServer<Int, RuntimeException> { srv ->
                val p = srv.playerList.getPlayerByName("Player0")!!
                (0..35).count { p.inventory.getItem(it).item == net.minecraft.world.item.Items.STONE }
            }
            if (client != server) ghosts++
            h.cmd("item replace block ${below.x} ${below.y} ${below.z} container.0 with minecraft:air")
        }
        h.note("stone: $ghosts of $tries fast click-and-close rounds left the client and server disagreeing")
    }

    // A marked box leaves by a path no click sees and a different box lands in
    // the same slot within cleanup's one-second grace. Found by accident: the
    // scenario after a marked one came up with its fresh box already marked.
    private fun slotTakenByADifferentBox(h: Harness) = h.scenario("pass 0: a different box lands in a remembered slot") {
        h.area()
        h.put("hotbar.0", h.box("Leaver", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        val leaver = h.one("Leaver")
        h.cmd("clear Player0")
        h.put("hotbar.0", h.box("Arrival", h.uniqueFill()))
        h.ctx.waitTicks(80)
        val arrival = h.named("Arrival")
        val leaverNow = h.named("Leaver")
        h.note("Leaver: $leaverNow")
        h.note("Arrival: $arrival")
        h.check(leaverNow.size == 1 && leaverNow[0].uuid == leaver.uuid && leaverNow[0].state == "ex-inv",
            "the leaver's record should be ex-inv, is $leaverNow")
        h.check(arrival.size == 1 && arrival[0].uuid != leaver.uuid && !arrival[0].happy,
            "the arrival took the leaver's identity or mark: $arrival")
        h.check(h.stampAt(0) != leaver.uuid, "the arrival wears the leaver's stamp")
    }

    // Two clicks inside one round trip - pick up, put down - which is what
    // itemscroller does, and what anyone clicking fast on a laggy server does.
    // A stack the mod never stamps is the control.
    private fun fastClicksLeaveNoGhost(h: Harness) = h.scenario("stamp: two clicks inside one round trip leave no ghost") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        h.put("hotbar.0", "minecraft:stone 5")
        h.put("hotbar.1", h.box("Fast", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(1)
        h.openAt(a)
        val watch = listOf(0, 1, h.menuSlotOfInventory(0), h.menuSlotOfInventory(1))
        h.carry(h.menuSlotOfInventory(0), 0)
        h.ctx.waitTicks(10)
        h.note("stone: " + h.menuDump(watch))
        h.check(h.menuDump(listOf()).startsWith("cursor=- "), "stone left a ghost on the cursor: ${h.menuDump(watch)}")
        h.carry(h.menuSlotOfInventory(1), 1)
        h.ctx.waitTicks(10)
        h.note("box:   " + h.menuDump(watch))
        h.check(h.menuDump(listOf()).startsWith("cursor=- "), "the box left a ghost on the cursor: ${h.menuDump(watch)}")
        h.closeScreen()
        h.ctx.waitTicks(40)
        val r = h.one("Fast")
        h.check(r.state == "ex-inv" && r.slotIndex == 1, "Fast record: ${r.state}/${r.coords}#${r.slotIndex}")
    }

    // Recolouring a box in a crafting table: the grid eats the box and the
    // server builds a new one, of a different item type. Same contents, same
    // name, same physical box as far as the player is concerned.
    private fun dyeAMarkedBox(h: Harness) = h.scenario("009: dye a marked box in a crafting table") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:crafting_table")
        h.put("hotbar.0", h.box("Dye", h.uniqueFill()))
        h.put("hotbar.5", "minecraft:blue_dye")
        h.ctx.waitTicks(60)
        h.markInHand(0)
        val before = h.one("Dye")
        h.openAt(a)
        val watch = listOf(0, 1, 2, h.menuSlotOfInventory(0), h.menuSlotOfInventory(5))
        h.carry(h.menuSlotOfInventory(0), 1)
        h.note("after box into grid: " + h.menuDump(watch))
        h.carry(h.menuSlotOfInventory(5), 2)
        h.ctx.waitTicks(10)
        h.note("after dye into grid: " + h.menuDump(watch))
        h.quickMoveAll(listOf(0))
        h.note("after taking the result: " + h.menuDump(watch))
        h.closeScreen()
        h.ctx.waitTicks(80)
        val after = h.named("Dye")
        h.note("before: ${before.uuid.take(8)} ${before.type}; after: " + after.joinToString { "${it.uuid.take(8)}=${it.type} ${it.state}/${it.coords} happy=${it.happy}" })
        val bluePresent = (0..35).any { h.itemNameAt(it) == "Dye" && h.itemIdAt(it) == "minecraft:blue_shulker_box" }
        h.check(bluePresent, "the crafted box is not in the inventory - the scenario did not run")
        h.check(after.size == 1, "one box, ${after.size} records")
        h.check(after.any { it.uuid == before.uuid && it.happy && it.state == "inv" && it.type == "minecraft:blue_shulker_box" },
            "the marked record did not follow the box through the dye")
    }

    // Ticket 009's volume question, and 010's counting rule at full size: 27
    // identical boxes through a chest and back, each direction in ONE tick, as
    // itemscroller's move-everything does it. Two menu snapshots per click.
    private fun bulkMove(h: Harness) = h.scenario("009/010: 27 identical boxes, one tick each way") {
        val a = h.area()
        h.cmd("setblock ${a.x} ${a.y} ${a.z} minecraft:chest")
        val fill = h.uniqueFill()
        for (i in 0 until 27) h.put("inventory.$i", h.box("Bulk", fill))
        h.ctx.waitTicks(80)
        val before = h.named("Bulk")
        h.check(before.size == 27, "setup: ${before.size} records for 27 boxes")
        h.openAt(a)
        val slots = (9 until 36).map { h.menuSlotOfInventory(it) }
        val ms = h.ctx.computeOnClient<Double, RuntimeException> { mc ->
            val p = mc.player!!
            val t = System.nanoTime()
            for (s in slots) mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, s, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p)
            (System.nanoTime() - t) / 1e6
        }
        h.note("27 shift-clicks in one tick: %.1f ms total, %.2f ms per click".format(ms, ms / 27))
        h.ctx.waitTicks(10)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val inChest = h.named("Bulk")
        h.check(inChest.size == 27 && inChest.all { it.state == "ex-inv" }, "in chest: ${inChest.groupingBy { it.state }.eachCount()} of ${inChest.size}")
        h.check(inChest.map { it.slotIndex }.toSet().size == 27, "chest slots not distinct: ${inChest.map { it.slotIndex }.sorted()}")
        h.openAt(a)
        h.quickMoveAll((0 until 27).map { h.menuSlotOfContainer(it) })
        h.closeScreen()
        h.ctx.waitTicks(80)
        val out = h.named("Bulk")
        h.check(out.size == 27, "27 boxes, ${out.size} records")
        h.check(out.all { it.state == "inv" }, "not all back: ${out.groupingBy { it.state }.eachCount()}")
        h.check(out.map { it.coords }.toSet().size == 27, "records share slots")
        h.check(out.map { it.uuid }.toSet() == before.map { it.uuid }.toSet(), "identities were minted or lost")
    }

    // tweakeroo's hand restock (he has it OFF, so this is a courtesy check):
    // place the last box in hand and tweakeroo swaps an identical twin in from
    // the inventory with a menu click, no screen open.
    private fun handRestock(h: Harness) = h.scenario("009: tweakeroo hand restock after placing a box") {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("tweakeroo")) {
            h.note("tweakeroo not loaded - skipped")
            return@scenario
        }
        h.ctx.runOnClient<RuntimeException> { _ ->
            val toggle = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle").getField("TWEAK_HAND_RESTOCK").get(null)
            toggle.javaClass.getMethod("setBooleanValue", Boolean::class.javaPrimitiveType).invoke(toggle, true)
        }
        val a = h.area()
        val fill = h.uniqueFill()
        h.put("hotbar.0", h.box("Stock", fill))
        h.put("hotbar.1", h.box("Stock", fill))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.markInHand(1)
        // Park the second twin in the main inventory through the GUI, so the
        // records know where it is before the gesture under test.
        h.ctx.getInput().pressKey { it.keyInventory }
        h.ctx.waitTicks(10)
        h.ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            val inv9 = p.containerMenu.slots.first { it.container === p.inventory && it.containerSlot == 9 }.index
            mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, inv9, 1, net.minecraft.world.inventory.ContainerInput.SWAP, p)
        }
        h.ctx.waitTicks(10)
        h.closeScreen()
        h.ctx.waitTicks(40)
        val setup = h.named("Stock").associate { it.coords to it.uuid }
        h.check(setup.keys == setOf("hotbar:1", "inv:1"), "setup: $setup")
        val placedUUID = setup["hotbar:1"]
        val restockUUID = setup["inv:1"]

        h.placeFromHotbar(0, a.below())
        h.ctx.waitTicks(80)
        val after = h.named("Stock")
        h.note("after place+restock: " + after.joinToString { "${it.uuid.take(8)}=${it.state}/${it.coords}" })
        if (h.itemNameAt(0) != "Stock") {
            // Its restock runs from its own hooks around a right-click use, and
            // the harness's key press does not reach them. He has the tweak off.
            h.note("UNTESTED: tweakeroo did not restock the hand in the harness")
            return@scenario
        }
        h.check(after.size == 2, "two boxes, ${after.size} records")
        h.check(after.any { it.uuid == placedUUID && it.state == "block" }, "the placed box's record is not the placed one")
        h.check(after.any { it.uuid == restockUUID && it.state == "inv" && it.coords == "hotbar:1" }, "the restocked twin's record did not follow it into the hand")
        h.check(after.all { it.happy }, "a mark was lost")
    }

    // Hover a marked box in the inventory screen with every tooltip mod loaded
    // (peek draws its own shulker preview). A crash or mixin clash fails here.
    private fun tooltipHover(h: Harness) = h.scenario("009: hover a marked box with tooltip mods loaded") {
        h.area()
        h.put("hotbar.0", h.box("Peek", h.uniqueFill()))
        h.ctx.waitTicks(60)
        h.markInHand(0)
        h.ctx.getInput().pressKey { it.keyInventory }
        h.ctx.waitTicks(10)
        h.hover(h.menuSlotOfInventory(0))
        h.ctx.waitTicks(20)
        val shot = h.ctx.takeScreenshot("hover-marked-box")
        h.note("screenshot: ${shot.fileName}")
        h.closeScreen()
        h.check(h.one("Peek").happy, "mark lost while hovering")
    }

    private fun Rec.contentHash() = hash

    @Suppress("unused")
    private fun onServer(h: Harness, f: (MinecraftServer) -> Unit) = h.server.runOnServer<RuntimeException> { f(it) }
}
