package dev.matt.wtf.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The arbitration logic is the most bug-prone code in the mod and the only part
// that is pure - maps in, uuid out. Everything here runs on a plain JVM.
class ShulkerIdentityResolverTest {

    private val EMPTY_HASH = "generic-empty"
    private val HERE = "0,0,0"
    private val THERE = "5,0,5"

    private fun entry(
        uuid: String,
        state: String = "inv",
        name: String = "",
        hash: String = "",
        type: String = "minecraft:shulker_box",
        slotIndex: Int = -1,
        happy: Boolean = false,
        firstSeen: Long = 0,
        coords: String = "",
        lastKnown: Boolean = false,
    ) = WTFClient.ShulkerState(
        uuid = uuid,
        state = state,
        coords = coords,
        lastKnown = lastKnown,
        name = name,
        contentHash = hash,
        type = type,
        slotIndex = slotIndex,
        happy = happy,
        firstSeen = firstSeen
    )

    private fun tracked(vararg entries: WTFClient.ShulkerState) =
        entries.associateBy { it.uuid }

    // --- ledgerEntryMatchesStack ---------------------------------------------

    @Test
    fun `ledger entry of a different box type never matches`() {
        val e = entry("a", type = "minecraft:red_shulker_box")
        assertEquals(
            false,
            ShulkerIdentityResolver.ledgerEntryMatchesStack(e, "", "minecraft:shulker_box", false)
        )
    }

    @Test
    fun `named box must agree on name in both directions`() {
        val named = entry("a", name = "Ores")
        // Stack claims a different name.
        assertEquals(false, ShulkerIdentityResolver.ledgerEntryMatchesStack(named, "Bones", "minecraft:shulker_box", true))
        assertEquals(true, ShulkerIdentityResolver.ledgerEntryMatchesStack(named, "Ores", "minecraft:shulker_box", true))
        // A now-named stack cannot inherit an unnamed entry's identity.
        val unnamed = entry("b", name = "Shulker Box")
        assertEquals(false, ShulkerIdentityResolver.ledgerEntryMatchesStack(unnamed, "Ores", "minecraft:shulker_box", true))
    }

    @Test
    fun `unnamed box matches on type alone - slot continuity is the signal`() {
        // Content hash deliberately ignored here: ender strips the uuid stamp on
        // reopen and server resync perturbs nested NBT, so the hash drifts.
        val e = entry("a", name = "Shulker Box", hash = "stale")
        assertEquals(true, ShulkerIdentityResolver.ledgerEntryMatchesStack(e, "Shulker Box", "minecraft:shulker_box", false))
    }

    // --- resolveBySlotIndex --------------------------------------------------

    @Test
    fun `slot resolution requires matching state and slot`() {
        val map = tracked(
            entry("a", state = "chest", slotIndex = 5, name = "Shulker Box"),
            entry("b", state = "chest", slotIndex = 9, name = "Shulker Box")
        )
        assertEquals("a", ShulkerIdentityResolver.resolveBySlotIndex(map, "chest", 5, "minecraft:shulker_box", "Shulker Box", false, emptySet()))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(map, "ex-inv", 5, "minecraft:shulker_box", "Shulker Box", false, emptySet()))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(map, "chest", 7, "minecraft:shulker_box", "Shulker Box", false, emptySet()))
    }

    @Test
    fun `slot resolution skips already-claimed uuids`() {
        val map = tracked(entry("a", state = "chest", slotIndex = 5, name = "Shulker Box"))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(map, "chest", 5, "minecraft:shulker_box", "Shulker Box", false, setOf("a")))
    }

    @Test
    fun `negative slot never resolves`() {
        val map = tracked(entry("a", state = "chest", slotIndex = -1, name = "Shulker Box"))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(map, "chest", -1, "minecraft:shulker_box", "Shulker Box", false, emptySet()))
    }

    // --- resolveTrackedChestStack -------------------------------------------

    private fun resolve(
        map: Map<String, WTFClient.ShulkerState>,
        hash: String,
        name: String = "Shulker Box",
        claimed: Set<String> = emptySet(),
        hint: String? = null,
        slotIndex: Int = -1,
        chestState: String = "ex-inv",
        chestCoords: String = HERE,
    ) = ShulkerIdentityResolver.resolveTrackedChestStack(
        map, hash, name, "minecraft:shulker_box", false, claimed,
        genericEmptyHash = EMPTY_HASH, preferHint = hint, slotIndex = slotIndex,
        chestState = chestState, chestCoords = chestCoords,
    )

    @Test
    fun `an empty box hash matches nothing - every empty box shares it`() {
        val map = tracked(entry("a", hash = EMPTY_HASH))
        assertNull(resolve(map, EMPTY_HASH))
        assertNull(resolve(map, ""))
    }

    @Test
    fun `unique hash resolves`() {
        val map = tracked(entry("a", hash = "h1"), entry("b", hash = "h2"))
        assertEquals("b", resolve(map, "h2"))
    }

    @Test
    fun `ledger hint wins over every other tiebreak`() {
        val map = tracked(
            entry("a", hash = "h", happy = true, firstSeen = 1),
            entry("b", hash = "h", firstSeen = 999)
        )
        assertEquals("b", resolve(map, "h", hint = "b"))
    }

    @Test
    fun `slot pin beats happy and age when there is no hint`() {
        val map = tracked(
            entry("a", hash = "h", happy = true, firstSeen = 1),
            entry("b", hash = "h", slotIndex = 4, firstSeen = 999)
        )
        assertEquals("b", resolve(map, "h", slotIndex = 4))
    }

    @Test
    fun `a candidate pinned to a different slot is not this box`() {
        val map = tracked(entry("a", state = "ex-inv", coords = HERE, hash = "h", slotIndex = 20))
        assertNull(resolve(map, "h", slotIndex = 5))
    }

    // Reported 2026-09-24: a hopper carried a marked box from slot 0 of one
    // chest to slot 3 of the next, and the slot rule refused it there.
    @Test
    fun `a slot in ANOTHER container does not rule a candidate out`() {
        val map = tracked(entry("a", state = "ex-inv", coords = THERE, hash = "h", slotIndex = 0, lastKnown = true))
        assertEquals("a", resolve(map, "h", slotIndex = 3))
    }

    @Test
    fun `slot continuity only holds inside the same container`() {
        val map = tracked(entry("a", state = "ex-inv", coords = THERE, slotIndex = 0, name = "Shulker Box"))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(
            map, "ex-inv", 0, "minecraft:shulker_box", "Shulker Box", false, emptySet(), HERE))
        assertEquals("a", ShulkerIdentityResolver.resolveBySlotIndex(
            map, "ex-inv", 0, "minecraft:shulker_box", "Shulker Box", false, emptySet(), THERE))
    }

    // A box still placed as a block is not the one in this chest, however alike.
    @Test
    fun `a record whose box is still placed is not a candidate`() {
        val map = tracked(
            entry("placed", state = "block", coords = "7,7,7", hash = "h", happy = true, firstSeen = 1),
            entry("gone", state = "ex-inv", coords = "", hash = "h", firstSeen = 2),
        )
        val got = ShulkerIdentityResolver.resolveTrackedChestStack(
            map, "h", "Shulker Box", "minecraft:shulker_box", false, emptySet(),
            genericEmptyHash = EMPTY_HASH, chestState = "ex-inv", chestCoords = HERE,
            stillWhereRecorded = { it.state == "block" },
        )
        assertEquals("gone", got)
    }

    // WtfFuzz seed 17: an ender slot changed where the mod could not see.
    @Test
    fun `slot continuity refuses a box with different contents`() {
        val map = tracked(entry("old", state = "enderchest", slotIndex = 0, name = "Shulker Box", hash = "old-contents", happy = true))
        assertNull(ShulkerIdentityResolver.resolveBySlotIndex(
            map, "enderchest", 0, "minecraft:shulker_box", "Shulker Box", false, emptySet(), "", "new-contents"))
        assertEquals("old", ShulkerIdentityResolver.resolveBySlotIndex(
            map, "enderchest", 0, "minecraft:shulker_box", "Shulker Box", false, emptySet(), "", "old-contents"))
    }

    // The same report's second half: the unmarked twin still sitting in the
    // chest it was seen in must not beat the one that vanished and came back.
    @Test
    fun `a vanished record beats one last seen sitting in another chest`() {
        val map = tracked(
            entry("twin", state = "ex-inv", coords = THERE, hash = "h", slotIndex = 19, firstSeen = 1),
            entry("lost", state = "ex-inv", coords = "9,9,9", hash = "h", slotIndex = 20, firstSeen = 2, lastKnown = true),
        )
        assertEquals("lost", resolve(map, "h", slotIndex = 3))
    }

    @Test
    fun `happy entries win over unmarked ones`() {
        val map = tracked(
            entry("a", hash = "h", firstSeen = 1),
            entry("b", hash = "h", happy = true, firstSeen = 999)
        )
        assertEquals("b", resolve(map, "h"))
    }

    @Test
    fun `oldest firstSeen breaks a tie, and the result does not depend on map order`() {
        val old = entry("old", hash = "h", firstSeen = 100)
        val new = entry("new", hash = "h", firstSeen = 500)
        // Map iteration order is not stable across reloads; both orderings must
        // agree or identities swap between sessions.
        assertEquals("old", resolve(tracked(old, new), "h"))
        assertEquals("old", resolve(tracked(new, old), "h"))
    }

    @Test
    fun `claimed uuids are excluded`() {
        val map = tracked(entry("a", hash = "h", firstSeen = 1), entry("b", hash = "h", firstSeen = 2))
        assertEquals("b", resolve(map, "h", claimed = setOf("a")))
        assertNull(resolve(map, "h", claimed = setOf("a", "b")))
    }

    @Test
    fun `states that cannot hold a chest stack are ignored`() {
        val map = tracked(entry("a", state = "gone", hash = "h"))
        assertNull(resolve(map, "h"))
    }

    // --- resolveChestSlot: the shared ladder ---------------------------------

    private fun ladder(
        map: Map<String, WTFClient.ShulkerState>,
        stamp: String? = null,
        ledgerHint: String? = null,
        persistedHint: String? = null,
        slotIndex: Int = -1,
        hash: String = "",
        claimed: Set<String> = emptySet()
    ) = ShulkerIdentityResolver.resolveChestSlot(
        map,
        stampUUID = stamp,
        ledgerHint = ledgerHint,
        persistedHint = persistedHint,
        chestState = "chest",
        slotIndex = slotIndex,
        stackHash = hash,
        stackName = "Shulker Box",
        stackType = "minecraft:shulker_box",
        hasCustomName = false,
        claimed = claimed,
        genericEmptyHash = EMPTY_HASH
    )

    @Test
    fun `stamp outranks every other signal`() {
        val map = tracked(
            entry("stamped"),
            entry("ledger"),
            entry("persisted"),
            entry("slot", state = "chest", slotIndex = 3, name = "Shulker Box"),
            entry("hashed", hash = "h")
        )
        assertEquals("stamped", ladder(map, stamp = "stamped", ledgerHint = "ledger", persistedHint = "persisted", slotIndex = 3, hash = "h"))
    }

    @Test
    fun `session ledger outranks persisted ledger`() {
        val map = tracked(entry("ledger"), entry("persisted"))
        assertEquals("ledger", ladder(map, ledgerHint = "ledger", persistedHint = "persisted"))
    }

    // This is the ordering disagreement the two chest paths used to have: the
    // open path ran the hash match first and the close path ran slot first.
    @Test
    fun `slot continuity outranks the content hash`() {
        val map = tracked(
            entry("slot", state = "chest", slotIndex = 3, name = "Shulker Box"),
            entry("hashed", hash = "h")
        )
        assertEquals("slot", ladder(map, slotIndex = 3, hash = "h"))
    }

    @Test
    fun `persisted hint outranks slot continuity`() {
        val map = tracked(
            entry("persisted"),
            entry("slot", state = "chest", slotIndex = 3, name = "Shulker Box")
        )
        assertEquals("persisted", ladder(map, persistedHint = "persisted", slotIndex = 3))
    }

    @Test
    fun `hash is still used when nothing stronger applies`() {
        val map = tracked(entry("hashed", hash = "h"))
        assertEquals("hashed", ladder(map, hash = "h"))
    }

    @Test
    fun `a stamp naming an untracked box falls through to the rest of the ladder`() {
        val map = tracked(entry("hashed", hash = "h"))
        assertEquals("hashed", ladder(map, stamp = "ghost", hash = "h"))
    }

    @Test
    fun `a stamp for an already-claimed identity is not honoured`() {
        // Claimed means another slot in this same pass already owns it - handing
        // it out twice makes two markers fight over one entry.
        val map = tracked(entry("dup", hash = "h"))
        assertNull(ladder(map, stamp = "dup", hash = "h", claimed = setOf("dup")))
    }

    @Test
    fun `nothing resolves when no signal applies`() {
        assertNull(ladder(tracked(entry("a", hash = "other"))))
    }
}
