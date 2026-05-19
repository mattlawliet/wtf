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
    var isCollapsed = false

    fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) scrollOffset = 0
    }

    fun getHeaderHeight(scale: Float) = (BASE_HEADER_HEIGHT * scale).toInt()
    fun getRowHeight(scale: Float) = (BASE_ROW_HEIGHT * scale).toInt()
    fun getTotalContentHeight(scale: Float) = entries.size * getRowHeight(scale)

    fun scroll(delta: Int) {
        if (isCollapsed) return
        val maxScroll = maxOf(0, entries.size * BASE_ROW_HEIGHT - 100)
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxScroll)
    }

    fun resetScroll() {
        scrollOffset = 0
    }

    fun renderHeader(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, scale: Float = 1f) {
        val headerHeight = getHeaderHeight(scale)

        val headerColor = when (type) {
            ShulkerSectionType.BLOCK -> 0xFFFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0xFF55FF55.toInt()
            ShulkerSectionType.ITEM -> 0xFFFFAA55.toInt()
        }

        graphics.fill(x, y, x + width, y + headerHeight, 0xFF2A2A2A.toInt())

        val collapseArrow = if (isCollapsed) ">" else "v"
        graphics.drawString(font, collapseArrow, x + 2, y + (headerHeight - 8) / 2, 0xFFFFFFFF.toInt(), false)

        val headerText = when (type) {
            ShulkerSectionType.BLOCK -> "BLOCKS"
            ShulkerSectionType.INVENTORY -> "INVENTORY"
            ShulkerSectionType.ITEM -> "ITEMS"
        }

        graphics.drawString(
            font,
            Component.literal(headerText),
            x + 12,
            y + (headerHeight - 8) / 2,
            headerColor,
            false
        )

        val countText = "(${entries.size})"
        val countWidth = font.width(countText)
        graphics.drawString(
            font,
            Component.literal(countText),
            x + width - countWidth - 4,
            y + (headerHeight - 8) / 2,
            0xFF808080.toInt(),
            false
        )
    }

    fun renderEntries(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, visibleHeight: Int, hoveredId: String?, selectedId: String?, scale: Float = 1f) {
        if (isCollapsed) return
        
        val rowHeight = getRowHeight(scale)
        val startRow = (scrollOffset / rowHeight).coerceAtLeast(0)
        val endRow = minOf(entries.size, startRow + (visibleHeight / rowHeight) + 2)

        for (i in startRow until endRow) {
            val entry = entries[i]
            val entryY = y + i * rowHeight - scrollOffset

            if (entryY + rowHeight < y || entryY > y + visibleHeight) continue

            entry.render(graphics, font, x, entryY, width, isHovered = entry.id == hoveredId, isSelected = entry.id == selectedId, scale)
        }
    }

    fun getEntryAtPosition(localY: Int, scale: Float = 1f): ShulkerListRow? {
        if (isCollapsed) return null
        val scaledRowHeight = (BASE_ROW_HEIGHT * scale).toInt()
        val rowIndex = (localY + scrollOffset) / scaledRowHeight
        return if (rowIndex in entries.indices) entries[rowIndex] else null
    }
}