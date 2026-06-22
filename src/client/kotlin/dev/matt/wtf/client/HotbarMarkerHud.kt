package dev.matt.wtf.client

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier

// Same happy-marker icon shown in container/inventory screens (renderHappyMarkers
// in WTFClient), but for the 9 hotbar slots during normal gameplay, where no
// AbstractContainerScreen exists to hook - this is the only way to surface it
// without opening inventory.
object HotbarMarkerHud : HudElement {
    private val ID = Identifier.fromNamespaceAndPath("wtf", "hotbar_markers")

    fun register() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, ID, this)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, tracker: DeltaTracker) {
        if (WTFClient.isMarkerIconNone()) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.options.hideGui) return

        // Exact vanilla item-icon position, lifted from Gui.extractItemHotbar:
        // x = guiWidth/2 - 90 + i*20 + 2, y = guiHeight - 16 - 3. Matches the
        // same coordinate space renderHappyMarkers uses for slot.x/slot.y in
        // container screens, so the marker offset below (slot+10, slot-1) lines
        // up identically in both places.
        val hotbarLeft = graphics.guiWidth() / 2 - 90
        val slotY = graphics.guiHeight() - 16 - 3
        val font = mc.font

        for (i in 0 until 9) {
            val stack = player.inventory.getItem(i)
            if (!WTFClient.isHotbarSlotHappy(stack)) continue
            val slotX = hotbarLeft + i * 20 + 2
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(slotX + 10f, slotY - 1f)
            pose.scale(0.6f)
            graphics.text(font, WTFClient.getMarkerIcon(), 0, 0, WTFClient.getMarkerColor(), true)
            pose.popMatrix()
        }
    }
}
