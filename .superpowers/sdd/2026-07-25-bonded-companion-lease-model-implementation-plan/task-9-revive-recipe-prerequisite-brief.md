# Task 9 prerequisite brief: ordered bonded revive recipes

## Scope

Integrate the already-reviewed multi-item bonded-revival work from
`414ce168..44e3db0a` onto the routed Tamework head `b3940ff6` without
altering generic paid revival, HyDragon assets, or the bonded SQLite schema.

## Required contract

- `ReviveCosts` is one immutable, ordered AND recipe. Omission inherits and
  an explicit array replaces its parent; empty, duplicate, or nonpositive
  recipes are unavailable or invalid as appropriate.
- Quote, UI, transition authority, request hash, terminal verification, and
  restart recovery retain the complete ordered recipe.
- One operation-scoped escrow preflights every line, reserves or refunds the
  entire recipe, and never recharges a replay.
- Only the server-authored current policy recipe may authorize revival.
- Successful revival is `DEAD -> STORED` and never summons.
- No compatibility alias, migration, schema change, HyDragon asset change, or
  generic paid-revival dependency is introduced.

## Integration gate

Preserve the routing-authority commits, keep every bonded production class at
or below 500 lines, isolate new multi-item inventory scenarios in a focused
test class, and pass the bonded/config/escrow/recovery and ECS/thread gates.
