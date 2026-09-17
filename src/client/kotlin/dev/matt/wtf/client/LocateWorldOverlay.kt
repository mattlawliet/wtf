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

        val camera = mc.gameRenderer.mainCamera().position()
        val poseStack = ctx.poseStack()
        val collector = ctx.submitNodeCollector()

        // Full-range pulse (0 -> 1 -> 0) so the fill flashes from invisible to
        // a strong solid block, not just a faint shimmer - needs to read from
        // both far away (line outline) and up close (fill is what actually
        // catches the eye standing right next to it).
        val pulse = (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 450.0)).toFloat()
        val lineAlpha = (0.4f + 0.5f * pulse).coerceIn(0f, 1f)
        val fillAlpha = (0.55f * pulse).coerceIn(0f, 0.55f)

        poseStack.pushPose()
        poseStack.translate(pos.x - camera.x, pos.y - camera.y, pos.z - camera.z)

        // Local unit-cube coords (0..1) - translation into camera-relative
        // space is already baked into poseStack above, not applied per-vertex
        // like the old direct-buffer approach.
        collector.submitCustomGeometry(poseStack, RenderTypes.lines()) { pose, vertexConsumer ->
            val edges = arrayOf(
                floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), floatArrayOf(1f, 0f, 0f, 1f, 0f, 1f),
                floatArrayOf(1f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
                floatArrayOf(0f, 1f, 0f, 1f, 1f, 0f), floatArrayOf(1f, 1f, 0f, 1f, 1f, 1f),
                floatArrayOf(1f, 1f, 1f, 0f, 1f, 1f), floatArrayOf(0f, 1f, 1f, 0f, 1f, 0f),
                floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 0f, 1f, 1f, 0f),
                floatArrayOf(1f, 0f, 1f, 1f, 1f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f)
            )
            for (e in edges) {
                vertexConsumer.addVertex(pose, e[0], e[1], e[2]).setColor(1f, 1f, 0.2f, lineAlpha).setNormal(0f, 1f, 0f).setLineWidth(6f)
                vertexConsumer.addVertex(pose, e[3], e[4], e[5]).setColor(1f, 1f, 0.2f, lineAlpha).setNormal(0f, 1f, 0f).setLineWidth(6f)
            }
        }

        if (fillAlpha > 0.02f) {
            collector.submitCustomGeometry(poseStack, RenderTypes.debugFilledBox()) { pose, vertexConsumer ->
                // Emitted both winding directions - two faces went missing with
                // single winding, almost certainly backface-cull on this pipeline
                // catching a wrong vertex order. Double-sided is cheap for 6 faces
                // and guarantees visibility regardless of cull state.
                fun quad(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float) {
                    vertexConsumer.addVertex(pose, ax, ay, az).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, bx, by, bz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, cx, cy, cz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, dx, dy, dz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, dx, dy, dz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, cx, cy, cz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, bx, by, bz).setColor(1f, 1f, 0.2f, fillAlpha)
                    vertexConsumer.addVertex(pose, ax, ay, az).setColor(1f, 1f, 0.2f, fillAlpha)
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
