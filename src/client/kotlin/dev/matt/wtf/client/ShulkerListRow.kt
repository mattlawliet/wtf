package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ShulkerListRow(
    val id: String,
    val name: Component,
    val stack: ItemStack,
    val section: ShulkerSectionType,
    val matchPercent: Float = 0f
) {
    private val rowHeight = 24

    companion object {
        const val ICON_SIZE = 16
        const val ICON_PADDING = 4
        const val TEXT_LEFT_PAD = ICON_SIZE + ICON_PADDING * 2
    }

    fun getRowHeight() = rowHeight

    fun render(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, isHovered: Boolean, isSelected: Boolean) {
        val bgColor = when {
            isSelected -> 0x4A4A4A4A.toInt()
            isHovered -> 0x2A2A2A2A.toInt()
            else -> 0
        }

        if (bgColor != 0) {
            graphics.fill(x, y, x + width, y + rowHeight, bgColor)
        }

        val textColor = when (section) {
            ShulkerSectionType.BLOCK -> 0xFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0x55FF55.toInt()
            ShulkerSectionType.TRANSIT -> 0xFFAA55.toInt()
        }

        graphics.renderItem(stack, x + ICON_PADDING, y + (rowHeight - ICON_SIZE) / 2)

        val displayName = name.string
        val truncatedName = if (displayName.length > 30) displayName.take(27) + "..." else displayName

        graphics.drawString(
            font,
            Component.literal(truncatedName),
            x + TEXT_LEFT_PAD,
            y + (rowHeight - 8) / 2,
            textColor,
            false
        )

        if (matchPercent > 0f) {
            val percentText = "${(matchPercent * 100).toInt()}%"
            val percentWidth = font.width(percentText)
            graphics.drawString(
                font,
                Component.literal(percentText),
                x + width - percentWidth - 4,
                y + (rowHeight - 8) / 2,
                0x808080.toInt(),
                false
            )
        }
    }
}

enum class ShulkerSectionType {
    BLOCK,
    INVENTORY,
    TRANSIT
}