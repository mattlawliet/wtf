package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

class ShulkerSection(
    val type: ShulkerSectionType,
    val entries: List<ShulkerListRow>
) {
    companion object {
        const val BASE_HEADER_HEIGHT = 14
        const val BASE_ROW_HEIGHT = 17
    }

    var scrollOffset = 0

    fun getHeaderHeight(scale: Float) = (BASE_HEADER_HEIGHT * scale).toInt()
    fun getRowHeight(scale: Float) = (BASE_ROW_HEIGHT * scale).toInt()
    fun getTotalContentHeight(scale: Float) = entries.size * getRowHeight(scale)

    fun scroll(delta: Int) {
        val maxScroll = maxOf(0, entries.size * BASE_ROW_HEIGHT - 140)
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxScroll)
    }

    fun resetScroll() {
        scrollOffset = 0
    }

    fun renderHeader(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, scale: Float = 1f) {
        val headerHeight = getHeaderHeight(scale)
        val textPadding = (4 * scale).toInt()

        val headerColor = when (type) {
            ShulkerSectionType.BLOCK -> 0xFFFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0xFF55FF55.toInt()
            ShulkerSectionType.TRANSIT -> 0xFFFFAA55.toInt()
        }

        graphics.fill(x, y, x + width, y + headerHeight, 0xFF2A2A2A.toInt())

        val headerText = when (type) {
            ShulkerSectionType.BLOCK -> "BLOCKS"
            ShulkerSectionType.INVENTORY -> "INVENTORY"
            ShulkerSectionType.TRANSIT -> "TRANSIT"
        }

        graphics.drawString(
            font,
            Component.literal(headerText),
            x + textPadding,
            y + (headerHeight - 8) / 2,
            headerColor,
            false
        )

        val countText = "(${entries.size})"
        val countWidth = font.width(countText)
        graphics.drawString(
            font,
            Component.literal(countText),
            x + width - countWidth - textPadding,
            y + (headerHeight - 8) / 2,
            0xFF808080.toInt(),
            false
        )
    }

    fun renderEntries(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, visibleHeight: Int, hoveredId: String?, selectedId: String?, scale: Float = 1f) {
        val rowHeight = getRowHeight(scale)
        val startRow = (scrollOffset / rowHeight).coerceAtLeast(0)
        val endRow = minOf(entries.size, startRow + (visibleHeight / rowHeight) + 1)

        for (i in startRow until endRow) {
            val entry = entries[i]
            val entryY = y + i * rowHeight - scrollOffset

            if (entryY + rowHeight < y || entryY > y + visibleHeight) continue

            entry.render(graphics, font, x, entryY, width, isHovered = entry.id == hoveredId, isSelected = entry.id == selectedId, scale)
        }
    }

    fun getEntryAtPosition(localY: Int): ShulkerListRow? {
        val rowIndex = (localY + scrollOffset) / BASE_ROW_HEIGHT
        return if (rowIndex in entries.indices) entries[rowIndex] else null
    }
}