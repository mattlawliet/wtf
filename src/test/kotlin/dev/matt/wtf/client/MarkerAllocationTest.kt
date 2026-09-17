package dev.matt.wtf.client

import kotlin.test.Test
import kotlin.test.assertEquals

// allocateMarkers decides which stacks wear a marker in the window after a
// server resync has wiped every wtf:uuid stamp. Three identical boxes and one
// marked entry must produce exactly one marker - the bug this exists to stop is
// all three lighting up.
class MarkerAllocationTest {

    private val CURSOR = -1

    private fun entry(uuid: String, at: String? = null, hash: String = "") =
        MarkerEntry(uuid, at, hash)

    // Every candidate hashes to the same thing unless told otherwise, which is
    // the whole difficulty: identical boxes are identical.
    private fun allocate(
        candidates: List<Pair<Int, String>>,
        entries: List<MarkerEntry>,
        claimed: Set<String> = emptySet(),
        fingerprints: Map<Int, String?> = emptyMap(),
        default: String? = "H",
    ) = allocateMarkers(candidates, entries, claimed) { fingerprints.getOrDefault(it, default) }

    // --- pass B, the count rule ---------------------------------------------

    @Test
    fun `one marked entry among three identical stacks marks exactly one`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5", 5 to "hotbar:6"),
            listOf(entry("a", hash = "H")),
        )
        assertEquals(1, awarded.size)
    }

    @Test
    fun `two marked entries among three identical stacks mark two`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5", 5 to "hotbar:6"),
            listOf(entry("a", hash = "H"), entry("b", hash = "H")),
        )
        assertEquals(2, awarded.size)
    }

    @Test
    fun `an entry already answered for by a stamped stack is not handed out again`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5"),
            listOf(entry("a", hash = "H")),
            claimed = setOf("a"),
        )
        assertEquals(emptySet(), awarded)
    }

    @Test
    fun `a stack with no usable fingerprint is never marked`() {
        // null is what an empty box returns: its hash is shared by every empty
        // box of that colour, so counting over it would mark untouched boxes.
        val awarded = allocate(
            listOf(3 to "hotbar:4"),
            listOf(entry("a", hash = "generic-empty")),
            default = null,
        )
        assertEquals(emptySet(), awarded)
    }

    @Test
    fun `entries with a different fingerprint do not lend their count`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4"),
            listOf(entry("a", hash = "OTHER")),
        )
        assertEquals(emptySet(), awarded)
    }

    // --- pass A, position memory --------------------------------------------

    @Test
    fun `position memory puts the marker on the remembered slot, not the lowest`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5", 5 to "hotbar:6"),
            listOf(entry("a", at = "hotbar:6", hash = "H")),
        )
        assertEquals(setOf(5), awarded)
    }

    @Test
    fun `position memory does not let one entry claim two slots`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5"),
            listOf(entry("a", at = "hotbar:4", hash = "H")),
        )
        assertEquals(setOf(3), awarded)
    }

    @Test
    fun `a slot resolved by memory is never fingerprinted`() {
        val asked = mutableListOf<Int>()
        allocateMarkers(
            listOf(3 to "hotbar:4"),
            listOf(entry("a", at = "hotbar:4", hash = "H")),
            emptySet(),
        ) { asked.add(it); "H" }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `memory and count share out one marker each`() {
        val awarded = allocate(
            listOf(3 to "hotbar:4", 4 to "hotbar:5", 5 to "hotbar:6"),
            listOf(entry("a", at = "hotbar:6", hash = "H"), entry("b", hash = "H")),
        )
        // "a" takes its remembered slot; "b" is handed to the lowest slot left.
        assertEquals(setOf(5, 3), awarded)
    }

    // --- the cursor ---------------------------------------------------------

    @Test
    fun `the cursor is allocated even when it is the only unstamped stack`() {
        val awarded = allocate(
            listOf(CURSOR to "cursor"),
            listOf(entry("a", hash = "H")),
        )
        assertEquals(setOf(CURSOR), awarded)
    }

    @Test
    fun `an empty box on the cursor is not marked`() {
        val awarded = allocate(
            listOf(CURSOR to "cursor"),
            listOf(entry("a", hash = "generic-empty")),
            default = null,
        )
        assertEquals(emptySet(), awarded)
    }

    @Test
    fun `the cursor is preferred over inventory slots when nothing is remembered`() {
        val awarded = allocate(
            listOf(CURSOR to "cursor", 3 to "hotbar:4"),
            listOf(entry("a", hash = "H")),
        )
        assertEquals(setOf(CURSOR), awarded)
    }
}
