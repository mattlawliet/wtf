# Shulker State Machine Test Checklist

## Log Filtering
```
tail -f debug.log | grep "transition:"
```

---

## 1. Place shulker in world
**Steps:**
1. Hold tracked shulker in hand
2. Right-click to place
3. Check `transition:` lines

| Expect | Log |
|---|---|
| Existing tracked shulker placed | `transition: MyBox (abcd1234) inv → block from=place:existing slot=X` |
| New shulker placed (first time) | `transition: MyBox (abcd1234) new → block from=place:new slot=X` |

---

## 2. Break shulker block → pick up
**Steps:**
1. Break placed shulker block
2. Wait for item entity to appear
3. Walk over to pick it up
4. Check `transition:` lines

| Expect | Log |
|---|---|
| Drop from block | `transition: MyBox (abcd1234) block → item from=block_break` |
| Pick up | `transition: MyBox (abcd1234) item → inv from=entity_removed` |

---

## 3. Drop shulker → pick up
**Steps:**
1. Hold shulker, press Q to drop
2. Walk over to pick it up
3. Check `transition:` lines

| Expect | Log |
|---|---|
| Entity spawns as drop | `transition: MyBox (abcd1234) inv → item from=spawn:drop` |
| Pick up | `transition: MyBox (abcd1234) item → inv from=entity_removed` |

---

## 4. Hopper extraction → last known (ex-inv)
**Steps:**
1. Place shulker in chest with hopper below
2. Hopper sucks it out
3. Close chest
4. Check `transition:` lines

| Expect | Log |
|---|---|
| Scan finds it gone from inv | `transition: MyBox (abcd1234) inv → ex-inv from=chest:vanished_on_insert` |
| Scan cleanup catches it | `transition: MyBox (abcd1234) inv → ex-inv from=scan:cleanup lastKnown=false` |

---

## 5. Retrieve LK shulker from same chest
**Steps:**
1. Open chest where shulker was LK'd
2. Take shulker out
3. Close chest
4. Check `transition:` lines

| Expect | Log |
|---|---|
| Self-correction recovers it | Need to check: either `chest:take_to_inv` or `scan:ex_inv_recovery` |
| If recovery: | `transition: MyBox (abcd1234) ex-inv → inv from=scan:ex_inv_recovery lastKnown=true` |

---

## 6. Two same-name different-content shulkers (no swap)
**Steps:**
1. Have A (tracked) and B (untracked, same name, different contents)
2. Put A in hopper chest → A gets LK'd
3. B in inventory
4. Close chest
5. Check `transition:` lines

| Expect | Log |
|---|---|
| A transitions to ex-inv | `transition: A (uuidA) inv → ex-inv from=chest:vanished_on_insert` |
| B gets own UUID (new entry) | `scan: new shulker discovered B (uuidB) ...` |
| **NO** identity swap | No `transition: A ...` → `inv` from B's slot |

---

## 7. Untracked shulker in chest (no toast)
**Steps:**
1. Open chest with untracked shulker inside
2. Close chest
3. Check screen — no toast notification

| Expect | Log |
|---|---|
| No toast on screen | (visual check) |
| Logged at debug level | `handleChestClosed: new untracked shulker discovered in chest` |

---

## 8. Server sync UUID wipe → no false LK
**Steps:**
1. Place multiple shulkers in inventory
2. Open chest (triggers server CUSTOM_DATA wipe)
3. Close chest
4. Check `transition:` lines

| Expect | Log |
|---|---|
| Bystander shulkers (not moved) stay inv | No `transition:` lines for them, or only `from=scan:uuid (noop)` |
| Only the shulker moved to chest transitions | `transition: MyBox (abcd1234) inv → ex-inv from=chest:vanished_on_insert` |
| No false `lastKnown=true` on bystanders | All bystander log lines show `lastKnown=false` if any appear |

---

## 9. Content-hash recovery across containers
**Steps:**
1. Have shulker A get ex-inv'd (in hopper chest)
2. Move identical-content shulker B to player inventory
3. Run inventory scan
4. Check `transition:` lines

| Expect | Log |
|---|---|
| B gets A's UUID injected | `transition: B (uuidA) ex-inv → inv from=scan:ex_inv_recovery lastKnown=true/false` |
| A's state recovers to inv | B now carries A's identity |

---

## Recording
For each test, paste the relevant `transition:` lines below and mark pass/fail.

| # | Test | Pass/Fail | Notes |
|---|---|---|---|
| 1 | Place shulker | | |
| 2 | Break → pick up | | |
| 3 | Drop → pick up | | |
| 4 | Hopper → LK | | |
| 5 | Retrieve LK | | |
| 6 | Same-name no swap | | |
| 7 | Untracked in chest | | |
| 8 | Sync no false LK | | |
| 9 | Cross-container recovery | | |
