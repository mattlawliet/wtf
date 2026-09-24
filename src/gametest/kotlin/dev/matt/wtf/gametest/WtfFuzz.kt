package dev.matt.wtf.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.vehicle.minecart.MinecartChest
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.ShulkerBoxBlock
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3
import java.io.File
import java.util.Properties
import kotlin.random.Random

// Random walks of boxes through every place a box may legally go, checked
// against the server's own view of the world after every step.
//
//   WTF_FUZZ=1,2,3 WTF_FUZZ_STEPS=120 xvfb-run -a ./gradlew runClientGameTest
//
// Results: build/run/clientGameTest/wtf-fuzz.txt. A seed stops at its first
// hard failure; rerunning the same seed replays the same walk.
class WtfFuzz : FabricClientGameTest {
    override fun runTest(ctx: ClientGameTestContext) {
        val seeds = System.getenv("WTF_FUZZ")?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return
        val steps = System.getenv("WTF_FUZZ_STEPS")?.toIntOrNull() ?: 80
        val h = Harness(ctx)
        val report = StringBuilder()
        val props = Properties().apply {
            setProperty("level-type", "minecraft:flat")
            setProperty("difficulty", "peaceful")
            setProperty("gamemode", "survival")
            setProperty("spawn-protection", "0")
            setProperty("generate-structures", "false")
        }
        var failed = 0
        // Silent: the window is virtual, the speakers are not.
        ctx.runOnClient<RuntimeException> { mc ->
            mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0)
        }
        ctx.worldBuilder().createServer(props).use { server ->
            h.server = server
            var conn = server.connect()
            fun joined() {
                conn.clientLevel.waitForChunksRender()
                ctx.waitTicks(120)
            }
            val relog = {
                conn.close()
                conn = server.connect()
                joined()
            }
            try {
                joined()
                seeds.forEachIndexed { i, seed ->
                    val f = Fuzzer(h, seed, steps, i, relog, seeds.size)
                    if (!f.run()) failed++
                    report.append(f.report)
                    File(h.gameDir, "wtf-fuzz.txt").writeText("WTF fuzz - $failed of ${i + 1} seeds failed\n$report")
                }
            } finally {
                conn.close()
            }
        }
        val text = "WTF fuzz - $failed of ${seeds.size} seeds failed\n$report"
        File(h.gameDir, "wtf-fuzz.txt").writeText(text)
        println(text)
        if (failed > 0) throw ScenarioFailed("$failed fuzz seed(s) failed - see wtf-fuzz.txt")
    }
}

// Where the server says a box is. kind: inv (detail = the mod's slot key),
// ender, cont (a block container), block (placed), item (on the ground),
// frame, pot, cart, grid (2x2 crafting), cursor.
data class Spot(val kind: String, val detail: String, val pos: String = "", val partner: String = "", val invIndex: Int = -1) {
    override fun toString() = if (pos.isNotEmpty()) "$kind@$pos${if (detail.isNotEmpty()) "#$detail" else ""}" else "$kind:$detail"
}

class TBox(val stack: ItemStack, val spot: Spot, var fp: String = "")

private class Member(val label: String, val fp: String, val twins: Int)

private class Fuzzer(
    val h: Harness,
    val seed: Long,
    val steps: Int,
    val idx: Int,
    val relog: () -> Unit,
    val seedCount: Int = 1,
) {
    val report = StringBuilder()
    private val rnd = Random(seed)
    private val ctx = h.ctx
    private val base = BlockPos(2000 + idx * 48, -60, 2000)
    private fun at(dx: Int, dz: Int, dy: Int = 0): BlockPos = base.offset(dx, dy, dz)
    private fun key(p: BlockPos) = "${p.x},${p.y},${p.z}"

    // The ring. Everything sits on the perimeter so nothing hides anything.
    private val chestA = at(3, 0)
    private val dblLeft = at(0, -3)
    private val dblRight = at(-1, -3)
    private val barrel = at(-2, -3)
    private val trapped = at(1, -3)
    private val ender = at(2, -3)
    private val furnace = at(-2, 3)
    private val dispenser = at(0, 3)
    private val dispFront = at(-1, 3)
    private val dropper = at(1, 3)
    private val craftTable = at(2, 3)
    private val hopper = at(3, -2)
    private val hopperIn = at(3, -2, 1)
    private val hopperOut = at(3, -1)
    private val pot = at(3, 1)
    private val anvil = at(3, 2)
    private val placeCells = listOf(at(-3, -2), at(-3, 0))
    private val cartCell = at(-3, -1)
    private val frameCell = at(-3, 2)

    private val containers = listOf(chestA, dblLeft, barrel, trapped, ender, furnace, dispenser, dropper, hopperIn, hopper, hopperOut)

    private val members = mutableListOf<Member>()
    private val distinctMarked = mutableMapOf<String, Boolean>()
    private val twinMarked = mutableMapOf<String, Int>()
    private val observed = mutableMapOf<String, Boolean>()
    private val history = ArrayDeque<String>()
    private val soft = mutableMapOf<String, Int>()
    private var lastTruth: List<TBox> = emptyList()
    private var movingSteps = 0
    private val kinds = mutableMapOf<String, Int>()
    private val trace = StringBuilder()

    // --- world ---------------------------------------------------------------

    private fun cmd(c: String) = h.cmd(c)

    private fun boxItem(color: String, name: String?, item: String, count: Int): String {
        val comps = mutableListOf<String>()
        if (name != null) comps += "minecraft:custom_name=\"$name\""
        comps += "minecraft:container=[{slot:0,item:{id:\"minecraft:$item\",count:$count}}]"
        return "minecraft:$color[${comps.joinToString(",")}]"
    }

    private fun build() {
        // Arrive first: the area is far from spawn, and a command into an
        // unloaded chunk does nothing at all.
        home()
        ctx.waitTicks(40)
        cmd("fill ${base.x - 5} -60 ${base.z - 5} ${base.x + 5} -56 ${base.z + 5} minecraft:air")
        cmd("kill @e[type=!minecraft:player,x=${base.x - 6},y=-64,z=${base.z - 6},dx=12,dy=10,dz=12]")
        fun set(p: BlockPos, b: String) = cmd("setblock ${p.x} ${p.y} ${p.z} $b")
        set(chestA, "minecraft:chest[facing=west]")
        set(dblLeft, "minecraft:chest[facing=south,type=left]")
        set(dblRight, "minecraft:chest[facing=south,type=right]")
        set(barrel, "minecraft:barrel[facing=south]")
        set(trapped, "minecraft:trapped_chest[facing=south]")
        set(ender, "minecraft:ender_chest[facing=south]")
        set(furnace, "minecraft:furnace[facing=north]")
        set(dispenser, "minecraft:dispenser[facing=west]")
        set(dropper, "minecraft:dropper[facing=up]")
        set(craftTable, "minecraft:crafting_table")
        set(hopper, "minecraft:hopper[facing=south]")
        set(hopperIn, "minecraft:chest[facing=west]")
        set(hopperOut, "minecraft:chest[facing=west]")
        set(pot, "minecraft:decorated_pot")
        set(anvil, "minecraft:anvil[facing=north]")
        set(frameCell.west(), "minecraft:stone")
        set(frameCell.west().above(), "minecraft:stone")
        cmd("summon minecraft:item_frame ${frameCell.x} ${frameCell.y} ${frameCell.z} {Facing:5b}")
        cmd("summon minecraft:chest_minecart ${cartCell.x}.5 ${cartCell.y} ${cartCell.z}.5")
        home()
        cmd("clear Player0")
        h.server.runOnServer<RuntimeException> { s -> s.playerList.getPlayerByName("Player0")!!.enderChestInventory.clearContent() }
        ctx.waitTicks(20)
    }

    private fun home() {
        cmd("tp Player0 ${base.x}.5 ${base.y} ${base.z}.5 0 20")
        ctx.waitTicks(5)
    }

    // --- truth -----------------------------------------------------------------

    private fun invKey(i: Int) = when (i) {
        in 0..8 -> "hotbar:${i + 1}"
        in 9..35 -> "inv:${i - 8}"
        40 -> "offhand"
        else -> "slot:$i"
    }

    private fun isBox(st: ItemStack) = !st.isEmpty && (st.item as? BlockItem)?.block is ShulkerBoxBlock

    private fun truth(): List<TBox> {
        val boxes = h.server.computeOnServer<List<TBox>, RuntimeException> { s ->
            val out = mutableListOf<TBox>()
            val p = s.playerList.getPlayerByName("Player0")!!
            val lvl = p.level() as ServerLevel
            for (i in 0 until p.inventory.containerSize) {
                val st = p.inventory.getItem(i)
                if (isBox(st)) out += TBox(st.copy(), Spot("inv", invKey(i), invIndex = i))
            }
            if (isBox(p.containerMenu.carried)) out += TBox(p.containerMenu.carried.copy(), Spot("cursor", ""))
            for (i in 1..4) {
                val st = p.inventoryMenu.getSlot(i).item
                if (isBox(st)) out += TBox(st.copy(), Spot("grid", "$i"))
            }
            val ec = p.enderChestInventory
            for (i in 0 until ec.containerSize) if (isBox(ec.getItem(i))) out += TBox(ec.getItem(i).copy(), Spot("ender", "$i"))
            for (x in base.x - 5..base.x + 5) for (y in -61..-57) for (z in base.z - 5..base.z + 5) {
                val pos = BlockPos(x, y, z)
                val be = lvl.getBlockEntity(pos) ?: continue
                when (be) {
                    is ShulkerBoxBlockEntity -> {
                        val st = ItemStack(lvl.getBlockState(pos).block)
                        st.applyComponents(be.collectComponents())
                        out += TBox(st, Spot("block", "", pos = key(pos)))
                    }
                    is DecoratedPotBlockEntity -> if (isBox(be.getTheItem())) out += TBox(be.getTheItem().copy(), Spot("pot", "", pos = key(pos)))
                    is Container -> {
                        val state = lvl.getBlockState(pos)
                        val partner = if (state.block is ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
                            key(pos.relative(ChestBlock.getConnectedDirection(state))) else ""
                        for (i in 0 until be.containerSize) {
                            if (isBox(be.getItem(i))) out += TBox(be.getItem(i).copy(), Spot("cont", "$i", pos = key(pos), partner = partner))
                        }
                    }
                }
            }
            val area = AABB(base.x - 6.0, -64.0, base.z - 6.0, base.x + 7.0, -50.0, base.z + 7.0)
            for (e in lvl.getEntitiesOfClass(ItemEntity::class.java, area)) if (isBox(e.item)) out += TBox(e.item.copy(), Spot("item", "${e.id}"))
            for (e in lvl.getEntitiesOfClass(ItemFrame::class.java, area)) if (isBox(e.item)) out += TBox(e.item.copy(), Spot("frame", ""))
            for (e in lvl.getEntitiesOfClass(MinecartChest::class.java, area)) {
                for (i in 0 until e.containerSize) if (isBox(e.getItem(i))) out += TBox(e.getItem(i).copy(), Spot("cart", "$i"))
            }
            out
        }
        val fps = fingerprints(boxes.map { it.stack })
        boxes.forEachIndexed { i, b -> b.fp = fps[i] }
        return boxes
    }

    // The mod's own content fingerprint, so truth and records speak one language.
    private fun fingerprints(stacks: List<ItemStack>): List<String> = ctx.computeOnClient<List<String>, RuntimeException> { _ ->
        val cls = Class.forName("dev.matt.wtf.client.WTFClient")
        val inst = cls.getField("INSTANCE").get(null)
        val m = cls.getDeclaredMethod("fingerprintFromItem", ItemStack::class.java).apply { isAccessible = true }
        stacks.map { (m.invoke(inst, it) as String?) ?: "" }
    }

    private fun markedPlayerSlots(): Set<Int> = ctx.computeOnClient<Set<Int>, RuntimeException> { _ ->
        val f = Class.forName("dev.matt.wtf.client.WTFClient").getDeclaredField("markedPlayerSlots")
        f.isAccessible = true
        (f.get(null) as Set<*>).map { it as Int }.toSet()
    }

    // --- client actions ----------------------------------------------------------

    private fun screenOpen() = ctx.computeOnClient<Boolean, RuntimeException> { it.gui.screen() is AbstractContainerScreen<*> }

    private fun faceToward(pos: BlockPos): Direction {
        val d = Vec3(base.x + 0.5 - (pos.x + 0.5), 0.0, base.z + 0.5 - (pos.z + 0.5))
        return Direction.getApproximateNearest(d.x, d.y, d.z)
    }

    // A right-click on the block face toward the player - exactly what the use
    // key sends, without depending on the crosshair.
    private fun useOn(pos: BlockPos, face: Direction = faceToward(pos)) {
        val hit = Vec3.atCenterOf(pos).add(face.stepX * 0.5, face.stepY * 0.5, face.stepZ * 0.5)
        ctx.runOnClient<RuntimeException> { mc ->
            mc.gameMode!!.useItemOn(mc.player!!, InteractionHand.MAIN_HAND, BlockHitResult(hit, face, pos, false))
        }
    }

    private fun openBlock(pos: BlockPos): Boolean {
        useOn(pos)
        return waitScreen()
    }

    private fun waitScreen(): Boolean {
        for (i in 0 until 40) {
            ctx.waitTick()
            if (screenOpen()) {
                ctx.waitTicks(2)
                return true
            }
        }
        return false
    }

    private fun <T : net.minecraft.world.entity.Entity> clientEntity(cls: Class<T>, cell: BlockPos): T? =
        ctx.computeOnClient<T?, RuntimeException> { mc ->
            mc.level!!.getEntitiesOfClass(cls, AABB(cell).inflate(0.6)).firstOrNull()
        }

    private fun interact(cls: Class<out net.minecraft.world.entity.Entity>, cell: BlockPos): Boolean {
        val e = clientEntity(cls, cell) ?: return false
        ctx.runOnClient<RuntimeException> { mc ->
            mc.gameMode!!.interact(mc.player!!, e, EntityHitResult(e), InteractionHand.MAIN_HAND)
        }
        return true
    }

    private fun closeScreen() {
        if (screenOpen()) h.closeScreen()
    }

    private data class MSlot(val index: Int, val mine: Boolean, val cslot: Int, val box: Boolean, val empty: Boolean)

    private fun menu(): List<MSlot> = ctx.computeOnClient<List<MSlot>, RuntimeException> { mc ->
        val p = mc.player!!
        p.containerMenu.slots.map { MSlot(it.index, it.container === p.inventory, it.containerSlot, isBox(it.item), it.item.isEmpty) }
    }

    private fun carrying(): Boolean = ctx.computeOnClient<Boolean, RuntimeException> { !it.player!!.containerMenu.carried.isEmpty }

    private fun click(slot: Int, button: Int, input: ContainerInput) {
        ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, slot, button, input, p)
        }
        // 0 means the next click lands inside the same tick, as itemscroller does.
        val gap = rnd.nextInt(4)
        if (gap > 0) ctx.waitTicks(gap)
    }

    // Put whatever is on the cursor somewhere legal before a screen closes.
    private fun settleCursor() {
        repeat(3) {
            if (!carrying()) return
            val empty = menu().filter { it.mine && it.empty && it.cslot in 0..35 }.randomOrNull(rnd) ?: return
            click(empty.index, 0, ContainerInput.PICKUP)
            ctx.waitTicks(2)
        }
    }

    // 1-4 random clicks inside whatever screen is open: into the container, out
    // of it, around inside it, and around the player's own slots.
    private fun clickSession(allowInventoryOps: Boolean): String {
        val ops = mutableListOf<String>()
        repeat(1 + rnd.nextInt(4)) {
            val m = menu()
            val theirs = m.filter { !it.mine }
            val mine = m.filter { it.mine && it.cslot in 0..40 }
            val myBoxes = mine.filter { it.box }
            val theirBoxes = theirs.filter { it.box }
            val feasible = buildList {
                if (myBoxes.isNotEmpty() && theirs.isNotEmpty()) add(0)
                if (myBoxes.isNotEmpty() && theirs.any { it.empty }) add(1)
                if (theirBoxes.isNotEmpty()) { add(2); add(3) }
                if (theirBoxes.isNotEmpty() && theirs.any { it.empty }) add(4)
                if (allowInventoryOps && myBoxes.isNotEmpty()) add(5)
            }
            when (feasible.randomOrNull(rnd)) {
                0 -> {
                    val b = myBoxes.random(rnd)
                    click(b.index, 0, ContainerInput.QUICK_MOVE); ops += "shift-in inv#${b.cslot}"
                }
                1 -> {
                    val b = myBoxes.random(rnd); val to = theirs.filter { it.empty }.random(rnd)
                    click(b.index, 0, ContainerInput.PICKUP); click(to.index, 0, ContainerInput.PICKUP)
                    ops += "carry inv#${b.cslot}->c#${to.cslot}"
                }
                2 -> {
                    val b = theirBoxes.random(rnd)
                    click(b.index, 0, ContainerInput.QUICK_MOVE); ops += "shift-out c#${b.cslot}"
                }
                3 -> {
                    val b = theirBoxes.random(rnd); val btn = if (rnd.nextInt(4) == 0) 40 else rnd.nextInt(9)
                    click(b.index, btn, ContainerInput.SWAP); ops += "swap c#${b.cslot}<->${if (btn == 40) "offhand" else "hotbar${btn + 1}"}"
                }
                4 -> {
                    val b = theirBoxes.random(rnd); val to = theirs.filter { it.empty }.random(rnd)
                    click(b.index, 0, ContainerInput.PICKUP); click(to.index, 0, ContainerInput.PICKUP)
                    ops += "shuffle c#${b.cslot}->c#${to.cslot}"
                }
                5 -> {
                    val b = myBoxes.random(rnd)
                    when (rnd.nextInt(3)) {
                        0 -> { val btn = if (rnd.nextInt(3) == 0) 40 else rnd.nextInt(9); click(b.index, btn, ContainerInput.SWAP); ops += "swap inv#${b.cslot}<->${if (btn == 40) "offhand" else "hotbar${btn + 1}"}" }
                        1 -> { val to = mine.filter { it.empty && it.cslot in 0..35 }.randomOrNull(rnd)
                            if (to != null) { click(b.index, 0, ContainerInput.PICKUP); click(to.index, 0, ContainerInput.PICKUP); ops += "carry inv#${b.cslot}->inv#${to.cslot}" } }
                        else -> { val other = myBoxes.filter { it != b }.randomOrNull(rnd)
                            if (other != null) { click(b.index, 0, ContainerInput.PICKUP); click(other.index, 0, ContainerInput.PICKUP); click(b.index, 0, ContainerInput.PICKUP); ops += "exchange inv#${b.cslot}<->inv#${other.cslot}" } }
                    }
                }
            }
        }
        settleCursor()
        return ops.joinToString(", ").ifEmpty { "no clicks" }
    }

    // --- actions -------------------------------------------------------------------

    private var toggledThisStep: String? = null

    private fun act(t: List<TBox>): Pair<String, BlockPos?> {
        val inv = t.filter { it.spot.kind == "inv" }
        val hotbar = inv.filter { it.spot.invIndex in 0..8 }
        val r = rnd.nextInt(100)
        return when {
            r < 30 -> {
                // Mostly where boxes are, so sessions have something to move;
                // sometimes anywhere, so empty containers get boxes put in.
                val holding = containers.filter { c ->
                    t.any { (it.spot.kind == "cont" && (it.spot.pos == key(c) || it.spot.partner == key(c))) || (it.spot.kind == "ender" && c == ender) }
                }
                val c = if (holding.isNotEmpty() && (inv.isEmpty() || rnd.nextBoolean())) holding.random(rnd) else containers.random(rnd)
                if (!openBlock(c)) return "open ${name(c)} failed" to null
                val ops = clickSession(allowInventoryOps = true)
                if (rnd.nextInt(5) == 0) toggleHovered()
                closeScreen()
                "session ${name(c)}: $ops" to c
            }
            r < 40 -> {
                ctx.getInput().pressKey { it.keyInventory }
                if (!waitScreen()) return "inventory did not open" to null
                val ops = mutableListOf<String>()
                // Sometimes via the 2x2 grid, which hands its items back on close.
                val m = menu()
                val b = m.filter { it.mine && it.box }.randomOrNull(rnd)
                if (b != null && rnd.nextInt(3) == 0) {
                    val grid = 1 + rnd.nextInt(4)
                    click(b.index, 0, ContainerInput.PICKUP); click(grid, 0, ContainerInput.PICKUP); ops += "inv#${b.cslot}->grid$grid"
                }
                ops += clickSession(allowInventoryOps = true)
                if (rnd.nextInt(5) == 0) toggleHovered()
                closeScreen()
                "inventory: ${ops.joinToString(", ")}" to null
            }
            r < 48 -> {
                val i = rnd.nextInt(9)
                h.select(i); h.pressF()
                "F on hotbar${i + 1}" to null
            }
            r < 56 && hotbar.isNotEmpty() -> {
                val b = hotbar.random(rnd)
                h.select(b.spot.invIndex); h.pressToggle()
                "toggle in hand hotbar${b.spot.invIndex + 1}" to null
            }
            r < 64 && hotbar.isNotEmpty() -> {
                val cell = placeCells.filter { c -> t.none { it.spot.kind == "block" && it.spot.pos == key(c) } && blockIsAir(c) }.randomOrNull(rnd)
                    ?: return "place: no free cell" to null
                val b = hotbar.random(rnd)
                h.select(b.spot.invIndex)
                useOn(cell.below(), Direction.UP)
                ctx.waitTicks(5)
                "place hotbar${b.spot.invIndex + 1} at ${key(cell)}" to null
            }
            r < 69 -> {
                val placed = t.filter { it.spot.kind == "block" }.randomOrNull(rnd) ?: return "open placed: none" to null
                val p = parse(placed.spot.pos)
                if (!openBlock(p)) return "open placed failed" to null
                if (rnd.nextInt(2) == 0) { lastToggled = placed.fp; h.pressToggle() }
                val ops = clickSession(allowInventoryOps = false)
                closeScreen()
                "placed box ${placed.spot.pos}: $ops" to p
            }
            r < 74 -> {
                val placed = t.filter { it.spot.kind == "block" }.randomOrNull(rnd) ?: return "break: none" to null
                cmd("setblock ${placed.spot.pos.replace(',', ' ')} minecraft:air destroy")
                ctx.waitTicks(5)
                "break placed ${placed.spot.pos}" to null
            }
            r < 79 && hotbar.isNotEmpty() -> {
                val b = hotbar.random(rnd)
                h.select(b.spot.invIndex); ctx.getInput().pressKey { it.keyDrop }; ctx.waitTicks(5)
                "drop hotbar${b.spot.invIndex + 1}" to null
            }
            r < 85 -> collect()
            r < 88 -> { pulse(dispenser); "fire dispenser" to null }
            r < 90 -> { pulse(dropper); "fire dropper" to null }
            r < 93 -> {
                val framed = t.any { it.spot.kind == "frame" }
                if (framed) {
                    val e = clientEntity(ItemFrame::class.java, frameCell) ?: return "frame missing" to null
                    ctx.runOnClient<RuntimeException> { mc -> mc.gameMode!!.attack(mc.player!!, e) }
                    ctx.waitTicks(5)
                    "pop the frame" to null
                } else {
                    val b = hotbar.randomOrNull(rnd) ?: return "frame: nothing in the hotbar" to null
                    h.select(b.spot.invIndex); interact(ItemFrame::class.java, frameCell); ctx.waitTicks(5)
                    "frame hotbar${b.spot.invIndex + 1}" to null
                }
            }
            r < 96 -> {
                val potted = t.any { it.spot.kind == "pot" }
                if (potted) {
                    cmd("setblock ${pot.x} ${pot.y} ${pot.z} minecraft:air destroy")
                    ctx.waitTicks(2)
                    cmd("setblock ${pot.x} ${pot.y} ${pot.z} minecraft:decorated_pot")
                    "break the pot" to null
                } else {
                    val b = hotbar.randomOrNull(rnd) ?: return "pot: nothing in the hotbar" to null
                    h.select(b.spot.invIndex); useOn(pot); ctx.waitTicks(5)
                    "pot hotbar${b.spot.invIndex + 1}" to null
                }
            }
            r < 98 -> {
                if (!interact(MinecartChest::class.java, cartCell) || !waitScreen()) return "cart did not open" to null
                val ops = clickSession(allowInventoryOps = false)
                closeScreen()
                "cart: $ops" to null
            }
            r < 99 -> {
                val bench = if (rnd.nextBoolean()) craftTable else anvil
                if (!openBlock(bench)) return "open ${name(bench)} failed" to null
                val m = menu()
                val b = m.filter { it.mine && it.box }.randomOrNull(rnd)
                val input = m.firstOrNull { !it.mine && it.empty && it.index in 0..2 && it.index != (if (bench == anvil) 2 else 0) }
                if (b != null && input != null) { click(b.index, 0, ContainerInput.PICKUP); click(input.index, 0, ContainerInput.PICKUP) }
                ctx.waitTicks(rnd.nextInt(10))
                closeScreen()
                "visit ${name(bench)}" to null
            }
            else -> {
                val before = markedSnapshot()
                relog()
                home()
                ctx.waitTicks(60)
                relogCheck = before
                "relog" to null
            }
        }
    }

    private var relogCheck: Map<String, String>? = null

    private fun markedSnapshot(): Map<String, String> {
        val fps = members.map { it.fp }.toSet()
        // A box on the ground comes back as a new entity after a relog, so only
        // the identity is held for "item"; everything else must come back as is.
        return h.records().filter { it.happy && it.hash in fps }
            .associate { it.uuid to if (it.state == "item") "item" else "${it.state}/${it.coords}" }
    }

    private fun toggleHovered() {
        val b = menu().filter { it.box }.randomOrNull(rnd) ?: return
        val stack = ctx.computeOnClient<ItemStack, RuntimeException> { it.player!!.containerMenu.slots[b.index].item.copy() }
        lastToggled = fingerprints(listOf(stack)).single()
        h.hover(b.index)
        h.pressToggle()
    }

    private fun blockIsAir(p: BlockPos) = h.server.computeOnServer<Boolean, RuntimeException> { s ->
        s.playerList.getPlayerByName("Player0")!!.level().getBlockState(p).isAir
    }

    private fun pulse(p: BlockPos) {
        cmd("setblock ${p.x} ${p.y - 1} ${p.z} minecraft:redstone_block")
        ctx.waitTicks(6)
        cmd("setblock ${p.x} ${p.y - 1} ${p.z} minecraft:grass_block")
        ctx.waitTicks(6)
    }

    // Walk onto every box lying on the ground, then back to the middle.
    private fun collect(): Pair<String, BlockPos?> {
        val items = truth().filter { it.spot.kind == "item" }
        if (items.isEmpty()) return "collect: nothing on the ground" to null
        ctx.waitTicks(40) // a thrown item cannot be picked up for 40 ticks
        for (it in items) {
            val id = it.spot.detail.toInt()
            val pos = h.server.computeOnServer<Vec3?, RuntimeException> { s ->
                (s.playerList.getPlayerByName("Player0")!!.level() as ServerLevel).getEntity(id)?.position()
            } ?: continue
            cmd("tp Player0 ${pos.x} ${pos.y} ${pos.z}")
            ctx.waitTicks(15)
        }
        home()
        return "collect ${items.size} from the ground" to null
    }

    private fun parse(k: String): BlockPos = k.split(',').map { it.toInt() }.let { BlockPos(it[0], it[1], it[2]) }

    private fun name(p: BlockPos) = when (p) {
        chestA -> "chest"; dblLeft -> "double chest"; barrel -> "barrel"; trapped -> "trapped chest"; ender -> "ender chest"
        furnace -> "furnace"; dispenser -> "dispenser"; dropper -> "dropper"; hopperIn -> "chest over hopper"
        hopper -> "hopper"; hopperOut -> "chest under hopper"; craftTable -> "crafting table"; anvil -> "anvil"
        else -> key(p)
    }

    // --- checks -----------------------------------------------------------------------

    private fun fightLines(lines: List<String>) = lines.count {
        it.contains("OVERRODE") || it.contains("duplicate stamp stripped") || it.startsWith("evict:")
    }

    private fun check(t: List<TBox>, action: String, actedOn: BlockPos?, logSince: List<String>): List<String> {
        val bad = mutableListOf<String>()
        val recs = h.records()
        val shown = markedPlayerSlots()

        // Toggles: read which way the mod says it went, and hold it to it.
        for (line in logSince) {
            val marked = line.contains(" marked: ")
            val unmarked = line.contains(" unmarked: ")
            if (!marked && !unmarked) continue
            val fp = toggledFp(t, line) ?: continue
            val mem = members.first { it.fp == fp }
            if (mem.twins == 1) {
                if (distinctMarked[fp] == marked) bad += "toggle on ${mem.label} said ${if (marked) "marked" else "unmarked"} but it already was"
                distinctMarked[fp] = marked
            } else {
                val n = twinMarked.getValue(fp) + if (marked) 1 else -1
                if (n < 0 || n > mem.twins) bad += "toggle on ${mem.label} took the mark count to $n"
                twinMarked[fp] = n.coerceIn(0, mem.twins)
            }
        }

        // What the client itself shows in each inventory slot. After a click the
        // server refused, landing just as the screen closed, vanilla drops the
        // correction and the client keeps a ghost - the mod can only follow what
        // the client shows. Slots where the two disagree are vanilla's, not ours.
        val clientBoxes = ctx.computeOnClient<Set<Int>, RuntimeException> { mc ->
            val inv = mc.player!!.inventory
            (0 until inv.containerSize).filter { isBox(inv.getItem(it)) }.toSet()
        }
        val serverBoxes = t.filter { it.spot.kind == "inv" }.map { it.spot.invIndex }.toSet()
        val desync = (clientBoxes - serverBoxes) + (serverBoxes - clientBoxes)
        if (desync.isNotEmpty()) {
            soft.merge("vanilla client/server desync in the inventory", 1, Int::plus)
            return bad // nothing about the inventory can be judged until vanilla resyncs
        }

        for (mem in members) {
            val here = t.filter { it.fp == mem.fp }
            val rs = recs.filter { it.hash == mem.fp }
            if (here.size != mem.twins) {
                bad += "${mem.label}: ${here.size} boxes in the world, expected ${mem.twins} (harness lost one?)"
                continue
            }
            if (mem.twins == 1) {
                val box = here.single()
                val intent = distinctMarked.getValue(mem.fp)
                if (rs.size > 1) bad += "${mem.label}: ${rs.size} records for one box: ${rs.joinToString { it.brief() }}"
                if (intent && rs.count { it.happy } != 1) bad += "${mem.label}: marked, but ${rs.count { it.happy }} marked records: ${rs.joinToString { it.brief() }}"
                if (!intent && rs.any { it.happy }) bad += "${mem.label}: not marked, but a record is: ${rs.joinToString { it.brief() }}"
                val r = rs.singleOrNull()
                // Moves the mod saw: the screen it happened in, or the inventory.
                val seen = when (box.spot.kind) {
                    "inv" -> true
                    "cont", "ender" -> actedOn != null && (box.spot.pos == key(actedOn) || box.spot.partner == key(actedOn) || (box.spot.kind == "ender" && actedOn == ender))
                    else -> false
                }
                if (seen) observed[mem.fp] = true
                else if (lastTruth.firstOrNull { it.fp == mem.fp }?.spot != box.spot) observed[mem.fp] = false
                if (box.spot.kind == "inv") {
                    if (r == null) bad += "${mem.label}: in the inventory at ${box.spot.detail} with no record"
                    else if (r.state != "inv" || r.coords != box.spot.detail) bad += "${mem.label}: in the inventory at ${box.spot.detail}, record says ${r.brief()}"
                    val lit = box.spot.invIndex in shown
                    if (lit != intent) bad += "${mem.label}: marker ${if (lit) "shown" else "missing"} at ${box.spot.detail}"
                } else if (r != null && intent) {
                    val ok = matches(r, box.spot)
                    if (!ok && observed[mem.fp] == true) bad += "${mem.label}: at ${box.spot} (seen by the mod), record says ${r.brief()}"
                    else if (!ok) soft.merge("stale after an unseen move (${box.spot.kind})", 1, Int::plus)
                }
            } else {
                val intent = twinMarked.getValue(mem.fp)
                val happy = rs.count { it.happy }
                if (happy != intent) bad += "${mem.label}: $intent of ${mem.twins} marked, but $happy marked records: ${rs.joinToString { it.brief() }}"
                val invSlots = here.filter { it.spot.kind == "inv" }
                val invKeys = invSlots.map { it.spot.detail }.sorted()
                val recKeys = rs.filter { it.state == "inv" }.map { it.coords }.sorted()
                if (invKeys != recKeys) bad += "${mem.label}: twins in the inventory at $invKeys, records say $recKeys"
                if (rs.size > mem.twins) soft.merge("${mem.label}: more records than boxes", 1, Int::plus)
                // A marked record pointing nowhere while every twin is somewhere a
                // record says it is: the mark is stranded on a ghost.
                val placedByRecord = here.count { box -> rs.any { r -> matches(r, box.spot) && box.spot.kind in setOf("inv", "cont", "ender", "block", "item") } }
                val ghosts = rs.filter { r -> r.happy && here.none { box -> matches(r, box.spot) } && (r.coords.isEmpty() || r.state == "ex-inv") }
                if (placedByRecord == mem.twins && ghosts.isNotEmpty()) bad += "${mem.label}: mark stranded on a record with no box: ${ghosts.joinToString { it.brief() }}"
                val lit = invSlots.count { it.spot.invIndex in shown }
                val owed = rs.count { it.happy && it.state == "inv" && it.coords in invKeys }
                if (lit != owed) bad += "${mem.label}: $lit markers on twins in the inventory, $owed marked records there"
            }
        }
        val fights = fightLines(logSince)
        if (fights > 0) bad += "$fights identity-fight lines in the log: " + logSince.filter {
            it.contains("OVERRODE") || it.contains("duplicate stamp stripped") || it.startsWith("evict:")
        }.take(3).joinToString(" | ")

        relogCheck?.let { before ->
            // No mark may appear or vanish across a relog. A record may move -
            // but only to where a box with its contents really is: the mod
            // correcting a location it could not see before (a dispenser that
            // fired a box onto the ground) is the right answer, not a failure.
            val after = markedSnapshot()
            if (before.keys != after.keys) bad += "relog changed WHICH records are marked: before ${before.keys}, after ${after.keys}"
            for ((uuid, was) in before) {
                val now = after[uuid] ?: continue
                if (now == was) continue
                val r = recs.firstOrNull { it.uuid == uuid } ?: continue
                if (t.none { it.fp == r.hash && matches(r, it.spot) }) bad += "relog moved $uuid from $was to $now, where no such box is"
            }
            relogCheck = null
        }
        return bad
    }

    private fun Rec.brief() = "${uuid.take(8)}=$state/$coords#$slotIndex${if (happy) "*" else ""}"

    private fun matches(r: Rec, s: Spot) = when (s.kind) {
        "inv" -> r.state == "inv" && r.coords == s.detail
        "ender" -> r.state == "enderchest"
        "cont" -> r.state == "ex-inv" && (r.coords == s.pos || r.coords == s.partner)
        "block" -> r.state == "block" && r.coords == s.pos
        "item" -> r.state == "item"
        else -> true
    }

    // Which member a "marked:"/"unmarked:" line was about - by name, then by
    // elimination among unnamed members.
    private fun toggledFp(t: List<TBox>, line: String): String? {
        lastToggled?.let { return it }
        val name = line.substringAfter("§e").substringBefore("§f")
        return members.firstOrNull { it.label == name }?.fp
    }

    private var lastToggled: String? = null

    // --- the walk -------------------------------------------------------------------

    fun run(): Boolean {
        report.append("\n## seed $seed, $steps steps\n")
        build()
        // Counts stay within a stack of 64 for any seed position below 20.
        val s = idx
        val give = listOf(
            Triple("hotbar.0", "Alpha$idx", boxItem("shulker_box", "Alpha$idx", "dirt", 5 + s)),
            Triple("hotbar.1", "pink", boxItem("pink_shulker_box", null, "stone", 10 + s)),
            Triple("hotbar.2", "plain", boxItem("shulker_box", null, "cobblestone", 20 + s)),
            Triple("hotbar.3", "twin", boxItem("shulker_box", null, "gravel", 30 + s)),
            Triple("hotbar.4", "twin", boxItem("shulker_box", null, "gravel", 30 + s)),
            Triple("inventory.0", "twin", boxItem("shulker_box", null, "gravel", 30 + s)),
            Triple("hotbar.5", "Kitt$idx", boxItem("blue_shulker_box", "Kitt$idx", "oak_log", 40 + s)),
            Triple("inventory.1", "Kitt$idx", boxItem("blue_shulker_box", "Kitt$idx", "oak_log", 40 + s)),
        )
        for ((slot, _, item) in give) cmd("item replace entity Player0 $slot with $item")
        ctx.waitTicks(80)
        val t0 = truth()
        fun slotIndex(slot: String) = slot.substringAfter('.').toInt() + if (slot.startsWith("inventory")) 9 else 0
        for ((fp, group) in t0.groupBy { it.fp }) {
            val label = give.first { slotIndex(it.first) == group.first().spot.invIndex }.second
            members += Member(label, fp, group.size)
        }
        for (m in members) if (m.twins == 1) distinctMarked[m.fp] = false else twinMarked[m.fp] = 0
        // Mark Alpha, pink and one of each twin set.
        for (hb in listOf(0, 1, 3, 5)) {
            h.select(hb); h.pressToggle(); ctx.waitTicks(5)
        }
        ctx.waitTicks(60)
        for (m in members) {
            val rs = h.records().filter { it.hash == m.fp && it.happy }
            if (m.twins == 1) distinctMarked[m.fp] = rs.isNotEmpty() else twinMarked[m.fp] = rs.size
            observed[m.fp] = true
        }
        report.append("  population: ").append(members.joinToString { "${it.label}${if (it.twins > 1) "x${it.twins}" else ""}" })
            .append("; marked: ").append(members.joinToString { if (it.twins == 1) "${it.label}=${distinctMarked[it.fp]}" else "${it.label}=${twinMarked[it.fp]}" }).append('\n')
        lastTruth = truth()

        for (step in 1..steps) {
            // One line per step on stdout, and the latest in a file - so a run
            // can be watched: tail -f the gradle output or cat the file.
            val done = idx * steps + step - 1
            val pct = 100 * done / (seedCount * steps)
            val line = "[wtf-fuzz] seed $seed (${idx + 1}/$seedCount) step $step/$steps - $pct% overall"
            println(line)
            File(h.gameDir, "wtf-fuzz-progress.txt").writeText(line + "\n")
            val from = h.logLines().size
            val before = lastTruth
            lastToggled = null
            // Hand toggles name the member by what is in hand: remember it.
            val (action, actedOn) = try {
                act(before).also { (a, _) -> if (a.startsWith("toggle in hand")) lastToggled = before.firstOrNull { it.spot.kind == "inv" && it.spot.invIndex == (a.substringAfter("hotbar").toInt() - 1) }?.fp }
            } catch (e: Throwable) {
                "harness error: $e" to null
            }
            closeScreen()
            ctx.waitTicks(60)
            val t = truth()
            val all = h.logLines()
            val since = (if (all.size >= from) all.drop(from) else all)
            // What actually moved, from the server's side - an action that moved
            // nothing is not evidence of anything.
            val moved = members.flatMap { m ->
                val a = before.filter { it.fp == m.fp }.map { it.spot.toString() }.sorted()
                val b = t.filter { it.fp == m.fp }.map { it.spot.toString() }.sorted()
                if (a == b) emptyList() else listOf("${m.label}: ${(a - b.toSet()).joinToString("+")} -> ${(b - a.toSet()).joinToString("+")}")
            }
            if (moved.isNotEmpty()) movingSteps++
            val kind = action.substringBefore(' ').substringBefore(':')
            kinds.merge(kind, 1, Int::plus)
            trace.append("$step. $action\n").append(moved.joinToString("") { "      $it\n" })
            history.addLast("$step. $action")
            if (history.size > 25) history.removeFirst()
            val wasRelog = relogCheck != null
            var bad = check(t, action, actedOn, since)
            // Identity fights and relog changes are failures however they settle.
            if (bad.isNotEmpty() && !wasRelog && bad.none { it.contains("identity-fight") }) {
                // The mod settles on purpose - a one-second grace before a vanished
                // box is demoted, a 40-tick scan heartbeat. Give it that long once
                // more; only what is still wrong afterwards is a failure.
                ctx.waitTicks(60)
                val t2 = truth()
                val again = check(t2, action, actedOn, emptyList())
                if (again.isEmpty()) {
                    soft.merge("settled within 6s: ${bad.first().take(60)}", 1, Int::plus)
                    bad = emptyList()
                } else bad = again
            }
            lastTruth = t
            if (bad.isNotEmpty()) {
                report.append("  FAIL at step $step: $action\n")
                bad.forEach { report.append("    ✗ ").append(it).append('\n') }
                report.append("  last actions:\n")
                history.forEach { report.append("    ").append(it).append('\n') }
                report.append("  where the boxes are:\n")
                t.forEach { report.append("    ").append(labelOf(it)).append(" @ ").append(it.spot).append('\n') }
                report.append("  mod log for the step:\n")
                since.filter { !it.startsWith("fingerprint") }.takeLast(40).forEach { report.append("    | ").append(it.take(180)).append('\n') }
                File(h.gameDir, "wtf-fuzz-seed$seed.log").writeText(all.joinToString("\n"))
                File(h.gameDir, "wtf-fuzz-seed$seed-steps.txt").writeText(trace.toString())
                return false
            }
        }
        report.append("  PASS ($steps steps, $movingSteps moved a box)\n")
        report.append("  actions: ").append(kinds.entries.sortedByDescending { it.value }.joinToString { "${it.key} x${it.value}" }).append('\n')
        File(h.gameDir, "wtf-fuzz-seed$seed-steps.txt").writeText(trace.toString())
        if (soft.isNotEmpty()) report.append("  known limits hit: ").append(soft.entries.joinToString { "${it.key} x${it.value}" }).append('\n')
        return true
    }

    private fun labelOf(b: TBox) = members.firstOrNull { it.fp == b.fp }?.label ?: "?"
}
