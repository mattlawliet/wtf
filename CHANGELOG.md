# Changelog

## 2.4.2

Identical boxes, fast clicking and saving, found by playing and by automated
tests: client gametests that drive a real client against a real dedicated
server (24 scenarios), and a random-walk fuzzer that moves boxes through every
place a box can go and checks the mod against the server after every step.

- **Identical boxes stay apart.** Marking one no longer marks its twin, moving
  one no longer moves the mark, and the mark survives hoppers, relogs, the
  crafting grid and itemscroller's alt-move.
- **No ghost box on the cursor after fast clicks.** Every click sent the server
  a hash that included the mod's own stamp, so the server "corrected" every
  click, and a correction arriving after the next click overwrote it.
- **Two chests side by side are two chests**, and a double chest in a wall of
  double chests keeps one identity whichever half you open.
- **Placing, breaking and picking up a marked box** keeps its mark.
- **F with no screen open is tracked**; two marked boxes swapped between the
  hands keep their own records.
- **Dyeing a marked box** keeps its mark and updates its colour.
- **Item counts are part of a box's fingerprint**, so a half-full box is no
  longer taken for a full one. Saves are upgraded automatically (format v18).
- **A crash while saving can no longer lose the newest save**, and a corrupt
  save is set aside as `moods.json.corrupt` instead of overwriting the backup.

Tested with itemscroller, tweakeroo, malilib and peek installed.

## 2.4.1

Two defects from the first played session on 2.4.0, both in the anvil.

A tracked box could not be renamed at all: the typed name was replaced by the
box's own name on every keystroke. The mod stamps `wtf:uuid` in place on the
live ItemStack, which the client menu's `lastSlots` copy never sees, so slot 0
compares unequal from then on - and the result slot recomputing under each
keystroke is what makes vanilla report that stale difference to
`AnvilScreen.slotChanged`, whose whole body resets the text field. No stamp is
written into an anvil, grindstone or smithing menu any more. Identity across
the anvil is unaffected; the click ledger and the content fingerprint were
already carrying it.

A box sitting in an anvil input slot was also recorded as having left the
player's possession - "ex-inv, location unknown" while they were looking
straight at it. Vanilla hands an anvil input back when the window closes, so
the demotion is now suppressed for the open menu's own slots when the mod has
resolved no container.

## 2.4.0

Out of the prototype line. `CHECKLIST.md`'s sixteen scenarios are recorded for the
first time - fifteen against a dedicated server, the sixteenth through a userspace
latency proxy - and all sixteen pass. Six defects surfaced during that run and all
six are fixed; **every one of them was in what the mod records, never in what it
does.** The worst silently stopped every chest close from committing after the
first window resize.

Known and accepted: two boxes with the same type, name and contents can swap which
record they carry (ticket 010, reversed deliberately - refusing instead stranded
records forever on servers where a dozen boxes read "Shulker Box"). The count stays
right; which record sits on which box does not.


First build off the prototype line. Alpha rather than beta deliberately:
`CHECKLIST.md`'s sixteen scenarios are still unrecorded, two defects found while
running them are open, and several of the checklist's own expected log lines turned
out to name `from=` values the code no longer emits. This is the identity work
settling, not a candidate.

A day of testing in the Loom dev client found five real defects in one family -
**a single click that moves two stacks** - each in a different disguise, and all
five are fixed:

### Fixed - one click, two stacks

- **A box dropped onto an occupied slot lost its neighbour.** The ledger infers a
  destination by diffing the menu before and after the click, which cannot resolve a
  move where both stacks are a source and a destination to each other. The displaced
  box was never recorded, so its record went on claiming a slot another box held,
  the scan found the other box there, and cleanup declared the first one gone - the
  marker dying with it and returning only until the next scan. Swaps are now paired
  from facts: vanilla's own `slotId`/`button` for F and the number keys, and for a
  plain drop, the observation that a slot which held one shulker and now holds a
  different one has exchanged with the cursor.
- **An anvil swap handed one box's identity to another.** `resolveUUID` reads the
  ledger that the same loop writes, so the first iteration's move was read back by
  the second as the identity of a different stack - stamp, name and content hash all
  adopted by the wrong box. Identities are now resolved from the pre-click state.
- **Position memory lied in both directions.** The ledger moved boxes without
  updating `coords`, so a box arriving in a slot did not claim it and a box leaving
  did not release it. Pass 0 then stamped whichever box happened to be sitting there
  and fought the duplicate-stamp evictor over it - 230 rounds of that in one session.

### Fixed - records that could not come home

- **A renamed box stranded its own record.** `contentHash` covers type, name and
  contents, so a hash taken before a rename describes a box that no longer exists.
  `cachedContents` carries no name and still does, so it is now a second way in, and
  recovery refreshes the stale hash on the way through.
- **An ambiguous recovery refused outright**, which on a server where a dozen boxes
  read "Shulker Box" meant most of them could never return. It now claims the oldest
  unaccounted record, tiebroken deterministically. Reversed on purpose; the click
  path still refuses, because that one mints identity rather than promoting it.
- **Every unnamed box looked renamed on its first move**, rewriting its content hash
  on a click where nothing had changed - `scan:new` spelled unnamed `"???"` while the
  rest of the mod spelled it `"Shulker Box"`.

### Fixed - creative used the wrong slot numbers

- **The marker did not render in the creative inventory**, except on the main grid.
  Creative rebuilds the inventory tab out of `SlotWrapper`s built as
  `SlotWrapper(inventoryMenu.slots[i], i, x, y)`, so `containerSlot` on one carries
  the **menu** index - armor 5..8, main 9..35, hotbar 36..44, offhand 45 - while the
  slot's container is still the player's inventory. Main inventory is 9..35 in both
  spaces, which is exactly why that one row worked.
- **Marking a box in the creative hotbar recorded the wrong slot**, and one case was
  worse than nonsense: hotbar slot 4 stored `offhand` - a real key for a different
  real slot - so position memory would have put the box back in the wrong place after
  a resync. The click ledger, both inventory snapshots and the move log shared the
  same mistake, because creative's menu reuses container id 0.
- All seven now resolve the inventory index from the stack itself rather than trusting
  the slot's number, which is correct in every screen. Creative support fell out of
  the fix; it was not built for.

### Added

- **Search completion and material groups.** Ghost text in the search box, and
  `#ores` / `#logs` matching on what is inside each box. Tags are matched by prefix
  in their own index rather than folded into the free-text blob, so existing
  name and item searches rank exactly as they did.

### Changed - the log is the instrument

- State changes that arrive through a click now print a `transition:` line. One did
  not, so the same entry could log `inv -> ex-inv` twice with nothing in between.
- `overrode stamp` split into `(restamped)`, which fires on every click because the
  server drops client-only components, and `(OVERRODE <uuid>)`, which means two
  identities are fighting over one box. The second spent an afternoon buried under
  the first.
- The in-game help describes only gestures that exist, and the grid strips the
  Private Use Area characters some servers put in box names.

## 2.4.0-proto.3

Verified in the Loom dev client over one session: 46 consecutive anvil renames on
one box with zero orphaned records, markers holding through resyncs, and the
offhand/ender/chest moves tracked end to end.

### Fixed - markers going in and out of existence

- **The marker no longer blinks.** Both render paths asked the `wtf:uuid` stamp
  at frame time. That stamp is client-only NBT: the server wipes it on every
  resync and only a scan puts it back, and scans are throttled - the hotbar heal
  runs once every 40 ticks. The icon therefore went dark for up to two seconds at
  a stretch, repeatedly. Marked-ness is now resolved once per tick into a set
  (stamp first, content fingerprint second) and render just reads it, so a
  stripped stamp costs one tick instead of two seconds. The fingerprint is only
  recomputed for a slot whose contents actually changed.
- **The empty unnamed box is excluded from the fingerprint path**, since every
  empty box of a colour shares one hash and would otherwise light up unmarked.
- **Marking with the keybind shows the icon immediately.** The caches key on the
  stack's content hash, and marking changes the tracked entry rather than the
  stack, so the cached "not marked" answer stood forever. `save()` now clears
  them, which covers every path that changes happiness at once.
- **A box shift-clicked into a chest keeps its icon**, on the way in and on
  reopen. Container slots were stamp-or-ledger only, so once the server stripped
  the stamp the box was unidentifiable inside a container even though the
  inventory could still place it by fingerprint. They now use the same per-tick
  cache, ledger first so same-content boxes are not confused for each other.
- **The stack on the cursor shows its marker.** It is not one of `menu.slots`, so
  nothing drew it: picking a marked box up made the icon vanish until it was put
  down. Works in every screen with slots.
- **The offhand shows its marker** in the gameplay HUD, on the side opposite the
  main hand. The hotbar element only ever looped the nine hotbar slots.
- **Markers landed on the wrong slots in every container screen.** `Slot.index`
  is the slot's position in the *menu*, not in the inventory, so the same
  physical slot is 30 in the inventory screen, 57 with a chest open and 84 with a
  large chest. A single chest therefore drew stars on empty slots and a large
  chest drew none, while the player's own inventory - where the two spaces
  coincide - looked perfect. Everything player-inventory-keyed now uses
  `getContainerSlot()`.
- The same confusion was already in `captureInventorySnapshot`,
  `captureInventoryBySlot` and `findInventoryStackForEntry`, which built location
  keys like `hotbar:3` out of a menu index: with a chest open, menu slot 27 became
  `inv:19` and the hotbar fell off the end as `slot:54`. Records written while a
  chest was open could never match their own slot again in the scan's position
  memory.

### Fixed - identity across menus

- **An anvil rename no longer orphans the record.** The custom name is part of
  the content fingerprint and the anvil result is built server-side, so a renamed
  box matched its record by neither uuid nor hash nor name and the next scan
  adopted it as a new entry. The name and fingerprint are now refreshed as the
  result is taken.
- **The click ledger can follow a box whose stamp is gone.** `resolveUUID` was
  stamp-or-ledger only, so an unstamped box could not be tracked across any menu
  at all - which is why the anvil produced no ledger lines whatsoever and the
  rename bridge never ran. It now falls back to the content fingerprint, and
  only when exactly one tracked entry matches: two boxes with identical contents
  stay ambiguous rather than swapping identities.

### Fixed - the offhand swap key

- **A box swapped to the offhand from inside a container is no longer lost.**
  A chest or ender menu has no slot for the offhand - `ChestMenu` builds the
  container plus player inventory indices 0..35 and nothing else - but the
  offhand swap key moves stacks in and out of it anyway. Every path that walked
  `menu.slots` had a one-slot hole: the click ledger, both inventory snapshots,
  and the lookup the chest-close self-correction uses. The offhand is now
  synthesized into all four at its player-inventory index.
- **Swapping a box out of a container no longer registers a phantom drop.** The
  click ledger saw the slot empty with nothing gaining, concluded the stack had
  been thrown out of the menu, and registered a drop expectation for an item
  entity that was never going to spawn.
- **An ender chest can report a box missing on close.** The branch was gated off
  for ender chests entirely, so a box that left the ender by any route the mod
  could not see stayed recorded as still being in it - forever, and the locate
  path followed the stale record. It now runs for the ender too, still behind
  the persisted-ledger precondition that guards ambiguous unnamed boxes, so the
  worst case is an honest last-known mark rather than a confident wrong one.

### Fixed - search

- **The search stopped matching on noise.** The score was
  `matchedChars / name.length` and anything above zero was listed, so letters
  that happened to appear in order somewhere in a long name matched - `seo`
  scored 0.06 against "redstone dust" and showed up. The score is now the span
  the match had to stretch to collect the query, so a tight hit scores near 1 and
  a scattered one collapses, with a single `MIN_MATCH` threshold at 0.5. Typos
  and vowel-dropped abbreviations still land (`dimond` 0.86, `rdst` 0.80,
  `dmnd` 0.57); noise does not (`ipx` 0.27, `seo` 0.11). Substring hits are
  unaffected and still score 1.

### Fixed - the keybind

- **The keybind works with no GUI open**, acting on the box in the main hand.
  It required a container screen with a slot under the cursor and returned
  silently otherwise, while the in-game help has always claimed it works on a
  "box in hand".
- **Marking a box that sits in a chest records where the chest is.** The keybind
  path set the state to `ex-inv` but never `dim`/`coords`, so the list rendered
  an empty location and Locate had nothing to point at. Ender entries stay
  coordinate-free, which is deliberate - an ender chest is a per-player
  inventory, not a place.

### Fixed - loading an older save

- **An entry written before `slotIndex` existed loaded as slot 0.** Gson builds
  `ShulkerState` without the Kotlin constructor - the class has required
  parameters, so there is no no-arg path - which means a field the JSON does not
  carry keeps the JVM zero value rather than the default written in the class.
  `slotIndex` defaults to -1 for "no slot", so those entries came back claiming
  slot 0: a real slot, rendered as "Ender Chest - Slot 1" and fed to the
  resolver's slot-continuity tiebreak as a position the box never had. Six
  entries across the saves on this machine have that shape, and a v16 file never
  even reaches `migrate()`. The save is now normalised as a JSON tree before it
  is bound, where an absent field is still distinguishable from a zero, with the
  same treatment for the non-null strings.
- Covered by seven new tests over the persisted shapes, including a real
  release-format (v15 + `nextSerial`) save, a pre-v15 entry, and a box genuinely
  sitting in slot 0.

### Debug logging

- **The dev client starts in verbose.** It is only ever run to watch what the mod
  does, so there is no `/wtf debug verbose` to remember before reproducing
  something. Release jars are unchanged: the normal jar stays quiet, the debug
  jar still keys off its marker resource.
- **Container moves are logged as they happen.** The state change only lands when
  the screen closes or the next scan runs, so watching a shift-click live showed
  nothing but a terse ledger line. Logged as `click:`, not `transition:` -
  nothing has transitioned yet, and `CHECKLIST.md` greps that prefix for real
  state changes.
- **One shape for every move line**, at click time and on close alike:
  `from > to name (uuid)`, with anything extra trailing.
- `ShulkerGridScreen.kt` held three raw NUL bytes in a signature string, which
  made the whole file read as binary to `grep` and `rg` - they silently skipped
  it. Now `\u0000` escapes.

## 2.4.0-proto.2

### Fixed — data loss

- **Power loss no longer wipes your moods.** The atomic save added in proto.1
  renamed a temp file into place but never flushed it, so on a hard power cut
  the filesystem came back with a 0-byte `moods.json` — the rename was in the
  journal, the contents were still in page cache. The next save then rotated
  that empty file into `moods.json.bak` and both copies were gone, which is the
  "could not read your WTF save (and no usable backup)" message on the next
  join. The temp file is now fsynced before the rename, and the directory after
  it, so the rename itself is durable.
- **A zero-length save is never rotated into the backup.** It is the corpse of
  an interrupted write, not a save worth keeping.
- **A leftover `moods.json.tmp` is used as a last-resort recovery source**,
  after the primary and the backup. It only exists if the machine died in the
  rename window, in which case it holds the newest complete data.
- **A truncated-but-parseable save is treated as unreadable** and falls through
  to the backup, rather than being applied. (Corrected in proto.3: this was
  written as a crash fix - "Gson builds `ShulkerSave` without the Kotlin
  constructor, so `{}` produced a null `tracked_shulkers` and an NPE in
  `applySave()`" - and that is not true of the wrapper. Every `ShulkerSave`
  parameter has a default, so Kotlin emits a no-arg constructor and Gson does
  apply the defaults: `{}` loads as an empty map, not null. The guard is
  defensive, not a fix for an observed NPE, and it stays because the guarantee
  vanishes the moment a parameter without a default is added. The constructor
  hazard is real one level down, on `ShulkerState` - see proto.3.)

## 2.4.0-proto.1

Prototype branch. Addresses every finding from the 2026-07-27 architectural
diagnostic against 2.3.1. Not deployed anywhere yet — see "Needs in-game
verification" below before treating it as a release.

Built for two targets from separate branches:

| Branch | Minecraft | Fabric API | Jar |
|---|---|---|---|
| `26.2_proto` | 26.2 | 0.153.0+26.2 | `wtf-2.4.0-proto.1_26.2.jar` |
| `master_proto` | 26.1.2 | 0.151.0+26.1.2 | `wtf-2.4.0-proto.1_26.1.2.jar` |

Each also ships a `_debug` variant, which now actually differs from the normal
jar (see Build below).

### Fixed — data loss

- **A corrupt save no longer destroys your marks.** `load()` swallowed every
  exception into a debug-only log, leaving `trackedShulkers` empty; the next
  tick sweep then overwrote the file with `{}`. Loading now falls back to a
  backup, and if nothing parses it blocks saving for the session and says so
  in chat instead of quietly replacing the file.
- **Saves are atomic.** Write to `moods.json.tmp`, rotate the previous save to
  `moods.json.bak`, then move the temp into place. A crash or power loss
  mid-write used to leave a truncated file, which is exactly the input the
  above bug then destroyed.
- **A failed write is reported**, not silently dropped.
- **The `version` field is read.** `SAVE_VERSION` is 16 and `migrate()` gates
  fixups on it; the pre-v15 `firstSeen` backfill now lives there. Previously
  the version was written and never looked at, so any format change had no
  safe path.

### Fixed — cross-world contamination

- **One `resetWorldState()`**, called from both disconnect and load. Twelve
  fields survived a server switch, including `persistedChestLedger` (keyed by
  `"minecraft:overworld:100_64_200"`, which collides freely between servers)
  and `slotLedger` (keyed by bare slot index). Joining a server with no save
  file could apply the previous server's slot→uuid map to matching coordinates.
- **Absolute tick deadlines are cleared too.** `tickCounter` resets to 0 on
  disconnect while `pendingDropEntities` held absolute spawn ticks, so their
  TTL check went negative and never fired — entries lived forever and could
  bind a recycled entity id in the new world to a tracked box.

### Fixed — identity resolution

- **One ladder, in one place.** `ShulkerIdentityResolver.resolveChestSlot` is
  now the only resolution path for container-held boxes: stamp → session slot
  ledger → persisted slot ledger → slot continuity → content hash.
- **The chest-open and chest-close paths disagreed.** Open ran the content-hash
  match *first*; close ran it *last*. Hash is the one signal that drifts (ender
  wipes the stamp on every reopen, server resync perturbs nested NBT), so
  open's ordering let it outrank slot continuity. Close's order won.
- **Render no longer mutates game state.** Stripping duplicate uuid stamps from
  inside the render path made the strip → rescan → re-stamp → strip loop run at
  frame rate. Render flags it; the tick handler repairs it once per tick.
- **Ender-chest detection no longer depends on where the crosshair drifted to.**
  It reads the block recorded at click time instead of `mc.hitResult` at
  screen-init, which is a full round-trip later (~250ms on the usual test
  server). Misdetection wrote ender boxes into a real chest's ledger.
- **Thread guards** on `onEntityRemoved` and `repairSlotUUIDs`. Both are
  reachable from packet handling and both immediately walk unsynchronized
  collections.

### Added — tests

- A test source set and 23 unit tests over `ShulkerIdentityResolver`, run by
  `./gradlew build`. They cover the tiebreak ordering, including that the
  result does not depend on map iteration order — the specific thing that used
  to swap identities between sessions. The project had zero automated tests.

### Fixed — build and packaging

- **The debug jar was a lie.** It shipped a `wtf_debug.flag` resource that no
  code read, so it behaved identically to the normal jar. `debugMode` now reads
  it.
- **`minecraft` dependency is version-specific** (`~26.2` / `~26.1.2`). The old
  `>=26.1.2` floor let the 26.2 build install on 26.1.2 and fail at mixin-apply
  with a stack trace instead of a clean version error.
- Contact URLs point at this repo instead of the Fabric example mod.
- Dropped the template `ExampleMixin`/`ExampleClientMixin` (empty no-ops still
  transforming `Minecraft.run()`) and the now-empty server mixin config.

### Fixed — performance

- `chooseRicherCache` decorates before comparing. It called a gzip decompress
  plus 27 codec parses from *inside a comparator*, on every chest close, screen
  close and place.
- The SHA-256 instance and the fingerprint scratch list are reused instead of
  reallocated per call. A double-chest close ran two full 54-slot passes.
- The left panel clips with `enableScissor` instead of re-rendering every
  section header a second time to hide scroll bleed-through.
- `log()` flushes once per tick instead of once per line.

### Fixed — smaller correctness

- Search box width is clamped; a narrow window or large GUI scale produced a
  negative `EditBox` width.
- The grid screen refreshes once a second instead of showing the snapshot taken
  when it opened, keeping scroll position, collapsed sections and selection.
- The chest close handler registers once per screen instance. `AFTER_INIT`
  fires again on window resize, which stacked a second handler.
- `AFTER_INIT` only scans on container and grid screens, not on the title,
  pause and options screens.
- `openChestPos` is canonicalized on both branches, not just the fallback.
- The `Optional`-vs-`String` `getString` handling is one helper instead of three
  copies whose `.toString()` fallback would have stamped the literal text
  `Optional[...]` on as a uuid.
- `SHULKER_SLOTS` replaces the hardcoded `27`, and an oversized container is
  logged when truncated rather than losing its tail silently.

### Removed

`nextSerial`, a statically dead `orphanUUID` branch, `resolveOrphanedChestSlot`
(no callers), `XaeroCompat.addTemporaryWaypoint` (superseded), vestigial
`scaledX`/`scaledZ`, `parseBlockLoc`, `keyToIndex`, `resetScroll`, an unused
import, a doc comment copied onto the wrong function, and the stale `bin/`
tree. `MAX_TRANSIT_SPARE` was declared but never used, so `transitOrder` grew
unbounded for a whole session and was walked per stack on every scan; it now
bounds the deque.

### Needs in-game verification

Everything above compiles and the unit tests pass, but the identity work only
proves itself against a live server. Before this leaves prototype:

1. Corrupt a `moods.json` by hand and confirm the chat warning appears, marks
   stay hidden, and the file is *not* overwritten.
2. Delete `moods.json` but keep `moods.json.bak`; confirm recovery.
3. Play server A, join server B with no save file, and confirm no marks or
   chest ledger entries carry over.
4. Resize the window with a chest open, then close it — the close handler must
   run once.
5. Ender chest: open it while turning away mid-click, on a high-latency server.
   Contents must not land in a nearby chest's ledger.
6. Two identical unnamed boxes in one chest, across a relog, must not swap.
7. `CHECKLIST.md`'s nine scenarios, which are still the only coverage for the
   inventory-scan, block-open and block-place paths — those three still resolve
   identity independently of the shared ladder.
