package dev.matt.wtf.gametest

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.io.File

// What the mod believes about one box. Read straight out of WTFClient by
// reflection rather than from moods.json, because the save drops unmarked
// records - and a stray unmarked record is exactly what "one box, two records"
// looks like.
data class Rec(
    val uuid: String,
    val state: String,
    val coords: String,
    val name: String,
    val happy: Boolean,
    val hash: String,
    val from: String,
    val slotIndex: Int,
    val firstSeen: Long,
    val type: String,
)

class ScenarioFailed(msg: String) : AssertionError(msg)

class Harness(val ctx: ClientGameTestContext) {
    lateinit var server: TestDedicatedServerContext
    private val report = StringBuilder()
    val failures = mutableListOf<String>()
    private var nextArea = 0
    private var nextFill = 1

    // --- server side -------------------------------------------------------

    fun cmd(c: String) = server.runCommand(c)

    // A shulker box item built by the server, the way a live server hands one
    // back. `fill` makes a scenario's contents unique so an ex-inv record left
    // behind by an earlier scenario can never be recovered by a later one.
    fun box(name: String?, fill: Int, color: String = "shulker_box"): String {
        val comps = mutableListOf<String>()
        if (name != null) comps += "minecraft:custom_name=\"$name\""
        comps += "minecraft:container=[{slot:0,item:{id:\"minecraft:stone\",count:$fill}}]"
        return "minecraft:$color[${comps.joinToString(",")}]"
    }

    fun uniqueFill(): Int = nextFill++

    fun put(slot: String, item: String) = cmd("item replace entity Player0 $slot with $item")

    // Fresh ground for each scenario, far enough apart that no chest of one is
    // in reach of another.
    fun area(): BlockPos {
        val p = BlockPos(nextArea * 16, -60, 32)
        nextArea++
        cmd("fill ${p.x - 4} -60 ${p.z - 4} ${p.x + 4} -56 ${p.z + 4} minecraft:air")
        cmd("tp Player0 ${p.x - 2}.5 -60 ${p.z}.5 -90 30")
        ctx.waitTicks(10)
        return p
    }

    // --- client side -------------------------------------------------------

    // Minecraft.getInstance() is refused on the test thread; the directory is
    // fixed for the whole run, so read it once through the client.
    val gameDir: File by lazy { ctx.computeOnClient<File, RuntimeException> { it.gameDirectory } }

    private val toggleKey: KeyMapping by lazy {
        ctx.computeOnClient<KeyMapping, RuntimeException> { mc ->
            val km = mc.options.keyMappings.first { it.name == "key.wtf.toggle_happy" }
            km.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K))
            KeyMapping.resetMapping()
            km
        }
    }

    fun select(hotbar: Int) {
        ctx.getInput().pressKey { it.keyHotbarSlots[hotbar] }
        ctx.waitTicks(3)
    }

    // The mod's own keybind with no screen open: marks the box in the main hand.
    fun markInHand(hotbar: Int) {
        select(hotbar)
        ctx.getInput().pressKey(toggleKey)
        ctx.waitTicks(5)
    }

    fun pressF() {
        ctx.getInput().pressKey { it.keySwapOffhand }
    }

    fun openAt(pos: BlockPos) {
        ctx.getInput().lookAt(pos)
        ctx.waitTicks(2)
        ctx.getInput().pressKey { it.keyUse }
        ctx.waitFor({ it.gui.screen() is AbstractContainerScreen<*> }, 100)
        ctx.waitTicks(10)
    }

    // A right-click on exactly this block - the crosshair can clip a neighbour.
    fun openExact(pos: BlockPos) {
        ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            val face = net.minecraft.core.Direction.getApproximateNearest(p.x - (pos.x + 0.5), 0.0, p.z - (pos.z + 0.5))
            val hit = net.minecraft.world.phys.Vec3.atCenterOf(pos).add(face.stepX * 0.5, 0.0, face.stepZ * 0.5)
            mc.gameMode!!.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.phys.BlockHitResult(hit, face, pos, false))
        }
        ctx.waitFor({ it.gui.screen() is AbstractContainerScreen<*> }, 100)
        ctx.waitTicks(10)
    }

    // Open and return as soon as the screen exists - for checks that care what
    // the first tick after the server's container packet looks like.
    fun openAtNow(pos: BlockPos) {
        ctx.getInput().lookAt(pos)
        ctx.waitTicks(2)
        ctx.getInput().pressKey { it.keyUse }
        ctx.waitFor({ it.gui.screen() is AbstractContainerScreen<*> }, 100)
        ctx.waitTicks(1)
    }

    // What itemscroller's move-matching asks: are these two the same stack?
    fun sameStack(invIndex: Int, menuSlot: Int): Boolean = ctx.computeOnClient<Boolean, RuntimeException> { mc ->
        val p = mc.player!!
        ItemStack.isSameItemSameComponents(p.inventory.getItem(invIndex), p.containerMenu.slots[menuSlot].item)
    }

    fun menuStamp(menuSlot: Int): String? = ctx.computeOnClient<String?, RuntimeException> { mc ->
        stampOf(mc.player!!.containerMenu.slots[menuSlot].item)
    }

    fun placeFromHotbar(hotbar: Int, onGround: BlockPos) {
        select(hotbar)
        ctx.getInput().lookAt(onGround)
        ctx.waitTicks(2)
        ctx.getInput().pressKey { it.keyUse }
        ctx.waitTicks(10)
    }

    fun closeScreen() {
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE)
        ctx.waitFor({ it.gui.screen() == null }, 100)
        ctx.waitTicks(5)
    }

    // Menu slot indices of the player's own inventory in whatever menu is open,
    // by inventory index (0..8 hotbar, 9..35 main).
    fun menuSlotOfInventory(invIndex: Int): Int = ctx.computeOnClient<Int, RuntimeException> { mc ->
        val p = mc.player!!
        p.containerMenu.slots.first { it.container === p.inventory && it.containerSlot == invIndex }.index
    }

    fun menuSlotOfContainer(containerSlot: Int): Int = ctx.computeOnClient<Int, RuntimeException> { mc ->
        val p = mc.player!!
        p.containerMenu.slots.first { it.container !== p.inventory && it.containerSlot == containerSlot }.index
    }

    // Several shift-clicks inside ONE client tick: the itemscroller-style mass
    // move, with no scan able to run between them.
    fun quickMoveAll(menuSlots: List<Int>) {
        ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            for (s in menuSlots) {
                mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, s, 0, ContainerInput.QUICK_MOVE, p)
            }
        }
        ctx.waitTicks(10)
    }

    // PICKUP clicks: take whatever is in `from`, drop it in `to` - two slots of
    // the open menu, the way a person drags with the mouse.
    fun carry(from: Int, to: Int) {
        ctx.runOnClient<RuntimeException> { mc ->
            val p = mc.player!!
            mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, from, 0, ContainerInput.PICKUP, p)
            mc.gameMode!!.handleContainerInput(p.containerMenu.containerId, to, 0, ContainerInput.PICKUP, p)
        }
        ctx.waitTicks(5)
    }

    // What the open menu holds, for notes: "cursor=… s0=… s1=…" over `slots`.
    fun menuDump(slots: List<Int>): String = ctx.computeOnClient<String, RuntimeException> { mc ->
        val m = mc.player!!.containerMenu
        fun d(st: ItemStack) = if (st.isEmpty) "-" else "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item).path}x${st.count}"
        "cursor=${d(m.carried)} " + slots.joinToString(" ") { "s$it=${d(m.slots[it].item)}" }
    }

    fun itemIdAt(invIndex: Int): String? = ctx.computeOnClient<String?, RuntimeException> { mc ->
        val s = mc.player!!.inventory.getItem(invIndex)
        if (s.isEmpty) null else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.item).toString()
    }

    // Park the mouse over a menu slot of the open screen, in window pixels.
    fun hover(menuSlot: Int) {
        val (x, y) = ctx.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            val screen = mc.gui.screen() as AbstractContainerScreen<*>
            fun field(n: String) = AbstractContainerScreen::class.java.getDeclaredField(n).apply { isAccessible = true }.getInt(screen)
            val slot = mc.player!!.containerMenu.slots[menuSlot]
            val scale = mc.window.guiScale.toDouble()
            ((field("leftPos") + slot.x + 8) * scale) to ((field("topPos") + slot.y + 8) * scale)
        }
        ctx.getInput().setCursorPos(x, y)
        ctx.waitTicks(5)
    }

    fun stampAt(invIndex: Int): String? = ctx.computeOnClient<String?, RuntimeException> { mc ->
        stampOf(mc.player!!.inventory.getItem(invIndex))
    }

    fun itemNameAt(invIndex: Int): String? = ctx.computeOnClient<String?, RuntimeException> { mc ->
        val s = mc.player!!.inventory.getItem(invIndex)
        if (s.isEmpty) null else s.get(DataComponents.CUSTOM_NAME)?.string ?: "Shulker Box"
    }

    private fun stampOf(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val raw: Any? = data.copyTag().getString("wtf:uuid")
        return ((raw as? java.util.Optional<*>)?.orElse(null) ?: raw) as? String
    }

    fun records(): List<Rec> = ctx.computeOnClient<List<Rec>, RuntimeException> { _ ->
        val cls = Class.forName("dev.matt.wtf.client.WTFClient")
        val f = cls.getDeclaredField("trackedShulkers")
        f.isAccessible = true
        val map = f.get(null) as Map<*, *>
        map.values.map { e ->
            fun g(n: String): Any? = e!!.javaClass.getMethod(n).invoke(e)
            Rec(
                g("getUuid") as String, g("getState") as String, g("getCoords") as String,
                g("getName") as String, g("getHappy") as Boolean, g("getContentHash") as String,
                g("getFrom") as String, g("getSlotIndex") as Int, g("getFirstSeen") as Long,
                g("getType") as String,
            )
        }
    }

    // Menu slot indices the mod will draw a marker on this frame.
    fun markedMenuSlots(): Set<Int> = ctx.computeOnClient<Set<Int>, RuntimeException> { _ ->
        val f = Class.forName("dev.matt.wtf.client.WTFClient").getDeclaredField("markedMenuSlots")
        f.isAccessible = true
        (f.get(null) as Set<*>).map { it as Int }.toSet()
    }

    fun pressToggle() {
        ctx.getInput().pressKey(toggleKey)
        ctx.waitTicks(5)
    }

    fun named(name: String) = records().filter { it.name == name }

    fun one(name: String): Rec {
        val rs = named(name)
        check(rs.size == 1, "expected exactly one record named '$name', found ${rs.size}: $rs")
        return rs.single()
    }

    // --- the mod's debug log -----------------------------------------------

    private fun logFile(): File? {
        val base = File(gameDir, "config/wtf")
        return base.listFiles()?.filter { it.isDirectory }
            ?.map { File(it, "debug.log") }?.filter { it.exists() }
            ?.maxByOrNull { it.lastModified() }
    }

    fun logLines(): List<String> {
        ctx.waitTicks(1) // the mod flushes its writer at the end of a tick
        return logFile()?.readLines() ?: emptyList()
    }

    // --- scenario bookkeeping ----------------------------------------------

    private var soft = mutableListOf<String>()

    fun check(cond: Boolean, msg: String) {
        if (!cond) soft.add(msg)
    }

    fun note(msg: String) {
        report.append("    · ").append(msg).append('\n')
    }

    // WTF_SCENARIOS=<regex> runs only the scenarios whose names match.
    private val only = System.getenv("WTF_SCENARIOS")?.let(::Regex)
    val ran = mutableSetOf<String>()

    // The log as it stood just before a relog wiped it; set by the relog step.
    var beforeRelog: List<String> = emptyList()

    fun scenario(name: String, body: () -> Unit) {
        if (only != null && !only.containsMatchIn(name)) return
        ran.add(name)
        val from = logLines().size
        soft = mutableListOf()
        report.append("\n## ").append(name).append('\n')
        var crash: Throwable? = null
        try {
            cmd("clear Player0")
            ctx.waitTicks(5)
            if (ctx.computeOnClient<Boolean, RuntimeException> { it.gui.screen() != null }) closeScreen()
            select(0)
            body()
        } catch (t: Throwable) {
            crash = t
        }
        // The mod starts a fresh debug.log on every join, so after a relog the
        // file is shorter than where this scenario began: keep all of it.
        val all = logLines()
        val lines = if (all.size >= from) all.drop(from) else beforeRelog.drop(from) + listOf("--- relog ---") + all
        beforeRelog = emptyList()
        val excerpt = lines.filter {
            it.contains("transition:") || it.contains("click:") || it.contains("OVERRODE") ||
                it.contains("new shulker discovered") || it.contains("hash-matched") ||
                it.contains("offhand") || it.contains("ledger:") || it.contains("recovery")
        }
        File(gameDir, "wtf-scenario-logs").mkdirs()
        File(gameDir, "wtf-scenario-logs/${name.take(40).replace(Regex("[^A-Za-z0-9]+"), "_")}.log")
            .writeText(lines.joinToString("\n"))
        val overrode = lines.count { it.contains("OVERRODE") }
        if (overrode > 0) note("OVERRODE lines: $overrode")
        excerpt.takeLast(25).forEach { report.append("      > ").append(it.substringAfter("] ").take(160)).append('\n') }
        if (crash != null) soft.add("crashed: $crash")
        if (soft.isEmpty()) {
            report.append("  PASS\n")
        } else {
            report.append("  FAIL\n")
            soft.forEach { report.append("    ✗ ").append(it).append('\n') }
            failures.add(name)
            runCatching { ctx.takeScreenshot("fail-" + name.take(30).replace(Regex("[^A-Za-z0-9]+"), "_")) }
        }
    }

    fun finish() {
        val header = "WTF scenario run - ${failures.size} failed\n" +
            (if (failures.isEmpty()) "" else failures.joinToString("\n") { "  FAILED: $it" } + "\n")
        File(gameDir, "wtf-scenarios.txt").writeText(header + report)
        println(header + report)
        if (failures.isNotEmpty()) throw ScenarioFailed("${failures.size} scenario(s) failed - see wtf-scenarios.txt")
    }
}
