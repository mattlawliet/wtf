package dev.matt.wtf.client

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ShulkerGridScreen(private val allEntries: List<ShulkerEntry>) : Screen(Component.literal("Happy Shulkers")) {
    private var searchField: EditBox? = null
    private val sections = mutableMapOf<ShulkerSectionType, ShulkerSection>()
    private var selectedId: String? = null
    private var hoveredId: String? = null

    private val leftPanelWidth = 200
    private val searchHeight = 30
    private val sectionHeaderHeight = 20
    private val rowHeight = 24

    override fun init() {
        val field = EditBox(font, 10, 5, width - 20, searchHeight, Component.literal("Search shulkers and contents..."))
        field.isFocused = true
        addRenderableWidget(field)
        setFocused(field)
        searchField = field

        rebuildSections("")
    }

    private fun rebuildSections(query: String) {
        sections.clear()

        val blockEntries = mutableListOf<ShulkerListRow>()
        val invEntries = mutableListOf<ShulkerListRow>()
        val transitEntries = mutableListOf<ShulkerListRow>()

        for (entry in allEntries) {
            val sectionType = when (entry.section) {
                "block" -> ShulkerSectionType.BLOCK
                "inv" -> ShulkerSectionType.INVENTORY
                "transit" -> ShulkerSectionType.TRANSIT
                else -> continue
            }

            val rows = when (sectionType) {
                ShulkerSectionType.BLOCK -> blockEntries
                ShulkerSectionType.INVENTORY -> invEntries
                ShulkerSectionType.TRANSIT -> transitEntries
            }

            val items = entry.items.ifEmpty {
                WTFClient.deserializeNbtToItems(entry.cachedContentsNbt)
            }
            val matchPercent = if (query.isEmpty()) 1f else fuzzyMatchPercent(query, entry.name.string, items)
            if (matchPercent > 0f || query.isEmpty()) {
                rows.add(ShulkerListRow(
                    id = entry.id,
                    name = entry.name,
                    stack = entry.stack,
                    section = sectionType,
                    matchPercent = matchPercent
                ))
            }
        }

        if (blockEntries.isNotEmpty()) {
            sections[ShulkerSectionType.BLOCK] = ShulkerSection(ShulkerSectionType.BLOCK, blockEntries)
        }
        if (invEntries.isNotEmpty()) {
            sections[ShulkerSectionType.INVENTORY] = ShulkerSection(ShulkerSectionType.INVENTORY, invEntries)
        }
        if (transitEntries.isNotEmpty()) {
            sections[ShulkerSectionType.TRANSIT] = ShulkerSection(ShulkerSectionType.TRANSIT, transitEntries)
        }

        if (selectedId == null && sections.values.any { it.entries.isNotEmpty() }) {
            selectedId = sections.values.firstNotNullOfOrNull { section -> section.entries.firstOrNull()?.id }
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)

        renderLeftPanel(graphics, mouseX, mouseY)
        renderPreviewPanel(graphics)
    }

    private fun renderLeftPanel(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        graphics.fill(0, searchHeight, leftPanelWidth, height, 0x1A1A1A1A.toInt())

        var currentY = searchHeight
        val contentTop = searchHeight + sectionHeaderHeight

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            section.renderHeader(graphics, font, 0, currentY, leftPanelWidth)
            currentY += sectionHeaderHeight

            section.renderEntries(graphics, font, 0, currentY, leftPanelWidth, height - currentY, hoveredId, selectedId)

            currentY += sectionHeaderHeight + section.getTotalContentHeight()
        }

        hoveredId = null
        val relY = mouseY - contentTop
        if (mouseX in 0..leftPanelWidth && mouseY >= contentTop && relY >= 0) {
            var sectionTop = searchHeight + sectionHeaderHeight
            for (type in ShulkerSectionType.entries) {
                val section = sections[type] ?: continue
                val sectionEnd = sectionTop + section.getTotalContentHeight()
                if (mouseY < sectionEnd) {
                    val entry = section.getEntryAtPosition(mouseY - sectionTop)
                    if (entry != null) {
                        hoveredId = entry.id
                    }
                    break
                }
                sectionTop = sectionEnd + sectionHeaderHeight
            }
        }
    }

    private fun renderPreviewPanel(graphics: GuiGraphics) {
        val previewX = leftPanelWidth + 10
        val previewY = searchHeight + 10
        val previewWidth = width - previewX - 10

        graphics.fill(previewX - 5, previewY - 5, width - 5, height - 5, 0x2A2A2A2A.toInt())

        val selected = allEntries.find { it.id == selectedId }
        if (selected == null) {
            val hint = Component.literal("Hover a shulker to preview contents")
            graphics.drawString(font, hint, previewX, previewY + 100, 0x808080.toInt(), true)
            return
        }

        val items = selected.items.ifEmpty {
            WTFClient.deserializeNbtToItems(selected.cachedContentsNbt)
        }

        val gridCellSize = 24
        val gridPadding = 2
        val gridStartX = previewX + (previewWidth - 9 * (gridCellSize + gridPadding)) / 2
        val gridStartY = previewY + 20

        val query = searchField?.value ?: ""
        val highlightIndices = if (query.isNotEmpty()) {
            findMatchingItemIndices(query, items)
        } else emptySet()

        for (i in 0 until 27) {
            val col = i % 9
            val row = i / 9
            val x = gridStartX + col * (gridCellSize + gridPadding)
            val y = gridStartY + row * (gridCellSize + gridPadding)

            val isHighlighted = i in highlightIndices

            if (isHighlighted) {
                graphics.fill(x - 2, y - 2, x + gridCellSize + 2, y + gridCellSize + 2, 0x8800FF00.toInt())
            }

            graphics.fill(x, y, x + gridCellSize, y + gridCellSize, 0x3A3A3A3A.toInt())

            val item = items.getOrNull(i)
            if (item != null && !item.isEmpty) {
                graphics.renderItem(item, x + 4, y + 4)
            }
        }

        val nameY = gridStartY + 3 * (gridCellSize + gridPadding) + 15
        val nameColor = when (selected.section) {
            "block" -> 0xFFAA00.toInt()
            "inv" -> 0x55FF55.toInt()
            "transit" -> 0xFFAA55.toInt()
            else -> 0xFFFFFF.toInt()
        }
        graphics.drawString(font, selected.name, previewX, nameY, nameColor, false)

        val matchPercent = selected.matchPercent
        if (matchPercent > 0f && query.isNotEmpty()) {
            val percentText = "${(matchPercent * 100).toInt()}% match"
            graphics.drawString(font, Component.literal(percentText), previewX, nameY + 12, 0x808080.toInt(), false)
        }

        val detailText = when (selected.section) {
            "block" -> {
                val loc = selected.location
                if (loc.contains(':')) {
                    val dim = loc.substringBeforeLast(':')
                    val coords = loc.substringAfterLast(':').replace("_", ", ")
                    "$dim at $coords"
                } else loc
            }
            "inv" -> slotLabel(selected.location)
            "transit" -> "In Transit"
            else -> ""
        }
        graphics.drawString(font, Component.literal(detailText), previewX, nameY + 24, 0x808080.toInt(), false)
    }

    private fun findMatchingItemIndices(query: String, items: List<ItemStack>): Set<Int> {
        val indices = mutableSetOf<Int>()
        for (i in items.indices) {
            val item = items[i]
            if (!item.isEmpty) {
                val itemName = item.displayName.string
                if (fuzzyMatch(query, itemName)) {
                    indices.add(i)
                }
            }
        }
        return indices
    }

    private fun fuzzyMatchPercent(query: String, name: String, items: List<ItemStack>): Float {
        if (query.isEmpty()) return 1f

        val nameMatch = fuzzyMatchScore(query, name)
        var itemMatch = 0f
        for (item in items) {
            if (!item.isEmpty) {
                val score = fuzzyMatchScore(query, item.displayName.string)
                if (score > itemMatch) itemMatch = score
            }
        }

        return maxOf(nameMatch, itemMatch)
    }

    private fun fuzzyMatchScore(query: String, text: String): Float {
        if (query.isEmpty()) return 1f
        val q = query.lowercase()
        val t = text.lowercase()
        if (t.contains(q)) return 1f

        var qi = 0
        var matched = 0
        for (ti in t.indices) {
            if (qi < q.length && t[ti] == q[qi]) {
                qi++
                matched++
            }
        }
        return if (qi == q.length) matched.toFloat() / t.length else 0f
    }

    override fun isPauseScreen() = true
}