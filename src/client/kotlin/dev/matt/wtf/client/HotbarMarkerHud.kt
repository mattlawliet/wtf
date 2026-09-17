package dev.matt.wtf.client

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.player.Inventory

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
        // ponytail: Options.hideGui was removed in 26.2 with no replacement
        // found anywhere (Minecraft/Gui/Hud/Options all searched) - F1 no
        // longer suppresses this marker on this branch. Revisit if a
        // replacement API surfaces.

        // Exact vanilla item-icon position, lifted from Gui.extractItemHotbar:
        // x = guiWidth/2 - 90 + i*20 + 2, y = guiHeight - 16 - 3. Matches the
        // same coordinate space renderHappyMarkers uses for slot.x/slot.y in
        // container screens, so the marker offset below (slot+10, slot-1) lines
        // up identically in both places.
        val hotbarLeft = graphics.guiWidth() / 2 - 90
        val slotY = graphics.guiHeight() - 16 - 3
        val font = mc.font

        for (i in 0 until 9) {
            if (!WTFClient.isHotbarSlotHappy(i)) continue
            val slotX = hotbarLeft + i * 20 + 2
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(slotX + 10f, slotY - 1f)
            pose.scale(0.6f)
            graphics.text(font, WTFClient.getMarkerIcon(), 0, 0, WTFClient.getMarkerColor(), true)
            pose.popMatrix()
        }

        // The offhand is not one of the nine, so the loop above never drew it.
        // Vanilla puts it beside the hotbar on the side opposite the main hand,
        // with the item at center - 91 - 26 (left) or center + 91 + 10 (right).
        // ponytail: those offsets are copied from Gui rather than read from it -
        // there is no API that exposes them. If a version moves the offhand
        // slot, the marker moves with it only after this line is updated.
        val offhand = player.getItemInHand(InteractionHand.OFF_HAND)
        if (!offhand.isEmpty && WTFClient.isHotbarSlotHappy(Inventory.SLOT_OFFHAND)) {
            val center = graphics.guiWidth() / 2
            val offX = if (player.mainArm.opposite == HumanoidArm.LEFT) {
                center - 91 - 26
            } else {
                center + 91 + 10
            }
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.translate(offX + 10f, slotY - 1f)
            pose.scale(0.6f)
            graphics.text(font, WTFClient.getMarkerIcon(), 0, 0, WTFClient.getMarkerColor(), true)
            pose.popMatrix()
        }
    }
}
