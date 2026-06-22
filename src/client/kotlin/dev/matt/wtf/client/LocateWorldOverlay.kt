package dev.matt.wtf.client

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.rendertype.RenderTypes

// Slow-blinking line-box outline drawn at the active locate target's block
// position, only while its chunk is loaded - this is the player-visible
// "I've reached the right area" signifier (no separate icon needed).
object LocateWorldOverlay {
    fun register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(LevelRenderEvents.AfterTranslucentTerrain { ctx -> render(ctx) })
    }

    private fun render(ctx: LevelRenderContext) {
        val mc = Minecraft.getInstance()
        val target = WTFClient.getLocateTarget() ?: return
        val pos = target.pos ?: return
        val level = mc.level ?: return
        if (level.dimension().identifier().toString() != target.dim) return
        if (!level.isLoaded(pos)) return

        val camera = mc.gameRenderer.mainCamera.position()
        val poseStack = ctx.poseStack()
        val pose = poseStack.last().pose()

        // Full-range pulse (0 -> 1 -> 0) so the fill flashes from invisible to
        // a strong solid block, not just a faint shimmer - needs to read from
        // both far away (line outline) and up close (fill is what actually
        // catches the eye standing right next to it).
        val pulse = (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 450.0)).toFloat()
        val lineAlpha = (0.4f + 0.5f * pulse).coerceIn(0f, 1f)
        val fillAlpha = (0.55f * pulse).coerceIn(0f, 0.55f)

        val x0 = (pos.x - camera.x).toFloat()
        val y0 = (pos.y - camera.y).toFloat()
        val z0 = (pos.z - camera.z).toFloat()
        val x1 = x0 + 1f
        val y1 = y0 + 1f
        val z1 = z0 + 1f

        val lineBuffer = ctx.bufferSource().getBuffer(RenderTypes.lines())
        // 12 edges of the unit cube at the block position.
        val edges = arrayOf(
            floatArrayOf(x0, y0, z0, x1, y0, z0), floatArrayOf(x1, y0, z0, x1, y0, z1),
            floatArrayOf(x1, y0, z1, x0, y0, z1), floatArrayOf(x0, y0, z1, x0, y0, z0),
            floatArrayOf(x0, y1, z0, x1, y1, z0), floatArrayOf(x1, y1, z0, x1, y1, z1),
            floatArrayOf(x1, y1, z1, x0, y1, z1), floatArrayOf(x0, y1, z1, x0, y1, z0),
            floatArrayOf(x0, y0, z0, x0, y1, z0), floatArrayOf(x1, y0, z0, x1, y1, z0),
            floatArrayOf(x1, y0, z1, x1, y1, z1), floatArrayOf(x0, y0, z1, x0, y1, z1)
        )
        for (e in edges) {
            lineBuffer.addVertex(pose, e[0], e[1], e[2]).setColor(1f, 1f, 0.2f, lineAlpha).setNormal(0f, 1f, 0f).setLineWidth(6f)
            lineBuffer.addVertex(pose, e[3], e[4], e[5]).setColor(1f, 1f, 0.2f, lineAlpha).setNormal(0f, 1f, 0f).setLineWidth(6f)
        }

        if (fillAlpha > 0.02f) {
            val fillBuffer = ctx.bufferSource().getBuffer(RenderTypes.debugFilledBox())
            // Emitted both winding directions - two faces went missing with
            // single winding, almost certainly backface-cull on this pipeline
            // catching a wrong vertex order. Double-sided is cheap for 6 faces
            // and guarantees visibility regardless of cull state.
            fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float) {
                fillBuffer.addVertex(pose, ax, ay, az).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, bx, by, bz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, cx, cy, cz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, dx, dy, dz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, dx, dy, dz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, cx, cy, cz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, bx, by, bz).setColor(1f, 1f, 0.2f, fillAlpha)
                fillBuffer.addVertex(pose, ax, ay, az).setColor(1f, 1f, 0.2f, fillAlpha)
            }
            quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1) // bottom
            quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0) // top
            quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0) // -x
            quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1) // +x
            quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0) // -z
            quad(x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1) // +z
        }
    }
}
