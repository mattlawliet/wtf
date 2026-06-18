package dev.matt.wtf.client

// Single point of arbitration between the 3 weak identity signals (uuid
// stamp, slot ledger, contentHash) for a chest/container-held shulker stack.
// Previously each call site in WTFClient re-implemented its own subset of
// "which signal wins", which is why every container/event type (ender chest
// strip, server resync, hopper extraction) needed its own bugfix for what was
// really one underlying problem. See memory: wtf-shulker-identity-model.
internal object ShulkerIdentityResolver {

    // True when a tracked entry plausibly IS this stack - used to validate a
    // ledger/persisted slot->uuid mapping before trusting it. A box that left
    // the slot leaves a stale mapping behind; without this check the next
    // (different) box in that slot inherits the old identity.
    fun ledgerEntryMatchesStack(
        entry: WTFClient.ShulkerState,
        stackHash: String,
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

    fun resolveTrackedChestStack(
        trackedShulkers: Map<String, WTFClient.ShulkerState>,
        stackHash: String,
        stackName: String,
        stackType: String,
        hasCustomName: Boolean,
        alreadyResolved: Set<String>,
        preferHint: String? = null,
        slotIndex: Int = -1
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
            slotIndex < 0 || c.slotIndex < 0 || c.slotIndex == slotIndex

        if (stackHash.isNotEmpty() && stackHash != WTFClient.genericEmptyHash(stackType)) {
            val hashMatches = trackedShulkers.values.filter {
                it.uuid !in alreadyResolved &&
                    it.type == stackType &&
                    it.contentHash == stackHash &&
                    slotEligible(it) &&
                    (it.state == "inv" || it.state == "item" || it.state == "block" || it.state == "ex-inv" || it.state == "enderchest")
            }
            val hashMatch = pick(hashMatches)
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
        alreadyResolved: Set<String>
    ): String? {
        if (slotIndex < 0) return null
        val match = trackedShulkers.values.firstOrNull {
            it.uuid !in alreadyResolved &&
                it.state == chestState &&
                it.slotIndex == slotIndex &&
                it.type == stackType &&
                ledgerEntryMatchesStack(it, "", stackName, stackType, hasCustomName)
        } ?: return null
        WTFClient.log("chest resolve: slot-matched $stackName ($chestState s$slotIndex) -> ${match.uuid}")
        return match.uuid
    }

    // Unnamed shulker boxes carry no identifying NBT, and a server resync
    // (e.g. rejoining after a restart) can wipe wtf:uuid and/or perturb
    // content-hash fingerprints (CUSTOM_DATA on nested boxes, etc). When
    // none of that resolves a slot, re-link to the one previously-tracked
    // entry for this chest of matching type — BUT only when there is exactly
    // one such candidate. If there are two or more same-type unnamed boxes
    // and only one candidate remains, picking randomly would swap identities
    // across restarts; instead do nothing and let the persisted slot ledger
    // accumulate correct mappings over time via manual opens.
    fun resolveOrphanedChestSlot(
        trackedShulkers: Map<String, WTFClient.ShulkerState>,
        stackType: String,
        hasCustomName: Boolean,
        chestState: String,
        coordStr: String,
        dimStr: String,
        alreadyResolved: Set<String>
    ): String? {
        if (hasCustomName) return null
        val candidates = trackedShulkers.values.filter {
            it.uuid !in alreadyResolved &&
                it.type == stackType &&
                it.state == chestState &&
                it.coords == coordStr &&
                it.dim == dimStr
        }
        if (candidates.size != 1) return null
        return candidates[0].uuid.also { WTFClient.log("chest resolve: orphan-slot re-linked unnamed $stackType to tracked UUID $it (unambiguous)") }
    }
}
