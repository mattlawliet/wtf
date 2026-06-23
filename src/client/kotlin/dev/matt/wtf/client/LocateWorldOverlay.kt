package dev.matt.wtf.client

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.world.phys.shapes.Shapes

// Slow-blinking line-box outline + pulsing fill drawn at the active locate
// target's block position, only while its chunk is loaded - this is the
// player-visible "I've reached the right area" signifier.
//
// 26.2 moved level rendering to a submit-node model (LevelRenderContext lost
// bufferSource()/poseStack()-direct-draw entirely) - outline goes through
// submitShapeOutline (the same call vanilla's own block-selection outline
// uses), fill goes through submitCustomGeometry's VertexConsumer callback.
object LocateWorldOverlay {
    fun register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(LevelRenderEvents.AfterTranslucentTerrain { ctx -> render(ctx) })
    }

    private fun argb(r: Float, g: Float, b: Float, a: Float): Int {
        val ai = (a * 255f).toInt().coerceIn(0, 255)
        val ri = (r * 255f).toInt().coerceIn(0, 255)
        val gi = (g * 255f).toInt().coerceIn(0, 255)
        val bi = (b * 255f).toInt().coerceIn(0, 255)
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun render(ctx: LevelRenderContext) {
        val mc = Minecraft.getInstance()
        val target = WTFClient.getLocateTarget() ?: return
        val pos = target.pos ?: return
        val level = mc.level ?: return
        if (level.dimension().identifier().toString() != target.dim) return
        if (!level.isLoaded(pos)) return

        val camera = mc.gameRenderer.mainCamera().position()
        val poseStack = ctx.poseStack()
        val collector: SubmitNodeCollector = ctx.submitNodeCollector()

        // Full-range pulse (0 -> 1 -> 0) so the fill flashes from invisible to
        // a strong solid block, not just a faint shimmer - needs to read from
        // both far away (line outline) and up close (fill is what actually
        // catches the eye standing right next to it).
        val pulse = (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 450.0)).toFloat()
        val lineAlpha = (0.4f + 0.5f * pulse).coerceIn(0f, 1f)
        val fillAlpha = (0.55f * pulse).coerceIn(0f, 0.55f)

        val ox = (pos.x - camera.x).toFloat()
        val oy = (pos.y - camera.y).toFloat()
        val oz = (pos.z - camera.z).toFloat()

        poseStack.pushPose()
        poseStack.translate(ox, oy, oz)

        collector.submitShapeOutline(poseStack, Shapes.block(), RenderTypes.lines(), argb(1f, 1f, 0.2f, lineAlpha), 6f, false)

        if (fillAlpha > 0.02f) {
            val fillColor = argb(1f, 1f, 0.2f, fillAlpha)
            collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox()) { pose, vc ->
                // Emitted both winding directions - two faces went missing
                // with single winding on this pipeline (backface-cull on a
                // wrong vertex order). Double-sided is cheap for 6 faces and
                // guarantees visibility regardless of cull state. Local unit
                // cube coords since translate already happened on poseStack.
                fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float) {
                    vc.addVertex(pose, ax, ay, az).setColor(fillColor)
                    vc.addVertex(pose, bx, by, bz).setColor(fillColor)
                    vc.addVertex(pose, cx, cy, cz).setColor(fillColor)
                    vc.addVertex(pose, dx, dy, dz).setColor(fillColor)
                    vc.addVertex(pose, dx, dy, dz).setColor(fillColor)
                    vc.addVertex(pose, cx, cy, cz).setColor(fillColor)
                    vc.addVertex(pose, bx, by, bz).setColor(fillColor)
                    vc.addVertex(pose, ax, ay, az).setColor(fillColor)
                }
                quad(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 1f) // bottom
                quad(0f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 0f) // top
                quad(0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f, 0f) // -x
                quad(1f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 0f, 1f) // +x
                quad(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f) // -z
                quad(0f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f, 1f, 0f, 1f) // +z
            }
        }

        poseStack.popPose()
    }
}
