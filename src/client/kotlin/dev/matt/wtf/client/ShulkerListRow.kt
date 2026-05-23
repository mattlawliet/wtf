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
    companion object {
        const val BASE_ICON_SIZE = 10
        const val BASE_ICON_PADDING = 3
        const val BASE_ROW_HEIGHT = 17
    }

    fun getRowHeight(scale: Float) = (BASE_ROW_HEIGHT * scale).toInt()
    fun getIconSize(scale: Float) = (BASE_ICON_SIZE * scale).toInt()
    fun getIconPadding(scale: Float) = (BASE_ICON_PADDING * scale).toInt()

    fun render(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, isHovered: Boolean, isSelected: Boolean, scale: Float = 1f) {
        val rowHeight = getRowHeight(scale)
        val iconSize = getIconSize(scale)
        val iconPadding = getIconPadding(scale)
        val slotSize = 16
        val textLeftPad = slotSize + iconPadding * 2

        val bgColor = when {
            isSelected -> 0xFF4A4A4A.toInt()
            isHovered -> 0xFF3A3A3A.toInt()
            else -> 0
        }

        if (bgColor != 0) {
            graphics.fill(x, y, x + width, y + rowHeight, bgColor)
        }

        val textColor = when (section) {
            ShulkerSectionType.BLOCK -> 0xFFFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0xFF55FF55.toInt()
            ShulkerSectionType.ITEM -> 0xFFFFAA55.toInt()
        }

        val iconScale = (iconSize.toFloat() / slotSize).coerceAtMost(1f)
        val scaledDrawSize = slotSize * iconScale
        val iconX = x + iconPadding + ((slotSize - scaledDrawSize) / 2f).toInt()
        val iconY = y + ((rowHeight - scaledDrawSize) / 2f).toInt()

        graphics.pose().pushMatrix()
        graphics.pose().translate(iconX.toFloat(), iconY.toFloat())
        graphics.pose().scale(iconScale, iconScale)
        graphics.renderItem(stack, 0, 0)
        graphics.pose().popMatrix()

        val displayName = name.string
        val truncatedName = if (displayName.length > 30) displayName.take(27) + "..." else displayName

        graphics.drawString(
            font,
            Component.literal(truncatedName),
            x + textLeftPad,
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
                x + width - percentWidth - (4 * scale).toInt(),
                y + (rowHeight - 8) / 2,
                0xFF55FF55.toInt(),
                false
            )
        }
    }
}

enum class ShulkerSectionType {
    BLOCK,
    INVENTORY,
    ITEM
}
