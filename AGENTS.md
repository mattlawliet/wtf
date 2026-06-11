## State machine logging

All state transitions must use `transition:` prefix with format:
`transition: <name> (<uuid_prefix>) <old_state> → <new_state> from=<context> [key=value ...]`

Filter debug log with: `grep "transition:"`

## Build
`./gradlew build` — verify before considering work done.

## Key invariants
- UUIDs in CUSTOM_DATA get wiped by server sync packets — never use `hashItemAndComponents` across sync boundary
- Use `fingerprintFromItem` (type+name+container only) for content comparison
- `entry.lastKnown` = true if LK'd by chest/vanished, false if scan-orphaned
