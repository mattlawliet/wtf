package dev.matt.wtf.client

// Single point of arbitration between the 3 weak identity signals (uuid
// stamp, slot ledger, contentHash) for a chest/container-held shulker stack.
// Previously each call site in WTFClient re-implemented its own subset of
// "which signal wins", which is why every container/event type (ender chest
// strip, server resync, hopper extraction) needed its own bugfix for what was
// really one underlying problem. See memory: wtf-shulker-identity-model.
// Not `internal`: the unit tests compile as a separate module.
object ShulkerIdentityResolver {

    // THE ladder. Every container path resolves through this and only this, so
    // the order lives in one place instead of being re-derived per call site.
    // Strongest signal first:
    //
    //   1. a live wtf:uuid stamp on the stack
    //   2. this session's slot ledger for this exact slot
    //   3. the persisted slot ledger for this chest
    //   4. a tracked entry that already records this exact slot
    //   5. the content-hash fingerprint
    //
    // Hash is deliberately last. It is the one signal that drifts - ender wipes
    // the stamp on every reopen and server resync perturbs nested NBT - so
    // letting it outrank slot continuity is exactly how two identical boxes
    // swapped identities. The chest-open path used to run it first.
    //
    // ledgerHint/persistedHint must already be validated by the caller
    // (ledgerEntryMatchesStack) because rejecting one also evicts it, and this
    // object does not own those maps.
    fun resolveChestSlot(
        trackedShulkers: Map<String, WTFClient.ShulkerState>,
        stampUUID: String?,
        ledgerHint: String?,
        persistedHint: String?,
        chestState: String,
        slotIndex: Int,
        stackHash: String,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean,
        claimed: Set<String>,
        genericEmptyHash: String,
        // "x,y,z" of the open container; unused for the ender chest, which is
        // one container wherever it is opened.
        chestCoords: String = "",
        // True for a record whose box is visibly still where it says - placed,
        // or on the ground. Supplied by the caller so this stays pure.
        stillWhereRecorded: (WTFClient.ShulkerState) -> Boolean = { false },
    ): String? {
        stampUUID?.takeIf { it in trackedShulkers && it !in claimed }?.let { return it }
        ledgerHint?.let { return it }
        persistedHint?.let { return it }
        resolveBySlotIndex(trackedShulkers, chestState, slotIndex, stackType, stackName, hasCustomName, claimed, chestCoords, stackHash)
            ?.let { return it }
        return resolveTrackedChestStack(
            trackedShulkers, stackHash, stackName, stackType, hasCustomName, claimed,
            genericEmptyHash = genericEmptyHash, preferHint = persistedHint, slotIndex = slotIndex,
            chestState = chestState, chestCoords = chestCoords, stillWhereRecorded = stillWhereRecorded
        )
    }

    // A slot number only means something inside its own container. Both
    // slot-based rules below compared slotIndex alone, so a record at slot 0
    // of one chest matched slot 0 of any other - and, the other way round, a
    // record whose slot differed was excluded from a chest it had never been
    // in. A marked box a hopper carried from slot 0 of one chest to slot 3 of
    // the next was refused there for exactly that (reported 2026-09-24).
    fun inContainer(entry: WTFClient.ShulkerState, chestState: String, chestCoords: String): Boolean =
        entry.state == chestState && (chestState == "enderchest" || entry.coords == chestCoords)

    // True when a tracked entry plausibly IS this stack - used to validate a
    // ledger/persisted slot->uuid mapping before trusting it. A box that left
    // the slot leaves a stale mapping behind; without this check the next
    // (different) box in that slot inherits the old identity.
    fun ledgerEntryMatchesStack(
        entry: WTFClient.ShulkerState,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean
    ): Boolean {
        if (entry.type != stackType) return false
        // A named box carries a stable identity in its name - it does NOT drift
        // across restart, so it must agree. (Also rejects a now-named box sitting
        // where an unnamed one was tracked, and vice-versa.)
        val entryNamed = entry.name.isNotEmpty() && entry.name != "Shulker Box" && entry.name != "???"
        if (hasCustomName || entryNamed) return entry.name == stackName
        // Unnamed box: the content-hash is NOT reliable here - ender strips the
        // uuid stamp on every reopen and server resync perturbs nested NBT, so
        // the hash drifts across restart. Slot continuity (this mapping was
        // recorded for this exact slot) is the only durable signal, so trust it
        // by slot+type. A genuinely different identified box would carry its own
        // tracked uuid and be resolved before this fallback is ever consulted.
        return true
    }

    // genericEmptyHash: the fingerprint an *empty* box of this type produces.
    // Passed in rather than computed here so this object stays free of any
    // Minecraft runtime dependency and can be unit-tested against plain maps.
    fun resolveTrackedChestStack(
        trackedShulkers: Map<String, WTFClient.ShulkerState>,
        stackHash: String,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean,
        alreadyResolved: Set<String>,
        genericEmptyHash: String,
        preferHint: String? = null,
        slotIndex: Int = -1,
        chestState: String = "",
        chestCoords: String = "",
        stillWhereRecorded: (WTFClient.ShulkerState) -> Boolean = { false },
    ): String? {
        // Deterministic disambiguation when several candidates are equally
        // valid: the persisted-ledger hint for this slot wins, then a candidate
        // already pinned to this slot, then happy entries, then the oldest by
        // firstSeen. Map iteration order is NOT stable across reloads, so never
        // fall back to a bare firstOrNull - that's what swapped identities.
        fun pick(candidates: List<WTFClient.ShulkerState>): WTFClient.ShulkerState? {
            if (candidates.isEmpty()) return null
            candidates.firstOrNull { it.uuid == preferHint }?.let { return it }
            if (slotIndex >= 0) candidates.firstOrNull { it.slotIndex == slotIndex }?.let { return it }
            val happy = candidates.filter { it.happy }.ifEmpty { candidates }
            return happy.minByOrNull { if (it.firstSeen > 0) it.firstSeen else Long.MAX_VALUE }
                ?: happy.minByOrNull { it.uuid }
        }

        // When resolving a specific slot, a candidate already pinned to a
        // DIFFERENT slot is not this box - slot position is authoritative for
        // container-held boxes (esp. ender, where the item uuid stamp is wiped
        // on every reopen so slot is the only durable identity). Without this an
        // identical-content box pinned to slot 20 gets claimed by slot 5 simply
        // because it was the only hash candidate left.
        fun slotEligible(c: WTFClient.ShulkerState): Boolean =
            slotIndex < 0 || c.slotIndex < 0 || c.slotIndex == slotIndex || !inContainer(c, chestState, chestCoords)

        // A record whose box the mod last saw sitting in ANOTHER container is
        // the worst candidate, not an equal one: that box is presumably still
        // there. A box that vanished (lastKnown) or has no known place is the
        // one owed. Only a tiebreak - with nothing better, a box a hopper moved
        // unseen still has to be claimable.
        fun seenElsewhere(c: WTFClient.ShulkerState): Boolean =
            (c.state == "ex-inv" || c.state == "enderchest") && !c.lastKnown &&
                !inContainer(c, chestState, chestCoords) && (c.state == "enderchest" || c.coords.isNotEmpty())

        if (stackHash.isNotEmpty() && stackHash != genericEmptyHash) {
            val hashMatches = trackedShulkers.values.filter {
                it.uuid !in alreadyResolved &&
                    it.type == stackType &&
                    it.contentHash == stackHash &&
                    slotEligible(it) && !stillWhereRecorded(it) &&
                    (it.state == "inv" || it.state == "item" || it.state == "block" || it.state == "ex-inv" || it.state == "enderchest")
            }
            val hashMatch = pick(hashMatches.filterNot(::seenElsewhere).ifEmpty { hashMatches })
            if (hashMatch != null) {
                WTFClient.log("chest resolve: hash-matched $stackName to tracked UUID ${hashMatch.uuid}")
                return hashMatch.uuid
            }
        }

        return null
    }

    // Authoritative slot fallback for container-held boxes: a tracked entry that
    // records THIS exact slot in THIS container state is this box. Uses the
    // entry's own persisted slotIndex (always saved on the entry) rather than
    // the separate persistedChestLedger map - so it still resolves when that map
    // is incomplete. That gap is what orphaned default/unnamed ender boxes: their
    // generic hash is unmatchable and, with no ledger entry, they were
    // re-discovered as new non-happy entries on every reopen, losing the marker
    // on all but the one box whose ledger row happened to survive.
    fun resolveBySlotIndex(
        trackedShulkers: Map<String, WTFClient.ShulkerState>,
        chestState: String,
        slotIndex: Int,
        stackType: String,
        stackName: String,
        hasCustomName: Boolean,
        alreadyResolved: Set<String>,
        chestCoords: String = "",
        stackHash: String = "",
    ): String? {
        if (slotIndex < 0) return null
        val match = trackedShulkers.values.firstOrNull {
            it.uuid !in alreadyResolved &&
                inContainer(it, chestState, chestCoords) &&
                // Same slot, different contents: a different box. The ender chest
                // changes where the mod cannot see - another client, a plugin -
                // and an unnamed record used to latch onto whatever unnamed box
                // now filled its slot, mark and all (found by WtfFuzz, seed 17).
                // The fingerprint no longer drifts (stamps are stripped before
                // hashing, counts are in since v18), so it can be asked.
                (stackHash.isEmpty() || it.contentHash.isEmpty() || it.contentHash == stackHash) &&
                it.slotIndex == slotIndex &&
                it.type == stackType &&
                ledgerEntryMatchesStack(it, stackName, stackType, hasCustomName)
        } ?: return null
        WTFClient.log("chest resolve: slot-matched $stackName ($chestState s$slotIndex) -> ${match.uuid}")
        return match.uuid
    }
}
