package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class ShulkerListScreen(private val entries: List<ShulkerEntry>) : Screen(Component.literal("Happy Shulkers")) {
    private var searchField: EditBox? = null
    private var scrollOffset = 0
    private var scrollAccum = 0.0
    private val entryHeight = 30
    private val listStartY = 40

    override fun init() {
        val field = EditBox(font, width / 2 - 100, 8, 200, 20, Component.literal("Search shulkers..."))
        field.isFocused = true
        addRenderableWidget(field)
        setFocused(field)
        searchField = field
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)

        val query = searchField?.value ?: ""
        val filtered = if (query.isEmpty()) entries else entries.filter { fuzzyMatch(query, it.name.string) }

        val maxVisible = (height - listStartY) / entryHeight
        if (scrollOffset > filtered.size - maxVisible) {
            scrollOffset = maxOf(0, filtered.size - maxVisible)
        }

        for (i in 0 until minOf(filtered.size, maxVisible)) {
            val entry = filtered[i + scrollOffset]
            val y = listStartY + i * entryHeight

            graphics.item(entry.stack, width / 2 - 100, y)
            graphics.text(font, entry.name, width / 2 - 78, y + 4, -1, false)
            val detail = when (entry.section) {
                "inv" -> slotLabel(entry.location)
                "block" -> {
                    val dim = entry.location.substringBeforeLast(':')
                    val coords = entry.location.substringAfterLast(':')
                    "At $coords  [$dim]"
                }
                "item" -> {
                    if (entry.location.contains(',')) {
                        "§6Dropped near ${entry.location}§r"
                    } else {
                        "§6In Transit (Missing)§r"
                    }
                }
                "enderchest" -> entry.location
                "ex-inv" -> {
                    if (entry.lastKnown) entry.location else "In chest: ${entry.location}"
                }
                else -> "Unknown"
            }
            graphics.text(font, Component.literal("#${entry.shortHash}:${entry.serial}  $detail"), width / 2 - 78, y + 14, 0xFF808080.toInt(), false)
        }

        if (filtered.isEmpty()) {
            val hint = if (query.isEmpty())
                Component.literal("No happy shulkers found")
            else
                Component.literal("No matching shulkers")
            graphics.text(font, hint, width / 2 - font.width(hint) / 2, height / 2, 0xFF808080.toInt(), false)
        }

        // Cover the header zone so list items don't visually bleed over the search field.
        graphics.fill(0, 0, width, listStartY, 0xFF000000.toInt())
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        scrollAccum += scrollY
        val steps = scrollAccum.toInt()
        if (steps != 0) {
            scrollAccum -= steps
            val query = searchField?.value ?: ""
            val filteredSize = if (query.isEmpty()) entries.size else entries.count { fuzzyMatch(query, it.name.string) }
            val maxVisible = (height - listStartY) / entryHeight
            scrollOffset = (scrollOffset - steps).coerceIn(0, maxOf(0, filteredSize - maxVisible))
        }
        return true
    }

    override fun isPauseScreen() = true
}
