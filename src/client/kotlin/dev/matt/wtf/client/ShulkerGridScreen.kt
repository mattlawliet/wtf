package dev.matt.wtf.client

import net.minecraft.client.Minecraft
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

    private val basePanelWidth = 200
    private val baseSearchHeight = 30
    private val baseHeaderHeight = 20
    private val baseRowHeight = 24

    private val leftPanelWidth get() = (basePanelWidth * guiScale).toInt()
    private val searchHeight get() = (baseSearchHeight * guiScale).toInt()
    private val sectionHeaderHeight get() = (baseHeaderHeight * guiScale).toInt()
    private val rowHeight get() = (baseRowHeight * guiScale).toInt()
    private val guiScale: Float
        get() {
            val mc = Minecraft.getInstance()
            val options = mc.options
            return try {
                val method = options.javaClass.getMethod("getGuiScale")
                (method.invoke(options) as? Int)?.toFloat()?.coerceAtLeast(1f) ?: 1f
            } catch (e: Exception) {
                1f
            }
        }

    override fun init() {
        val field = EditBox(font, (10 * guiScale).toInt(), (5 * guiScale).toInt(),
            (width - 20 * guiScale).toInt(), searchHeight,
            Component.literal("Search shulkers and contents..."))
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
        graphics.fill(0, searchHeight, leftPanelWidth, height, 0xFF1A1A1A.toInt())

        var currentY = searchHeight

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            section.renderHeader(graphics, font, 0, currentY, leftPanelWidth, guiScale)
            currentY += sectionHeaderHeight

            val sectionContentHeight = height - currentY
            section.renderEntries(graphics, font, 0, currentY, leftPanelWidth, sectionContentHeight, hoveredId, selectedId, guiScale)

            currentY += sectionHeaderHeight
        }

        hoveredId = null
        var sectionTop = searchHeight + sectionHeaderHeight
        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue
            val sectionEnd = sectionTop + sectionHeaderHeight + section.getTotalContentHeight(guiScale)
            if (mouseY >= sectionTop && mouseY < sectionEnd && mouseX < leftPanelWidth) {
                val localY = mouseY - sectionTop - sectionHeaderHeight
                if (localY >= 0) {
                    val entry = section.getEntryAtPosition(localY)
                    if (entry != null) {
                        hoveredId = entry.id
                    }
                }
                break
            }
            sectionTop = sectionEnd
        }
    }

    private fun renderPreviewPanel(graphics: GuiGraphics) {
        val previewX = leftPanelWidth + (10 * guiScale).toInt()
        val previewY = searchHeight + (10 * guiScale).toInt()
        val previewWidth = width - previewX - (10 * guiScale).toInt()

        graphics.fill(previewX - (5 * guiScale).toInt(), previewY - (5 * guiScale).toInt(),
            width - (5 * guiScale).toInt(), height - (5 * guiScale).toInt(), 0xFF2A2A2A.toInt())

        val selected = allEntries.find { it.id == selectedId }
        if (selected == null) {
            val hint = Component.literal("Hover a shulker to preview contents")
            graphics.drawString(font, hint, previewX, previewY + (100 * guiScale).toInt(), 0xFF808080.toInt(), true)
            return
        }

        val items = selected.items.ifEmpty {
            WTFClient.deserializeNbtToItems(selected.cachedContentsNbt)
        }

        val cellSize = (32 * guiScale).toInt()
        val cellPadding = (3 * guiScale).toInt()
        val gridStartX = previewX + (previewWidth - 9 * (cellSize + cellPadding)) / 2
        val gridStartY = previewY + (20 * guiScale).toInt()

        val query = searchField?.value ?: ""
        val highlightIndices = if (query.isNotEmpty()) {
            findMatchingItemIndices(query, items)
        } else emptySet()

        for (i in 0 until 27) {
            val col = i % 9
            val row = i / 9
            val x = gridStartX + col * (cellSize + cellPadding)
            val y = gridStartY + row * (cellSize + cellPadding)

            if (i in highlightIndices) {
                graphics.fill(x - 2, y - 2, x + cellSize + 2, y + cellSize + 2, 0x8800FF00.toInt())
            }

            graphics.fill(x, y, x + cellSize, y + cellSize, 0xFF3A3A3A.toInt())

            val item = items.getOrNull(i)
            if (item != null && !item.isEmpty) {
                val itemOffset = ((cellSize - 16) / 2).coerceAtLeast(0)
                graphics.renderItem(item, x + itemOffset, y + itemOffset)
            }
        }

        val nameY = gridStartY + 3 * (cellSize + cellPadding) + (15 * guiScale).toInt()
        val nameColor = when (selected.section) {
            "block" -> 0xFFFFAA00.toInt()
            "inv" -> 0xFF55FF55.toInt()
            "transit" -> 0xFFFFAA55.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        graphics.drawString(font, selected.name, previewX, nameY, nameColor, false)

        val matchPercent = selected.matchPercent
        if (matchPercent > 0f && query.isNotEmpty()) {
            val percentText = "${(matchPercent * 100).toInt()}% match"
            graphics.drawString(font, Component.literal(percentText), previewX, nameY + (12 * guiScale).toInt(), 0xFF808080.toInt(), false)
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
        graphics.drawString(font, Component.literal(detailText), previewX, nameY + (24 * guiScale).toInt(), 0xFF808080.toInt(), false)
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