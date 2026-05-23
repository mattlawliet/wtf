package dev.matt.wtf.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

class ShulkerGridScreen(private val allEntries: List<ShulkerEntry>) : Screen(Component.literal("Happy Shulkers")) {
    private var searchField: EditBox? = null
    private val sections = mutableMapOf<ShulkerSectionType, ShulkerSection>()
    private val previewItemsById = mutableMapOf<String, List<ItemStack>>()
    private var selectedId: String? = null
    private var hoveredId: String? = null
    private var searchQuery = ""

    private val leftPanelWidth: Int
        get() = (width * 0.22f).toInt().coerceIn(130, 220)

    private val searchHeight = 20

    override fun init() {
        val field = EditBox(font, 10, 5, width - 20, searchHeight,
            Component.literal("Search shulkers..."))
        field.setResponder { text ->
            searchQuery = text
            rebuildSections(text)
        }
        field.isFocused = true
        addRenderableWidget(field)
        setFocused(field)
        searchField = field

        rebuildSections("")
    }

    private fun rebuildSections(query: String) {
        sections.clear()
        previewItemsById.clear()

        val blockEntries = mutableListOf<ShulkerListRow>()
        val invEntries = mutableListOf<ShulkerListRow>()
        val itemEntries = mutableListOf<ShulkerListRow>()
        val exInvEntries = mutableListOf<ShulkerListRow>()

        for (entry in allEntries) {
            val sectionType = when (entry.section) {
                "block" -> ShulkerSectionType.BLOCK
                "inv" -> ShulkerSectionType.INVENTORY
                "item" -> ShulkerSectionType.ITEM
                "ex-inv" -> ShulkerSectionType.EXTERNAL_INV
                else -> continue
            }

            val rows = when (sectionType) {
                ShulkerSectionType.BLOCK -> blockEntries
                ShulkerSectionType.INVENTORY -> invEntries
                ShulkerSectionType.ITEM -> itemEntries
                ShulkerSectionType.EXTERNAL_INV -> exInvEntries
            }

            val items = entry.items.takeIf { it.size == 27 }
                ?: entry.cachedContentsNbt?.let(WTFClient::deserializeNbtToItems)
                ?: List(27) { ItemStack.EMPTY }
            previewItemsById[entry.id] = items
            val matchPercent = if (query.isEmpty()) 1f else fuzzyMatchPercent(query, entry.name.string, items)
            if (matchPercent > 0f || query.isEmpty()) {
                rows.add(ShulkerListRow(
                    id = entry.id,
                    name = entry.name,
                    stack = entry.stack,
                    section = sectionType,
                    matchPercent = matchPercent,
                    lastKnown = entry.lastKnown,
                    location = entry.location
                ))
            }
        }

        if (blockEntries.isNotEmpty()) {
            sections[ShulkerSectionType.BLOCK] = ShulkerSection(ShulkerSectionType.BLOCK, blockEntries)
        }
        if (invEntries.isNotEmpty()) {
            sections[ShulkerSectionType.INVENTORY] = ShulkerSection(ShulkerSectionType.INVENTORY, invEntries)
        }
        if (itemEntries.isNotEmpty()) {
            sections[ShulkerSectionType.ITEM] = ShulkerSection(ShulkerSectionType.ITEM, itemEntries)
        }
        if (exInvEntries.isNotEmpty()) {
            sections[ShulkerSectionType.EXTERNAL_INV] = ShulkerSection(ShulkerSectionType.EXTERNAL_INV, exInvEntries)
        }

        if (selectedId == null && sections.values.any { it.entries.isNotEmpty() }) {
            selectedId = sections.values.firstNotNullOfOrNull { section -> section.entries.firstOrNull()?.id }
        } else if (selectedId != null && !allEntries.any { it.id == selectedId }) {
            selectedId = sections.values.firstNotNullOfOrNull { section -> section.entries.firstOrNull()?.id }
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(graphics, mouseX, mouseY, delta)

        renderLeftPanel(graphics, mouseX, mouseY)
        renderPreviewPanel(graphics)
    }

    private fun renderLeftPanel(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        val panelY = searchHeight + 5
        val panelHeight = height - panelY - 5
        
        graphics.fill(0, panelY, leftPanelWidth, panelY + panelHeight, 0xFF1A1A1A.toInt())

        var currentY = panelY

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            section.renderHeader(graphics, font, 0, currentY, leftPanelWidth)
            currentY += ShulkerSection.HEADER_HEIGHT

            if (!section.isCollapsed) {
                val availableHeight = height - currentY - 5
                if (availableHeight > 0) {
                    section.renderEntries(graphics, font, 0, currentY, leftPanelWidth, availableHeight, hoveredId, selectedId)
                    currentY += minOf(section.getTotalContentHeight(), availableHeight)
                }
            }

            if (currentY >= panelY + panelHeight) break
        }

        hoveredId = null
        var hoverY = panelY

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            if (mouseX in 0..leftPanelWidth && mouseY >= hoverY && mouseY < hoverY + ShulkerSection.HEADER_HEIGHT) {
                break
            }
            hoverY += ShulkerSection.HEADER_HEIGHT

            if (!section.isCollapsed) {
                val contentHeight = section.getTotalContentHeight()
                if (mouseX in 0..leftPanelWidth && mouseY >= hoverY && mouseY < hoverY + contentHeight) {
                    val localY = mouseY - hoverY
                    val entry = section.getEntryAtPosition(localY)
                    if (entry != null) {
                        hoveredId = entry.id
                    }
                    break
                }
                hoverY += contentHeight
            }

            if (hoverY >= panelY + panelHeight) break
        }
    }

    private fun renderPreviewPanel(graphics: GuiGraphics) {
        val panelY = searchHeight + 5
        val panelHeight = height - panelY - 5
        
        val previewX = leftPanelWidth + 7
        val previewWidth = width - previewX - 7

        graphics.fill(previewX - 3, panelY, width - 3, panelY + panelHeight, 0xFF2A2A2A.toInt())

        val selected = allEntries.find { it.id == selectedId }
        if (selected == null) {
            val hint = Component.literal("Select a shulker")
            graphics.drawString(font, hint, previewX, panelY + 30, 0xFF808080.toInt(), true)
            return
        }

        val items = previewItemsById[selected.id]
            ?: selected.items.takeIf { it.size == 27 }
            ?: selected.cachedContentsNbt?.let(WTFClient::deserializeNbtToItems)
            ?: List(27) { ItemStack.EMPTY }

        val cellSize = 22
        val cellPadding = 2
        val gridStartX = previewX + (previewWidth - 9 * (cellSize + cellPadding)) / 2
        val gridStartY = panelY + 10

        for (i in 0 until 27) {
            val col = i % 9
            val row = i / 9
            val x = gridStartX + col * (cellSize + cellPadding)
            val y = gridStartY + row * (cellSize + cellPadding)

            graphics.fill(x, y, x + cellSize, y + cellSize, 0xFF3A3A3A.toInt())

            val item = items.getOrNull(i)
            if (item != null && !item.isEmpty) {
                val itemOffset = ((cellSize - 16) / 2).coerceAtLeast(0)
                graphics.renderItem(item, x + itemOffset, y + itemOffset)
                graphics.renderItemDecorations(font, item, x + itemOffset, y + itemOffset)
            }
        }

        val nameY = gridStartY + 3 * (cellSize + cellPadding) + 10
        val nameColor = when (selected.section) {
            "block" -> 0xFFFFAA00.toInt()
            "inv" -> 0xFF55FF55.toInt()
            "item" -> 0xFFFFAA55.toInt()
            "ex-inv" -> 0xFF55FFFF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        graphics.drawString(font, selected.name, previewX, nameY, nameColor, false)

        val detailText = when (selected.section) {
            "block" -> {
                val loc = selected.location
                if (loc.contains(':')) {
                    val dim = loc.substringBeforeLast(':')
                    val coords = loc.substringAfterLast(':')
                    "$dim at $coords"
                } else loc
            }
            "inv" -> slotLabel(selected.location)
            "item" -> "In Transit: ${selected.location}"
            "ex-inv" -> {
                val rawLoc = selected.location
                if (selected.lastKnown) {
                    rawLoc // Already has §c[Last Known]§7 prefix from resolveHappyShulkers
                } else {
                    val loc = rawLoc
                    if (loc.contains(':')) {
                        val dim = loc.substringBeforeLast(':')
                        val coords = loc.substringAfterLast(':')
                        "$dim at $coords"
                    } else loc
                }
            }
            else -> ""
        }
        graphics.drawString(font, Component.literal(detailText), previewX, nameY + 12, 0xFF808080.toInt(), false)

        val itemCount = items.count { !it.isEmpty }
        val debugStatus = when {
            selected.cachedContentsNbt == null -> "items=$itemCount cached=NULL"
            selected.cachedContentsNbt.isEmpty() -> "items=$itemCount cached=EMPTY"
            else -> "items=$itemCount cached=${selected.cachedContentsNbt.size}B"
        }
        graphics.drawString(font, Component.literal(debugStatus), previewX, nameY + 24, 0xFFFFFF00.toInt(), false)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = mouseButtonEvent.x
        val mouseY = mouseButtonEvent.y
        val button = mouseButtonEvent.button()
        
        if (button == 0 && mouseX < leftPanelWidth) {
            val panelY = searchHeight + 5
            var currentY = panelY

            for (type in ShulkerSectionType.entries) {
                val section = sections[type] ?: continue

                if (mouseY >= currentY && mouseY < currentY + ShulkerSection.HEADER_HEIGHT) {
                    section.toggleCollapse()
                    return true
                }
                currentY += ShulkerSection.HEADER_HEIGHT

                if (!section.isCollapsed) {
                    val contentHeight = section.getTotalContentHeight()
                    if (mouseY >= currentY && mouseY < currentY + contentHeight) {
                        val localY = (mouseY - currentY).toInt()
                        val entry = section.getEntryAtPosition(localY)
                        if (entry != null) {
                            selectedId = entry.id
                            return true
                        }
                    }
                    currentY += contentHeight
                }

                if (currentY >= height) break
            }
        }
        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseX < leftPanelWidth) {
            var currentY = ShulkerSection.HEADER_HEIGHT

            for (type in ShulkerSectionType.entries) {
                val section = sections[type] ?: continue
                val sectionEnd = currentY + section.getTotalContentHeight()
                if (mouseY >= currentY && mouseY < sectionEnd) {
                    section.scroll((-verticalAmount).toInt())
                    return true
                }
                currentY = sectionEnd
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
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