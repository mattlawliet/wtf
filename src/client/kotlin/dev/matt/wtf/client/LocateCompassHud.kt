package dev.matt.wtf.client

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth

// Minimal HUD widget: a small arrow centered above the hotbar, rotated to
// point at the active locate target's world position. Only draws for
// state=="block" targets (LocateTarget.pos != null) in the player's
// current dimension.
object LocateCompassHud : HudElement {
    private val ID = Identifier.fromNamespaceAndPath("wtf", "locate_compass")

    fun register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, this)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, tracker: DeltaTracker) {
        val mc = Minecraft.getInstance()
        val target = WTFClient.getLocateTarget() ?: return
        val pos = target.pos ?: return
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (level.dimension().identifier().toString() != target.dim) return

        val dx = (pos.x + 0.5) - player.x
        val dz = (pos.z + 0.5) - player.z
        val angleToTarget = Math.toDegrees(Mth.atan2(dz, dx)).toFloat() - 90f
        val rotation = angleToTarget - player.yRot

        // guiWidth()/2 is the true screen horizontal center - shared by both
        // the ring and the arrow below so they can't drift apart.
        val centerX = graphics.guiWidth() / 2
        val centerY = graphics.guiHeight() - 52

        val size = 6

        // Pixelated round frame: a coarse circle built from same-size square
        // cells as the arrow strokes (2px), not a smooth circle - drawn
        // unrotated so the frame stays put while only the arrow spins.
        drawPixelRing(graphics, centerX, centerY)

        // "Arrived" = crosshair is actually on the target block, not just
        // standing near it - swap the spinning arrow for the player's chosen
        // marker icon/color so arriving reads as a distinct state, not just
        // the arrow getting short.
        val hitResult = mc.hitResult
        val arrived = hitResult is net.minecraft.world.phys.BlockHitResult && hitResult.blockPos == pos
        if (arrived) {
            graphics.centeredText(mc.font, WTFClient.getMarkerIcon(), centerX, centerY - 4, WTFClient.getMarkerColor())
            return
        }

        graphics.pose().pushMatrix()
        graphics.pose().translate(centerX.toFloat(), centerY.toFloat())
        graphics.pose().rotate(Math.toRadians(rotation.toDouble()).toFloat())
        val color = 0xFFE62020.toInt()
        val outline = 0xFF601010.toInt()
        // Overall length unchanged (still spans -size..size) - just a longer
        // thin shaft and a short, tight pixel-triangle head instead of a head
        // that ate half the arrow. Narrows one pixel per row for max
        // resolution in minimal space, with a 1px dark outline for contrast.
        val headHeight = 4
        val tipY = -size
        val headBaseY = tipY + headHeight
        graphics.fill(-1, headBaseY, 1, size, color)
        // Exactly headHeight rows, halfWidth counting down to 0 right at the
        // tip row - a while-loop on y>=tipY here previously ran one row past
        // the tip with halfWidth gone negative (inverted rect = stray pixel
        // poking out beyond the point).
        for (i in 0 until headHeight) {
            val y = headBaseY - i
            val halfWidth = headHeight - 1 - i
            graphics.fill(-halfWidth - 1, y, halfWidth + 1, y + 1, outline)
            graphics.fill(-halfWidth, y, halfWidth, y + 1, color)
        }
        graphics.pose().popMatrix()
    }

    private fun drawPixelRing(graphics: GuiGraphicsExtractor, centerX: Int, centerY: Int) {
        val px = 2
        val outerR = 13
        // Keep the cell grid's bound symmetric around 0 (same odd cell count
        // both sides) regardless of outerR, so the ring stays centered on
        // centerX/centerY instead of drifting when the radius changes.
        val gridBound = (outerR / px) * px
        val cells = -gridBound..gridBound step px
        for (cellX in cells) {
            for (cellY in cells) {
                val dist = Math.sqrt((cellX * cellX + cellY * cellY).toDouble())
                val color = when {
                    dist <= outerR - px -> 0xFF000000.toInt()
                    dist <= outerR -> 0xFF8B8B8B.toInt()
                    else -> continue
                }
                // fill()'s args are a cell's top-left corner, not its center -
                // shift back by half a cell so cellX=0/cellY=0 straddles the
                // true center point instead of sitting to its bottom-right.
                val half = px / 2
                graphics.fill(centerX + cellX - half, centerY + cellY - half, centerX + cellX - half + px, centerY + cellY - half + px, color)
            }
        }
    }
}
