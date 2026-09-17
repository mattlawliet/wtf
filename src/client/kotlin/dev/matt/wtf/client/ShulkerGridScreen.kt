package dev.matt.wtf.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

class ShulkerGridScreen(entries: List<ShulkerEntry>) : Screen(Component.literal("Happy Shulkers")) {
    private val allEntries = entries.toMutableList()
    private var searchField: EditBox? = null
    // The keybinding press that opens this screen still has a pending GLFW
    // char callback in flight when the search field grabs focus - without
    // this, that same keystroke types itself straight into the search box
    // the instant the list opens, silently filtering out every box that
    // doesn't happen to contain that character. A short window instead of
    // a single swallowed event, since holding the key a bit longer than a
    // quick tap can fire more than one char callback before release.
    private val suppressCharsUntil = System.currentTimeMillis() + 150
    private val sections = mutableMapOf<ShulkerSectionType, ShulkerSection>()
    private var sectionHeights = mapOf<ShulkerSection, Int>()
    private val previewItemsById = mutableMapOf<String, List<ItemStack>>()
    // Lowercased item display names per entry, computed once — building display
    // names is too costly to redo on every search keystroke.
    private val searchNamesById = mutableMapOf<String, List<String>>()
    // Tag paths of everything inside each box ("ores", "logs", "planks"), kept
    // OUT of searchNamesById on purpose: that one is a concatenated blob scored
    // by fuzzyMatchPercent as query.length / span, and appending a dozen tags per
    // item would stretch those spans and quietly change what MIN_MATCH means.
    // These are matched by prefix under a "#" instead, so free-text ranking is
    // exactly what it was.
    private val searchTagsById = mutableMapOf<String, Set<String>>()
    private var selectedId: String? = null
    private var hoveredId: String? = null
    private var searchQuery = ""
    private var pendingRemoveId: String? = null
    private var removeButtonBounds: IntArray? = null
    private var locateButtonBounds: IntArray? = null
    private var waypointButtonBounds: IntArray? = null
    private var percentButtonBounds: IntArray? = null
    private var blurButtonBounds: IntArray? = null
    private var glowButtonBounds: IntArray? = null
    private var clearAllButtonBounds: IntArray? = null
    private var pendingClearAll = false
    private var refreshTicks = 0
    private var lastSignature: String? = null
    private var infoBounds: IntArray? = null
    private var showInfo = false

    private val leftPanelWidth: Int
        get() = (width * 0.22f).toInt().coerceIn(130, 220)

    // A subsequence hit below this is noise - the query's letters happening to
    // appear in order, spread across a name that has nothing to do with it.
    // Substring hits score 1f and are never affected by this.
    //
    // 0.5 rather than 0.6 so a vowel-dropped abbreviation still lands: "dmnd"
    // on "diamond" spans 7 characters for 4, which is 0.57. The nearest noise
    // in testing was 0.27, so there is room under it.
    private val MIN_MATCH = 0.5f

    private val searchHeight = 20
    private val topBarButtonWidth = 70
    private val REFRESH_INTERVAL_TICKS = 20

    override fun init() {
        // glowX = width-285; leave 5px gap on left and right of search bar.
        // Debug mode adds a 4th top-bar button (Clear All) further left of
        // glow, which the fixed width-295 never accounted for - shrink by
        // one more button+gap so they don't end up touching.
        val debugButtonReserve = if (WTFClient.isDebugModeEnabled()) topBarButtonWidth + 5 else 0
        // Clamped: leftPanelWidth is, but this wasn't, so a narrow window or a
        // large GUI scale handed EditBox a negative width.
        val searchWidth = (width - 295 - debugButtonReserve).coerceAtLeast(40)
        val field = EditBox(font, 5, 5, searchWidth, searchHeight,
            Component.literal("Search shulkers..."))
        field.setResponder { text ->
            searchQuery = text
            rebuildSections(text)
            // After rebuild, so the tag cache is populated by the query that just
            // ran - typing "#" alone matches everything, which fills it.
            field.setSuggestion(completionFor(text))
        }
        field.isFocused = true
        addRenderableWidget(field)
        setFocused(field)
        searchField = field

        val iconButton = net.minecraft.client.gui.components.Button.builder(
            Component.literal(WTFClient.getMarkerIcon())
        ) { btn ->
            btn.setMessage(Component.literal(WTFClient.cycleMarkerIcon()))
        }
            .pos(width - 55, 5)
            .size(20, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Marker icon")))
            .build()
        addRenderableWidget(iconButton)

        fun colorSwatch(rgb: Int) = Component.literal("■")
            .withStyle { it.withColor(net.minecraft.network.chat.TextColor.fromRgb(rgb and 0xFFFFFF)) }

        val colorButton = net.minecraft.client.gui.components.Button.builder(
            colorSwatch(WTFClient.getMarkerColor())
        ) { btn ->
            WTFClient.cycleMarkerColor()
            btn.setMessage(colorSwatch(WTFClient.getMarkerColor()))
        }
            .pos(width - 30, 5)
            .size(20, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Marker color")))
            .build()
        addRenderableWidget(colorButton)

        rebuildSections("")
    }

    // The list was a snapshot taken at open: a box that moved while you were
    // reading kept showing its old location until you reopened the screen.
    // Re-resolve on a 1s cadence and only rebuild when something actually
    // changed, carrying scroll position and collapsed state across the rebuild
    // so the list doesn't jump under the cursor.
    override fun tick() {
        if (++refreshTicks < REFRESH_INTERVAL_TICKS) return
        refreshTicks = 0

        val fresh = WTFClient.resolveHappyShulkers()
        val signature = fresh.joinToString("|") { "${it.id}\u0000${it.section}\u0000${it.location}\u0000${it.lastKnown}" }
        if (signature == lastSignature) return
        lastSignature = signature

        val scrollByType = sections.mapValues { it.value.scrollOffset }
        val collapsedTypes = sections.filterValues { it.isCollapsed }.keys.toSet()

        allEntries.clear()
        allEntries.addAll(fresh)
        val liveIds = fresh.mapTo(mutableSetOf()) { it.id }
        previewItemsById.keys.retainAll(liveIds)
        searchNamesById.keys.retainAll(liveIds)
        searchTagsById.keys.retainAll(liveIds)
        if (selectedId !in liveIds) selectedId = null

        rebuildSections(searchQuery)

        for ((type, section) in sections) {
            scrollByType[type]?.let { section.scrollOffset = it }
            if (type in collapsedTypes) section.isCollapsed = true
        }
    }

    // Tags come from the registry sync, so before the server has sent them this
    // is simply empty and "#ores" finds nothing - which is the right way to fail.
    private fun tagPathsOf(items: List<ItemStack>): Set<String> {
        val out = mutableSetOf<String>()
        for (stack in items) {
            if (stack.isEmpty) continue
            stack.item.builtInRegistryHolder().tags().forEach { out.add(it.location().getPath()) }
        }
        return out
    }

    // The tail of the best candidate, which EditBox draws grey after the cursor.
    // Prefix, never fuzzy: ghost text has to continue what you typed, and a
    // completion that doesn't is indistinguishable from a bug. Item names are
    // deliberately not candidates - there are hundreds and suggesting one per
    // keystroke reads as noise; box names and tags are few and worth guessing at.
    private fun completionFor(text: String): String {
        if (text.isEmpty()) return ""
        val q = text.lowercase()
        val pool: Sequence<String> = if (text.startsWith("#")) {
            searchTagsById.values.asSequence().flatten().distinct().map { "#$it" }
        } else {
            allEntries.asSequence().map { it.name.string }
        }
        return pool
            .filter { it.length > text.length && it.lowercase().startsWith(q) }
            .minByOrNull { it.length }
            ?.substring(text.length)
            ?: ""
    }

    override fun keyPressed(event: net.minecraft.client.input.KeyEvent): Boolean {
        // Tab accepts the suggestion, the way chat's does. Only when there is one
        // to accept, so tab keeps its normal behaviour otherwise.
        val field = searchField
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB && field != null && field.isFocused) {
            val completion = completionFor(field.value)
            if (completion.isNotEmpty()) {
                field.value = field.value + completion
                return true
            }
        }
        return super.keyPressed(event)
    }

    private fun rebuildSections(query: String) {
        val tagQuery = if (query.startsWith("#")) query.removePrefix("#").lowercase() else null
        sections.clear()

        val blockEntries = mutableListOf<ShulkerListRow>()
        val invEntries = mutableListOf<ShulkerListRow>()
        val itemEntries = mutableListOf<ShulkerListRow>()
        val exInvEntries = mutableListOf<ShulkerListRow>()
        val enderChestEntries = mutableListOf<ShulkerListRow>()

        for (entry in allEntries) {
            val sectionType = when (entry.section) {
                "block" -> ShulkerSectionType.BLOCK
                "inv" -> ShulkerSectionType.INVENTORY
                "item" -> ShulkerSectionType.ITEM
                "ex-inv" -> ShulkerSectionType.EXTERNAL_INV
                "enderchest" -> ShulkerSectionType.ENDERCHEST
                else -> continue
            }

            val rows = when (sectionType) {
                ShulkerSectionType.BLOCK -> blockEntries
                ShulkerSectionType.INVENTORY -> invEntries
                ShulkerSectionType.ITEM -> itemEntries
                ShulkerSectionType.EXTERNAL_INV -> exInvEntries
                ShulkerSectionType.ENDERCHEST -> enderChestEntries
            }

            val items = previewItemsById.getOrPut(entry.id) {
                entry.items.takeIf { it.size == SHULKER_SLOTS }
                    ?: entry.cachedContentsNbt?.let(WTFClient::deserializeNbtToItems)
                    ?: List(SHULKER_SLOTS) { ItemStack.EMPTY }
            }
            val matchPercent = when {
                query.isEmpty() -> 0f
                // "#ores" - what is INSIDE the box, by tag. "#" alone matches
                // everything, which is what makes the completion useful: nobody
                // can guess tag names, so the suggestion has to come before the
                // first real keystroke.
                tagQuery != null -> {
                    val tags = searchTagsById.getOrPut(entry.id) { tagPathsOf(items) }
                    if (tagQuery.isEmpty() || tags.any { it.startsWith(tagQuery) }) 1f else 0f
                }
                else -> {
                    val itemNames = searchNamesById.getOrPut(entry.id) {
                        items.mapNotNull {
                            if (it.isEmpty) null else {
                                val id = BuiltInRegistries.ITEM.getKey(it.item).toString()
                                "${it.displayName.string.lowercase()} $id ${id.removePrefix("minecraft:")}"
                            }
                        }
                    }
                    fuzzyMatchPercent(query, entry.name.string, itemNames)
                }
            }
            if (matchPercent >= MIN_MATCH || query.isEmpty()) {
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

        if (query.isNotEmpty()) {
            val byMatchThenName = compareByDescending<ShulkerListRow> { it.matchPercent }
                .thenBy { it.name.string.lowercase() }
            blockEntries.sortWith(byMatchThenName)
            invEntries.sortWith(byMatchThenName)
            itemEntries.sortWith(byMatchThenName)
            exInvEntries.sortWith(byMatchThenName)
            enderChestEntries.sortWith(byMatchThenName)
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
        if (enderChestEntries.isNotEmpty()) {
            sections[ShulkerSectionType.ENDERCHEST] = ShulkerSection(ShulkerSectionType.ENDERCHEST, enderChestEntries)
        }

        val allSectionIds = sections.values.flatMap { s -> s.entries.map { it.id } }.toSet()
        if (selectedId !in allSectionIds) {
            selectedId = sections.values.firstNotNullOfOrNull { section -> section.entries.firstOrNull()?.id }
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, delta)

        renderLeftPanel(graphics, mouseX, mouseY)
        renderPreviewPanel(graphics, mouseX, mouseY)
        renderPreviewGridTooltip(graphics, mouseX, mouseY)
        renderTopBarButtons(graphics, mouseX, mouseY)
        renderInfoButton(graphics, mouseX, mouseY)
        if (showInfo) renderInfoOverlay(graphics)
    }

    private fun renderTopBarButtons(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val btnHeight = searchHeight
        val percentOn = WTFClient.isShowMatchPercentEnabled()
        val blurOn = WTFClient.isPreviewBlurEnabled()
        val glowOn = WTFClient.isItemGlowEnabled()

        val blurX = width - 135
        val percentX = blurX - 5 - topBarButtonWidth
        val glowX = percentX - 5 - topBarButtonWidth

        glowButtonBounds = intArrayOf(glowX, 5, glowX + topBarButtonWidth, 5 + btnHeight)
        percentButtonBounds = intArrayOf(percentX, 5, percentX + topBarButtonWidth, 5 + btnHeight)
        blurButtonBounds = intArrayOf(blurX, 5, blurX + topBarButtonWidth, 5 + btnHeight)

        renderToggleButton(graphics, glowButtonBounds!!, "Item Glow", glowOn, mouseX, mouseY)
        renderToggleButton(graphics, percentButtonBounds!!, "Match %", percentOn, mouseX, mouseY)
        renderToggleButton(graphics, blurButtonBounds!!, "Blur BG", blurOn, mouseX, mouseY)

        if (WTFClient.isDebugModeEnabled()) {
            val clearX = glowX - 5 - topBarButtonWidth
            clearAllButtonBounds = intArrayOf(clearX, 5, clearX + topBarButtonWidth, 5 + btnHeight)
            val label = if (pendingClearAll) "Confirm?" else "Clear all"
            renderDangerButton(graphics, clearAllButtonBounds!!, label, mouseX, mouseY)
        } else {
            clearAllButtonBounds = null
            pendingClearAll = false
        }
    }

    private fun renderDangerButton(graphics: GuiGraphicsExtractor, bounds: IntArray, label: String, mouseX: Int, mouseY: Int) {
        val (x0, y0, x1, y1) = bounds
        val isHovered = mouseX in x0..x1 && mouseY in y0..y1
        val bgColor = if (isHovered) 0xFFCC2222.toInt() else 0xFF992222.toInt()
        graphics.fill(x0, y0, x1, y1, bgColor)
        val textWidth = font.width(label)
        graphics.text(font, Component.literal(label), x0 + (x1 - x0 - textWidth) / 2, y0 + (y1 - y0 - 8) / 2, 0xFFFFFFFF.toInt(), false)
    }

    private fun renderToggleButton(graphics: GuiGraphicsExtractor, bounds: IntArray, label: String, on: Boolean, mouseX: Int, mouseY: Int) {
        val (x0, y0, x1, y1) = bounds
        val isHovered = mouseX in x0..x1 && mouseY in y0..y1
        val bgColor = when {
            on && isHovered -> 0xFF3A6A3A.toInt()
            on -> 0xFF2A5A2A.toInt()
            isHovered -> 0xFF4A4A4A.toInt()
            else -> 0xFF3A3A3A.toInt()
        }
        graphics.fill(x0, y0, x1, y1, bgColor)
        val text = "$label: ${if (on) "On" else "Off"}"
        val textWidth = font.width(text)
        graphics.text(font, Component.literal(text), x0 + (x1 - x0 - textWidth) / 2, y0 + (y1 - y0 - 8) / 2, 0xFFFFFFFF.toInt(), false)
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    private fun renderLeftPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panelY = searchHeight + 10
        val panelHeight = height - panelY - 5

        graphics.fill(0, panelY, leftPanelWidth, panelY + panelHeight, 0xFF1A1A1A.toInt())

        // Title bar
        val titleBarH = 14
        graphics.fill(0, panelY, leftPanelWidth, panelY + titleBarH, 0xFF252525.toInt())
        val title = Component.literal("Shulker List")
        val titleW = font.width(title)
        graphics.text(font, title, (leftPanelWidth - titleW) / 2, panelY + 3, 0xFFCCCCCC.toInt(), false)

        // Divide available content space among expanded sections so all section
        // headers stay visible. Sections needing less than their share give
        // surplus back to sections that need more (one redistribution pass).
        val activeSections = ShulkerSectionType.entries.mapNotNull { sections[it] }
        val totalHeaderH = activeSections.size * ShulkerSection.HEADER_HEIGHT
        val totalContentSpace = (panelHeight - titleBarH - totalHeaderH).coerceAtLeast(0)
        val expandedSections = activeSections.filter { !it.isCollapsed }
        sectionHeights = run {
            if (expandedSections.isEmpty()) return@run emptyMap()
            val budgetPerSection = totalContentSpace / expandedSections.size
            val heights = expandedSections.associateWith { minOf(it.getTotalContentHeight(), budgetPerSection) }.toMutableMap()
            val surplus = totalContentSpace - heights.values.sum()
            if (surplus > 0) {
                val needMore = heights.entries.filter { (s, h) -> h < s.getTotalContentHeight() }
                if (needMore.isNotEmpty()) {
                    val bonus = surplus / needMore.size
                    needMore.forEach { (s, h) -> heights[s] = minOf(s.getTotalContentHeight(), h + bonus) }
                }
            }
            heights
        }

        var currentY = panelY + titleBarH

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            section.renderHeader(graphics, font, 0, currentY, leftPanelWidth)
            currentY += ShulkerSection.HEADER_HEIGHT

            if (!section.isCollapsed) {
                val sectionH = sectionHeights[section] ?: 0
                if (sectionH > 0) {
                    // Clip each section to its own band. This used to be handled
                    // by re-drawing every header in a second pass to cover the
                    // bleed-through, which computed and submitted all the header
                    // geometry twice per frame.
                    graphics.enableScissor(0, currentY, leftPanelWidth, currentY + sectionH)
                    section.renderEntries(graphics, font, 0, currentY, leftPanelWidth, sectionH, hoveredId, selectedId)
                    graphics.disableScissor()
                    currentY += sectionH
                }
            }

            if (currentY >= panelY + panelHeight) break
        }

        hoveredId = null
        var hoverY = panelY + 14 // skip title bar

        for (type in ShulkerSectionType.entries) {
            val section = sections[type] ?: continue

            if (mouseX in 0..leftPanelWidth && mouseY >= hoverY && mouseY < hoverY + ShulkerSection.HEADER_HEIGHT) {
                break
            }
            hoverY += ShulkerSection.HEADER_HEIGHT

            if (!section.isCollapsed) {
                val contentHeight = sectionHeights[section] ?: 0
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

    private fun renderPreviewPanel(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val panelY = searchHeight + 10
        val panelHeight = height - panelY - 5
        
        val previewX = leftPanelWidth + 7
        val previewWidth = width - previewX - 7

        val blurred = WTFClient.isPreviewBlurEnabled()
        if (!blurred) {
            graphics.fill(previewX - 3, panelY, width - 3, panelY + panelHeight, 0xFF2A2A2A.toInt())
        }

        val selected = allEntries.find { it.id == selectedId }
        if (selected == null) {
            val keyName = WTFClient.getToggleHappyKeyDisplayName()
            val emptyLines = if (allEntries.isEmpty()) {
                if (keyName != null) listOf(
                    "§fNo marked shulkers yet.",
                    "",
                    "§7Hold a shulker box and press §e$keyName§7 to mark it.",
                    "§7Also works on placed shulker blocks you're looking at.",
                    "",
                    "§7Open any chest or inventory to track contents.",
                    "",
                    "§7See §f?§7 (bottom-right) for more help."
                ) else listOf(
                    "§fNo marked shulkers yet.",
                    "",
                    "§7Bind §ekey.wtf.toggle_happy§7 in Controls,",
                    "§7then hold a shulker and press it to mark.",
                    "",
                    "§7See §f?§7 (bottom-right) for more help."
                )
            } else listOf("§7Select a shulker from the list.")
            val lineH = font.lineHeight + 3
            val totalH = emptyLines.size * lineH
            val startY = panelY + (panelHeight - totalH) / 2
            if (blurred) {
                graphics.fill(previewX - 3, startY - 8, width - 3, startY + totalH + 8, 0xFF2A2A2A.toInt())
            }
            emptyLines.forEachIndexed { i, line ->
                val comp = Component.literal(line)
                val lw = font.width(comp)
                graphics.text(font, comp, previewX + (previewWidth - lw) / 2, startY + i * lineH, 0xFF888888.toInt(), false)
            }
            removeButtonBounds = null
            return
        }

        val items = previewItemsById[selected.id]
            ?: selected.items.takeIf { it.size == SHULKER_SLOTS }
            ?: selected.cachedContentsNbt?.let(WTFClient::deserializeNbtToItems)
            ?: List(SHULKER_SLOTS) { ItemStack.EMPTY }

        val cellSize = 22
        val cellPadding = 2
        val gridStartX = previewX + (previewWidth - 9 * (cellSize + cellPadding)) / 2
        // total content height: 3 grid rows + gap + info/button block
        val contentHeight = 3 * (cellSize + cellPadding) + 10 + 52
        val gridStartY = (panelY + (panelHeight - contentHeight) / 2).coerceAtLeast(panelY + 10)

        val q = searchQuery.lowercase()
        val previewTagQuery = if (q.startsWith("#")) q.removePrefix("#") else null
        val matchingSlots: Set<Int> = when {
            // A tag query has to highlight by tag. Comparing "#enchantables"
            // against item NAMES matched nothing, so the row came back with no
            // green slots and looked like it had been picked at random - the box
            // did contain something enchantable, and nothing said which.
            previewTagQuery != null -> if (previewTagQuery.isEmpty()) emptySet() else
                (0 until SHULKER_SLOTS).filter { i ->
                    val stack = items.getOrNull(i) ?: return@filter false
                    !stack.isEmpty && stack.item.builtInRegistryHolder().tags()
                        .anyMatch { it.location().getPath().startsWith(previewTagQuery) }
                }.toSet()
            q.isNotEmpty() -> {
                val allSlotNames = items.map { stack ->
                    if (stack.isEmpty) null else {
                        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                        "${stack.displayName.string.lowercase()} $id ${id.removePrefix("minecraft:")}"
                    }
                }
                (0 until SHULKER_SLOTS).filter { i ->
                    val name = allSlotNames.getOrNull(i) ?: return@filter false
                    fuzzyMatchScore(q, name) >= MIN_MATCH
                }.toSet()
            }
            else -> emptySet()
        }

        for (i in 0 until SHULKER_SLOTS) {
            val col = i % 9
            val row = i / 9
            val x = gridStartX + col * (cellSize + cellPadding)
            val y = gridStartY + row * (cellSize + cellPadding)

            val slotBg = if (i in matchingSlots) 0xFF2A4A2A.toInt() else 0xFF3A3A3A.toInt()
            graphics.fill(x, y, x + cellSize, y + cellSize, slotBg)

            val item = items.getOrNull(i)
            if (item != null && !item.isEmpty) {
                val itemOffset = ((cellSize - 16) / 2).coerceAtLeast(0)
                graphics.item(item, x + itemOffset, y + itemOffset)
                graphics.itemDecorations(font, item, x + itemOffset, y + itemOffset)
            }
        }

        val infoX = gridStartX
        val nameY = gridStartY + 3 * (cellSize + cellPadding) + 10
        val nameColor = when (selected.section) {
            "block" -> 0xFFFFAA00.toInt()
            "inv" -> 0xFF55FF55.toInt()
            "item" -> 0xFFFFAA55.toInt()
            "ex-inv" -> 0xFF55FFFF.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

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
            "enderchest" -> selected.location
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

        val statusParts = mutableListOf<String>()
        if (WTFClient.isDebugModeEnabled()) {
            if (selected.from.isNotEmpty()) {
                statusParts.add("from ${selected.from}")
            }
            val lastLocation = selected.lastLocation
            if (!lastLocation.isNullOrEmpty()) {
                statusParts.add("last ${compactLocation(lastLocation)}")
            }
        }
        if (statusParts.isEmpty()) {
            val itemCount = items.count { !it.isEmpty }
            statusParts.add("items $itemCount")
        }
        val statusText = font.plainSubstrByWidth(statusParts.joinToString(" | "), previewWidth)

        // Remove record button - second click confirms
        val btnX = infoX
        val btnY = nameY + 38
        val btnLabel = if (pendingRemoveId == selected.id) "Click again to confirm removal" else "Remove record"
        val btnWidth = font.width(btnLabel) + 10
        val btnHeight = 14

        // Locate button - points HUD compass / blinks overlay or slot for this entry
        val locateBtnY = btnY + btnHeight + 4
        val locateLabel = if (WTFClient.getLocateTarget()?.uuid == selected.id) "Un-locate" else "Locate"
        val locateBtnWidth = font.width(locateLabel) + 10

        // Add Waypoint button - only when Xaero's Minimap is installed, sits
        // below Locate so the panel grows by one more row instead of crowding.
        val xaeroPresent = WTFClient.isXaeroPresent()
        val waypointBtnY = locateBtnY + btnHeight + 4
        val waypointLabel = "Add Waypoint"
        val waypointBtnWidth = font.width(waypointLabel) + 10
        val panelBottom = (if (xaeroPresent) waypointBtnY else locateBtnY) + btnHeight + 4

        val infoWidth = maxOf(font.width(selected.name.string), font.width(detailText), font.width(statusText), btnWidth, if (xaeroPresent) waypointBtnWidth else 0)
        if (blurred) {
            graphics.fill(infoX - 3, nameY - 4, infoX + infoWidth + 3, panelBottom, 0xFF2A2A2A.toInt())
        }

        graphics.text(font, selected.name, infoX, nameY, nameColor, false)
        graphics.text(font, Component.literal(detailText), infoX, nameY + 12, 0xFF808080.toInt(), false)
        graphics.text(font, Component.literal(statusText), infoX, nameY + 24, 0xFFFFFF00.toInt(), false)

        removeButtonBounds = intArrayOf(btnX, btnY, btnX + btnWidth, btnY + btnHeight)
        val isHovered = mouseX in btnX..(btnX + btnWidth) && mouseY in btnY..(btnY + btnHeight)
        val bgColor = if (pendingRemoveId == selected.id) {
            if (isHovered) 0xFFCC2222.toInt() else 0xFF992222.toInt()
        } else {
            if (isHovered) 0xFF4A4A4A.toInt() else 0xFF3A3A3A.toInt()
        }
        graphics.fill(btnX, btnY, btnX + btnWidth, btnY + btnHeight, bgColor)
        graphics.text(font, Component.literal(btnLabel), btnX + 5, btnY + 3, 0xFFFFFFFF.toInt(), false)

        locateButtonBounds = intArrayOf(btnX, locateBtnY, btnX + locateBtnWidth, locateBtnY + btnHeight)
        val locateHovered = mouseX in btnX..(btnX + locateBtnWidth) && mouseY in locateBtnY..(locateBtnY + btnHeight)
        graphics.fill(btnX, locateBtnY, btnX + locateBtnWidth, locateBtnY + btnHeight, if (locateHovered) 0xFF3A6A3A.toInt() else 0xFF2A4A2A.toInt())
        graphics.text(font, Component.literal(locateLabel), btnX + 5, locateBtnY + 3, 0xFFFFFFFF.toInt(), false)

        if (xaeroPresent) {
            waypointButtonBounds = intArrayOf(btnX, waypointBtnY, btnX + waypointBtnWidth, waypointBtnY + btnHeight)
            val waypointHovered = mouseX in btnX..(btnX + waypointBtnWidth) && mouseY in waypointBtnY..(waypointBtnY + btnHeight)
            graphics.fill(btnX, waypointBtnY, btnX + waypointBtnWidth, waypointBtnY + btnHeight, if (waypointHovered) 0xFF3A4A6A.toInt() else 0xFF2A3A4A.toInt())
            graphics.text(font, Component.literal(waypointLabel), btnX + 5, waypointBtnY + 3, 0xFFFFFFFF.toInt(), false)
        } else {
            waypointButtonBounds = null
        }
    }

    private fun compactLocation(location: String): String {
        val cleaned = location.replace("§c[Last Known]§7 ", "")
        if (!cleaned.contains(':')) return cleaned
        val dim = cleaned.substringBeforeLast(':').substringAfterLast(':')
        val coords = cleaned.substringAfterLast(':').replace('_', ',')
        return "$dim $coords"
    }

    private fun renderPreviewGridTooltip(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val selected = allEntries.find { it.id == selectedId } ?: return
        val items = previewItemsById[selected.id]
            ?: selected.items.takeIf { it.size == SHULKER_SLOTS }
            ?: selected.cachedContentsNbt?.let(WTFClient::deserializeNbtToItems)
            ?: return

        val panelY = searchHeight + 10
        val previewX = leftPanelWidth + 7
        val previewWidth = width - previewX - 7

        val cellSize = 22
        val cellPadding = 2
        val gridStartX = previewX + (previewWidth - 9 * (cellSize + cellPadding)) / 2
        val panelHeight = height - panelY - 5
        val contentHeight = 3 * (cellSize + cellPadding) + 10 + 52
        val gridStartY = (panelY + (panelHeight - contentHeight) / 2).coerceAtLeast(panelY + 10)

        val gridWidth = 9 * (cellSize + cellPadding)
        val gridHeight = 3 * (cellSize + cellPadding)
        if (mouseX < gridStartX || mouseX >= gridStartX + gridWidth ||
            mouseY < gridStartY || mouseY >= gridStartY + gridHeight) return

        val col = (mouseX - gridStartX) / (cellSize + cellPadding)
        val row = (mouseY - gridStartY) / (cellSize + cellPadding)
        if (col !in 0..8 || row !in 0..2) return

        val slotIndex = row * 9 + col
        val item = items.getOrNull(slotIndex) ?: return
        if (item.isEmpty) return

        graphics.setTooltipForNextFrame(font, item, mouseX, mouseY)
    }

    override fun charTyped(event: net.minecraft.client.input.CharacterEvent): Boolean {
        if (System.currentTimeMillis() < suppressCharsUntil) return true
        return super.charTyped(event)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val mouseX = mouseButtonEvent.x
        val mouseY = mouseButtonEvent.y
        val button = mouseButtonEvent.button()

        if (button == 0) {
            val gBounds = glowButtonBounds
            if (gBounds != null && mouseX >= gBounds[0] && mouseX < gBounds[2] && mouseY >= gBounds[1] && mouseY < gBounds[3]) {
                WTFClient.setItemGlowEnabled(!WTFClient.isItemGlowEnabled())
                return true
            }
            val pBounds = percentButtonBounds
            if (pBounds != null && mouseX >= pBounds[0] && mouseX < pBounds[2] && mouseY >= pBounds[1] && mouseY < pBounds[3]) {
                WTFClient.setShowMatchPercentEnabled(!WTFClient.isShowMatchPercentEnabled())
                return true
            }
            val bBounds = blurButtonBounds
            if (bBounds != null && mouseX >= bBounds[0] && mouseX < bBounds[2] && mouseY >= bBounds[1] && mouseY < bBounds[3]) {
                WTFClient.setPreviewBlurEnabled(!WTFClient.isPreviewBlurEnabled())
                return true
            }
            val cBounds = clearAllButtonBounds
            if (cBounds != null && mouseX >= cBounds[0] && mouseX < cBounds[2] && mouseY >= cBounds[1] && mouseY < cBounds[3]) {
                if (pendingClearAll) {
                    WTFClient.clearAllRecords()
                    onClose()
                } else {
                    pendingClearAll = true
                }
                return true
            }
        }

        if (button == 0) {
            val iBounds = infoBounds
            if (iBounds != null && mouseX >= iBounds[0] && mouseX < iBounds[2] && mouseY >= iBounds[1] && mouseY < iBounds[3]) {
                showInfo = !showInfo
                return true
            }
            if (showInfo) { showInfo = false; return true }
        }

        if (button == 0 && mouseX >= leftPanelWidth) {
            val lBounds = locateButtonBounds
            val locateSelected = allEntries.find { it.id == selectedId }
            if (lBounds != null && locateSelected != null &&
                mouseX >= lBounds[0] && mouseX < lBounds[2] && mouseY >= lBounds[1] && mouseY < lBounds[3]
            ) {
                WTFClient.locateEntry(locateSelected)
                return true
            }
        }

        if (button == 0 && mouseX >= leftPanelWidth) {
            val wBounds = waypointButtonBounds
            val waypointSelected = allEntries.find { it.id == selectedId }
            if (wBounds != null && waypointSelected != null &&
                mouseX >= wBounds[0] && mouseX < wBounds[2] && mouseY >= wBounds[1] && mouseY < wBounds[3]
            ) {
                WTFClient.addXaeroWaypoint(waypointSelected)
                return true
            }
        }

        if (button == 0 && mouseX >= leftPanelWidth) {
            val bounds = removeButtonBounds
            val selected = allEntries.find { it.id == selectedId }
            if (bounds != null && selected != null &&
                mouseX >= bounds[0] && mouseX < bounds[2] && mouseY >= bounds[1] && mouseY < bounds[3]
            ) {
                if (pendingRemoveId == selected.id) {
                    WTFClient.removeTrackedShulker(selected.id)
                    allEntries.removeAll { it.id == selected.id }
                    previewItemsById.remove(selected.id)
                    searchNamesById.remove(selected.id)
                    pendingRemoveId = null
                    selectedId = null
                    removeButtonBounds = null
                    rebuildSections(searchQuery)
                } else {
                    pendingRemoveId = selected.id
                }
                return true
            }
        }

        if (button == 0 && mouseX < leftPanelWidth) {
            val panelY = searchHeight + 10 + 14
            var currentY = panelY

            for (type in ShulkerSectionType.entries) {
                val section = sections[type] ?: continue

                if (mouseY >= currentY && mouseY < currentY + ShulkerSection.HEADER_HEIGHT) {
                    section.toggleCollapse()
                    return true
                }
                currentY += ShulkerSection.HEADER_HEIGHT

                if (!section.isCollapsed) {
                    // Must be the section's actual allotted screen space
                    // (sectionHeights, the dynamic per-frame budget render
                    // uses), not its full unclamped content height - using
                    // the latter sized this section's click region as if it
                    // owned way more screen than it was actually given,
                    // swallowing clicks meant for whatever section renders
                    // next in that space.
                    val contentHeight = sectionHeights[section] ?: 0
                    if (mouseY >= currentY && mouseY < currentY + contentHeight) {
                        val localY = (mouseY - currentY).toInt()
                        val entry = section.getEntryAtPosition(localY)
                        if (entry != null) {
                            selectedId = entry.id
                            pendingRemoveId = null
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
            // Must start from the same baseline as the hover/render code
            // (panelY + 14 title bar, not an arbitrary HEADER_HEIGHT*2) - the
            // old base offset drifted further out of sync with each section
            // accumulated, eventually pushing the last section/rows' true Y
            // range outside what this check thought it was, silently eating
            // scroll input right where it mattered most (the bottom of a list).
            val panelY = searchHeight + 10
            var currentY = panelY + 14

            for (type in ShulkerSectionType.entries) {
                val section = sections[type] ?: continue
                currentY += ShulkerSection.HEADER_HEIGHT
                if (!section.isCollapsed) {
                    val h = sectionHeights[section] ?: 0
                    if (mouseY >= currentY && mouseY < currentY + h) {
                        section.scroll((-verticalAmount * ShulkerSection.ROW_HEIGHT).toInt())
                        return true
                    }
                    currentY += h
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    private fun fuzzyMatchPercent(query: String, name: String, itemNames: List<String>): Float {
        val q = query.lowercase()
        var best = fuzzyMatchScore(q, name.lowercase())
        for (itemName in itemNames) {
            val score = fuzzyMatchScore(q, itemName)
            if (score > best) best = score
        }
        return best
    }

    // Both arguments must already be lowercased.
    private fun fuzzyMatchScore(q: String, t: String): Float {
        if (q.isEmpty()) return 1f
        if (t.contains(q)) return 1f

        // Score by how tightly the match sits, not by how much of the name it
        // covers. matched/t.length punished long names for being long, so a
        // real hit scored low in "purple shulker box" while four letters picked
        // out of it in order scored about the same - and anything above zero
        // was listed. Span is how far the match had to stretch to collect the
        // query: a tight run scores near 1, a scattered one falls off fast.
        //
        // ponytail: greedy leftmost matching, so the span is an upper bound,
        // not the tightest possible one. Both are the same for the hits that
        // matter here (a typo or a missing letter). Only reach for a real
        // Smith-Waterman pass if a legitimate search starts scoring under
        // MIN_MATCH.
        var qi = 0
        var first = -1
        var last = -1
        for (ti in t.indices) {
            if (qi < q.length && t[ti] == q[qi]) {
                if (first < 0) first = ti
                last = ti
                qi++
            }
        }
        if (qi < q.length) return 0f
        return q.length.toFloat() / (last - first + 1)
    }

    private fun renderInfoButton(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val bw = 18
        val bh = 14
        val bx = width - bw - 4
        val by = height - bh - 4
        infoBounds = intArrayOf(bx, by, bx + bw, by + bh)
        val hovered = mouseX in bx..(bx + bw) && mouseY in by..(by + bh)
        val bg = if (showInfo || hovered) 0xFF555555.toInt() else 0xFF333333.toInt()
        graphics.fill(bx, by, bx + bw, by + bh, bg)
        val label = Component.literal("?")
        graphics.text(font, label, bx + (bw - font.width(label)) / 2, by + 3, 0xFFCCCCCC.toInt(), false)
    }

    private fun renderInfoOverlay(graphics: GuiGraphicsExtractor) {
        val toggleKey = WTFClient.getToggleHappyKeyDisplayName()
        // Read against toggleHappyForHoveredSlot() on 2026-09-15 (ticket 002).
        // The three gestures listed are the three branches that function has;
        // it had promised a fourth (look at a placed block) that was never
        // built and fails silently, and it had never mentioned the stack
        // refusal, the [LK] prefix, or what cyan really means.
        val lines = listOf(
            "§eWTF Shulker Tracker §7— How to use",
            "",
            "§f1. Mark a shulker box",
            "   §7Press §f${toggleKey ?: "§c[key not set — see Controls]§7"}§7 with the box §funder your cursor§7 in any inventory,",
            "   §7with it in your §fmain hand§7 and no screen open, or while the box's",
            "   §7own screen is §fopen§7 — which is how you mark a placed one.",
            "   §7One box at a time: a stack of two or more is refused.",
            "   §7Press the same key again to unmark it.",
            "",
            "§f2. Let the mod learn its contents",
            "   §7Open the shulker (place it if needed) so the mod can scan the items inside.",
            "   §7Also works when the box is in a chest — just open that chest.",
            "",
            "§f3. Use the list",
            "   §7This screen shows all marked shulkers grouped by location:",
            "   §f  Orange §7= placed block  §f  Green §7= your inventory",
            "   §f  Cyan   §7= left your inventory  §f  Purple §7= ender chest",
            "   §f  Orange (item) §7= on the ground",
            "   §7Cyan covers both: sitting in a chest, and §clost track of§7.",
            "   §7A §c[LK]§7 row is the last place it was seen, not a sighting now.",
            "   §7Click a row to preview its contents. Search by name or item.",
            "   §7Matching items highlight §agreen§7 in the preview grid.",
            "",
            "§fTop bar toggles:",
            "   §fItem Glow §7— glowing outline on ground shulker items",
            "   §fMatch %   §7— show content match score next to each row",
            "   §fBlur BG   §7— blur the background behind the preview panel",
        )
        val padH = 10
        val padV = 8
        val lineH = font.lineHeight + 2
        val textW = lines.maxOf { font.width(Component.translatable(it).string.replace(Regex("§."), "")) }
            .coerceAtLeast(lines.maxOf { font.width(it.replace(Regex("§."), "")) })
        val boxW = textW + padH * 2
        val boxH = lines.size * lineH + padV * 2
        val bx = (width - boxW) / 2
        val by = (height - boxH) / 2
        graphics.fill(bx - 2, by - 2, bx + boxW + 2, by + boxH + 2, 0xFF111111.toInt())
        graphics.fill(bx, by, bx + boxW, by + boxH, 0xFF222222.toInt())
        lines.forEachIndexed { i, line ->
            graphics.text(font, Component.literal(line), bx + padH, by + padV + i * lineH, 0xFFFFFFFF.toInt(), false)
        }
    }

    override fun isPauseScreen() = true
}
