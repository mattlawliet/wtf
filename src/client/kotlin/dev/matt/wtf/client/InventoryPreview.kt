package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

class InventoryPreview(
    private val items: List<ItemStack>,
    private val highlightIndices: Set<Int> = emptySet()
) {
    private val cellSize = 18
    private val padding = 1
    private val gridWidth = 9
    private val gridHeight = 3

    val totalWidth get() = gridWidth * (cellSize + padding) + padding
    val totalHeight get() = gridHeight * (cellSize + padding) + padding

    fun render(graphics: GuiGraphics, startX: Int, startY: Int) {
        for (i in 0 until 27) {
            val col = i % 9
            val row = i / 9
            val x = startX + padding + col * (cellSize + padding)
            val y = startY + padding + row * (cellSize + padding)

            val isHighlighted = i in highlightIndices

            if (isHighlighted) {
                graphics.fill(x - 1, y - 1, x + cellSize + 1, y + cellSize + 1, 0xFF00FF00.toInt())
            }

            graphics.fill(x, y, x + cellSize, y + cellSize, 0x1A1A1A1A.toInt())

            if (!items.getOrNull(i)?.isEmpty!!) {
                graphics.renderItem(items[i], x, y)
            }
        }
    }
}