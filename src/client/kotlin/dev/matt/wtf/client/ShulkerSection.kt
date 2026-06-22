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
        const val INDICATOR_HEIGHT = 10
    }

    var scrollOffset = 0
    var isCollapsed = false
    // Set every renderEntries() call to the section's actual allotted height
    // this frame (it's dynamic - shrinks/grows with panel size and how many
    // other sections are expanded). scroll()'s clamp used to assume a fixed
    // 100px, which didn't match the real visible area and could let you
    // scroll past where entries stop fully filling it, hiding rows that
    // should still be on screen.
    private var lastVisibleHeight = 100

    fun toggleCollapse() {
        isCollapsed = !isCollapsed
        if (isCollapsed) scrollOffset = 0
    }

    fun getTotalContentHeight() = entries.size * ROW_HEIGHT

    // Mirrors renderEntries()'s actual end-state instead of a naive
    // entries.size*ROW_HEIGHT - visibleHeight, which ignored the top
    // indicator bar's 10px reservation once scrolled away from row 0 - that
    // shortfall made the clamp stick one row short of the real end, only
    // escapable by something else (e.g. a click-driven nudge) landing on a
    // scrollOffset value the broken math happened to allow.
    fun scroll(delta: Int) {
        if (isCollapsed) return
        val fitsWithoutScrolling = entries.size * ROW_HEIGHT <= lastVisibleHeight
        val maxScroll = if (fitsWithoutScrolling) {
            0
        } else {
            val rowsVisibleAtEnd = maxOf(1, (lastVisibleHeight - INDICATOR_HEIGHT) / ROW_HEIGHT)
            maxOf(0, entries.size - rowsVisibleAtEnd) * ROW_HEIGHT
        }
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
            ShulkerSectionType.ENDERCHEST -> 0xFFAA55FF.toInt()
        }

        graphics.fill(x, y, x + width, y + HEADER_HEIGHT, 0xFF2A2A2A.toInt())

        val collapseArrow = if (isCollapsed) ">" else "v"
        graphics.text(font, collapseArrow, x + 2, y + (HEADER_HEIGHT - 8) / 2, 0xFFFFFFFF.toInt(), false)

        val headerText = when (type) {
            ShulkerSectionType.BLOCK -> "PLACED IN WORLD"
            ShulkerSectionType.INVENTORY -> "PLAYER INVENTORY"
            ShulkerSectionType.ITEM -> "DROPPED"
            ShulkerSectionType.EXTERNAL_INV -> "EXTERNAL INVENTORY"
            ShulkerSectionType.ENDERCHEST -> "ENDER CHEST"
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
        lastVisibleHeight = visibleHeight

        val indicatorH = INDICATOR_HEIGHT
        val startRow = (scrollOffset / ROW_HEIGHT).coerceAtLeast(0)
        val showTop = startRow > 0

        // Reserve indicator space so entries don't overlap the indicator bars.
        val contentY = if (showTop) y + indicatorH else y
        val bottomBound = y + visibleHeight

        // Count fully visible rows in the adjusted content zone.
        val availableForEntries = bottomBound - contentY
        val maxVisibleRows = availableForEntries / ROW_HEIGHT
        val endRow = minOf(entries.size, startRow + maxVisibleRows)
        val hiddenBelow = entries.size - endRow
        val showBottom = hiddenBelow > 0
        // When showing the bottom indicator, lose one row to make room.
        val adjustedEndRow = if (showBottom) maxOf(startRow, endRow - 1) else endRow

        for (i in startRow until adjustedEndRow) {
            val entryY = contentY + (i - startRow) * ROW_HEIGHT
            entries[i].render(graphics, font, x, entryY, width, isHovered = entries[i].id == hoveredId, isSelected = entries[i].id == selectedId)
        }

        if (showTop) {
            graphics.fill(x, y, x + width, y + indicatorH, 0xFF222222.toInt())
            val label = "↑ $startRow hidden"
            graphics.text(font, label, x + width / 2 - font.width(label) / 2, y + 1, 0xFF888888.toInt(), false)
        }
        if (showBottom) {
            val barY = bottomBound - indicatorH
            graphics.fill(x, barY, x + width, bottomBound, 0xFF222222.toInt())
            val label = "↓ $hiddenBelow hidden"
            graphics.text(font, label, x + width / 2 - font.width(label) / 2, barY + 1, 0xFF888888.toInt(), false)
        }
    }

    // Must mirror renderEntries() exactly: startRow snaps to whole rows (raw
    // scrollOffset can land mid-row from mouse-wheel deltas that aren't a
    // clean multiple of ROW_HEIGHT), and the top "N hidden" indicator bar
    // shifts the first visible row down by indicatorH px. Using raw
    // localY+scrollOffset here (without either adjustment) used to drift out
    // of sync with what's actually drawn, showing up as hover/click landing
    // on the wrong row once you'd scrolled even slightly off a row boundary.
    fun getEntryAtPosition(localY: Int): ShulkerListRow? {
        if (isCollapsed) return null
        val indicatorH = INDICATOR_HEIGHT
        val startRow = (scrollOffset / ROW_HEIGHT).coerceAtLeast(0)
        val showTop = startRow > 0
        val adjustedLocalY = if (showTop) localY - indicatorH else localY
        if (adjustedLocalY < 0) return null
        val rowIndex = startRow + adjustedLocalY / ROW_HEIGHT
        return if (rowIndex in entries.indices) entries[rowIndex] else null
    }
}