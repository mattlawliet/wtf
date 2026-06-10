package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

class ShulkerSection(
    val type: ShulkerSectionType,
    val entries: List<ShulkerListRow>
) {
    companion object {
        const val HEADER_HEIGHT = 14
        const val ROW_HEIGHT = 17
    }

    var scrollOffset = 0
    var isCollapsed = false

    fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) scrollOffset = 0
    }

    fun getTotalContentHeight() = entries.size * ROW_HEIGHT

    fun scroll(delta: Int) {
        if (isCollapsed) return
        val maxScroll = maxOf(0, entries.size * ROW_HEIGHT - 100)
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxScroll)
    }

    fun resetScroll() {
        scrollOffset = 0
    }

    fun renderHeader(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int) {
        val headerColor = when (type) {
            ShulkerSectionType.BLOCK -> 0xFFFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0xFF55FF55.toInt()
            ShulkerSectionType.ITEM -> 0xFFFFAA55.toInt()
            ShulkerSectionType.EXTERNAL_INV -> 0xFF55FFFF.toInt()
        }

        graphics.fill(x, y, x + width, y + HEADER_HEIGHT, 0xFF2A2A2A.toInt())

        val collapseArrow = if (isCollapsed) ">" else "v"
        graphics.text(font, collapseArrow, x + 2, y + (HEADER_HEIGHT - 8) / 2, 0xFFFFFFFF.toInt(), false)

        val headerText = when (type) {
            ShulkerSectionType.BLOCK -> "BLOCKS"
            ShulkerSectionType.INVENTORY -> "INVENTORY"
            ShulkerSectionType.ITEM -> "ITEMS"
            ShulkerSectionType.EXTERNAL_INV -> "EXTERNAL INVENTORIES"
        }

        graphics.text(
            font,
            Component.literal(headerText),
            x + 12,
            y + (HEADER_HEIGHT - 8) / 2,
            headerColor,
            false
        )

        val countText = "(${entries.size})"
        val countWidth = font.width(countText)
        graphics.text(
            font,
            Component.literal(countText),
            x + width - countWidth - 4,
            y + (HEADER_HEIGHT - 8) / 2,
            0xFF808080.toInt(),
            false
        )
    }

    fun renderEntries(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, visibleHeight: Int, hoveredId: String?, selectedId: String?) {
        if (isCollapsed) return
        
        val startRow = (scrollOffset / ROW_HEIGHT).coerceAtLeast(0)
        val endRow = minOf(entries.size, startRow + (visibleHeight / ROW_HEIGHT) + 2)

        for (i in startRow until endRow) {
            val entry = entries[i]
            val entryY = y + i * ROW_HEIGHT - scrollOffset

            if (entryY + ROW_HEIGHT < y || entryY > y + visibleHeight) continue

            entry.render(graphics, font, x, entryY, width, isHovered = entry.id == hoveredId, isSelected = entry.id == selectedId)
        }
    }

    fun getEntryAtPosition(localY: Int): ShulkerListRow? {
        if (isCollapsed) return null
        val rowIndex = (localY + scrollOffset) / ROW_HEIGHT
        return if (rowIndex in entries.indices) entries[rowIndex] else null
    }
}