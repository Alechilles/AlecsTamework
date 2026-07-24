# ADR 0006: Public Persistence Import Policy

- Status: Accepted and implemented
- Date: 2026-07-23

## Decision

The replacement importer accepts only a consistent snapshot already classified as public schema
v2, v3, or v4. It performs one deterministic import transaction into a temporary replacement
database. It never migrates or updates a legacy database in place.

Stable profile, owner, NPC, and tool identifiers must parse as UUIDs and are copied exactly. The
importer does not generate replacements for malformed source identity. Cross-profile identity or
foreign-key corruption refuses the complete import because it cannot be safely contained.

Public profile data maps as follows:

- display name and role map to `companion_profile`;
- valid `state_json` maps exactly to `metadata_json` with a new SHA-256 integrity hash;
- `last_world_name` maps to the non-authoritative `last_known_world_key` reconciliation hint;
- owner maps only to `companion_lifecycle`;
- aliases receive deterministic generations ordered by source mapping time and UUID;
- exactly one consistent current alias remains `CURRENT`; historical aliases become `RETIRED`;
- tool links and API extension data retain their stable compound keys and signed timestamps;
- every legacy snapshot receives a deterministic target ID and SHA-256 payload hash;
- only one valid active snapshot per profile and kind becomes current.

The importer preserves `0` and negative timestamps. It does not fabricate gameplay operations or
projection events for historical player actions.

## Lifecycle resolution

One positive, internally complete evidence set maps to one canonical state:

| Public evidence | Replacement state and location |
| --- | --- |
| capture flag plus one valid active capture snapshot | `CAPTURED` / `CAPTURE_ITEM` |
| death flag plus one valid active death snapshot | `DEAD_REVIVABLE` / `NONE` |
| lost flag plus one valid active lost snapshot | `LOST` / `NONE` |
| coop flag, matching slot/key, and valid coop snapshot | `COOP` / `COOP_SLOT` |
| no positive dormant flag | `UNRESOLVED` / `UNRESOLVED` |

`UNRESOLVED` is deliberately non-mutating. Startup reconciliation may later prove live or unloaded
state from world evidence; the importer may not guess.

## Bounded conflict policy

When stable profile identity is intact but mutually exclusive flags, current aliases, required
snapshots, coop evidence, or JSON payloads conflict, the importer:

1. retains the profile and all validly attributable evidence;
2. writes canonical lifecycle as `UNRESOLVED`;
3. records one precise incident with escaped raw evidence;
4. applies a profile-scoped quarantine;
5. creates no active coop residency or authoritative current snapshot from disputed evidence.

Unbounded corruption, invalid stable identity, unreadable schema, failed integrity checks, and
cross-profile references refuse the entire import. No arbitrary priority order chooses a winner.

## Publication and idempotency

The import key is source snapshot SHA-256, public schema version, and importer version. A completed
target is verified for counts, hashes, lifecycle coverage, foreign keys, and SQLite integrity before
an atomic same-directory move publishes `tamework-state.sqlite`.

An existing target is never merged with a different source. Failed work remains isolated to an
owned temporary target and cannot enable mutation readiness. The legacy main database, WAL, and SHM
remain byte-for-byte unchanged.

## Import regression budget

The immutable representative-v4 fixture must import in at most 10 seconds on the development test
host and produce a replacement main database no larger than 2 MiB. These are regression ceilings,
not expected runtime: the fixture normally completes well below one second. Larger release-corpus
benchmarks must establish scaled production budgets before release packaging.
