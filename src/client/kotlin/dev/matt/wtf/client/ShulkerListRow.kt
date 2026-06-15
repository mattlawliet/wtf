package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ShulkerListRow(
    val id: String,
    val name: Component,
    val stack: ItemStack,
    val section: ShulkerSectionType,
    val matchPercent: Float = 0f,
    val lastKnown: Boolean = false,
    val location: String = ""
) {
    companion object {
        const val BASE_ROW_HEIGHT = 17
        const val SLOT_SIZE = 16
    }

    fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, isHovered: Boolean, isSelected: Boolean) {
        val bgColor = when {
            isSelected -> 0xFF4A4A4A.toInt()
            isHovered -> 0xFF3A3A3A.toInt()
            else -> 0
        }

        if (bgColor != 0) {
            graphics.fill(x, y, x + width, y + BASE_ROW_HEIGHT, bgColor)
        }

        val textColor = when (section) {
            ShulkerSectionType.BLOCK -> 0xFFFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0xFF55FF55.toInt()
            ShulkerSectionType.ITEM -> 0xFFFFAA55.toInt()
            ShulkerSectionType.EXTERNAL_INV -> 0xFF55FFFF.toInt()
            ShulkerSectionType.ENDERCHEST -> 0xFFAA55FF.toInt()
        }

        graphics.item(stack, x + 2, y + (BASE_ROW_HEIGHT - SLOT_SIZE) / 2)

        val displayName = name.string
        val prefix = if (lastKnown && section == ShulkerSectionType.EXTERNAL_INV) "§c[LK]§r " else ""
        val textLeftPad = SLOT_SIZE + 4
        val showPercent = matchPercent > 0f && WTFClient.isShowMatchPercentEnabled()
        val rightPad = 4 + (if (showPercent) font.width("${(matchPercent * 100).toInt()}%") + 4 else 0)
        val maxTextWidth = width - textLeftPad - rightPad
        val fullName = "$prefix$displayName"
        val truncatedName = if (font.width(fullName) > maxTextWidth)
            font.plainSubstrByWidth(fullName, maxTextWidth)
        else fullName

        graphics.text(font, truncatedName, x + textLeftPad, y + (BASE_ROW_HEIGHT - 8) / 2, textColor, false)

        if (showPercent) {
            val percentText = "${(matchPercent * 100).toInt()}%"
            val percentWidth = font.width(percentText)
            graphics.text(
                font,
                Component.literal(percentText),
                x + width - percentWidth - 4,
                y + (BASE_ROW_HEIGHT - 8) / 2,
                0xFF55FF55.toInt(),
                false
            )
        }
    }
}

enum class ShulkerSectionType {
    BLOCK,
    INVENTORY,
    ITEM,
    EXTERNAL_INV,
    ENDERCHEST
}
