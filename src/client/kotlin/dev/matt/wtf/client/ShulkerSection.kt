package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component

class ShulkerSection(
    val type: ShulkerSectionType,
    val entries: List<ShulkerListRow>
) {
    private val headerHeight = 20
    var scrollOffset = 0

    companion object {
        val ROW_HEIGHT = 24
    }

    fun getHeaderHeight() = headerHeight

    fun getTotalContentHeight() = entries.size * ROW_HEIGHT

    fun scroll(delta: Int) {
        val maxScroll = maxOf(0, entries.size * ROW_HEIGHT - 200)
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxScroll)
    }

    fun resetScroll() {
        scrollOffset = 0
    }

    fun renderHeader(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int) {
        val headerColor = when (type) {
            ShulkerSectionType.BLOCK -> 0xFFAA00.toInt()
            ShulkerSectionType.INVENTORY -> 0x55FF55.toInt()
            ShulkerSectionType.TRANSIT -> 0xFFAA55.toInt()
        }

        graphics.fill(x, y, x + width, y + headerHeight, 0x2A2A2A2A.toInt())

        val headerText = when (type) {
            ShulkerSectionType.BLOCK -> "BLOCKS"
            ShulkerSectionType.INVENTORY -> "INVENTORY"
            ShulkerSectionType.TRANSIT -> "TRANSIT"
        }

        graphics.drawString(
            font,
            Component.literal(headerText),
            x + 4,
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
            0x808080.toInt(),
            false
        )
    }

    fun renderEntries(graphics: GuiGraphics, font: Font, x: Int, y: Int, width: Int, visibleHeight: Int, hoveredId: String?, selectedId: String?) {
        val startRow = (scrollOffset / ROW_HEIGHT).coerceAtLeast(0)
        val endRow = minOf(entries.size, startRow + (visibleHeight / ROW_HEIGHT) + 1)

        for (i in startRow until endRow) {
            val entry = entries[i]
            val entryY = y + i * ROW_HEIGHT - scrollOffset

            if (entryY + ROW_HEIGHT < y || entryY > y + visibleHeight) continue

            entry.render(graphics, font, x, entryY, width, isHovered = entry.id == hoveredId, isSelected = entry.id == selectedId)
        }
    }

    fun getEntryAtPosition(localY: Int): ShulkerListRow? {
        val rowIndex = (localY + scrollOffset) / ROW_HEIGHT
        return if (rowIndex in entries.indices) entries[rowIndex] else null
    }
}