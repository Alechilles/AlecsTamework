# API 0.9 and HyDragon integration

This contributor reference records the Tamework 3.0.0 integration surface
introduced for HyDragon. The public API contract is experimental version
`0.9.0`; the mod version and API version are independent.

## Availability contract

The presence of a Java type is not proof that its runtime authority is ready.
Consumers must inspect `TameworkApi.getCapabilities()` before using each
surface. Default API methods fail closed to preserve binary compatibility with
API 0.8 implementations.

| Surface | Capability | Fail-closed accessor or result |
| --- | --- | --- |
| Capture policy | `CAPTURE_POLICY` | Config views are empty and capture-requirement registration is unsupported when unavailable. |
| Bonded vessels | `BONDED_VESSELS` | `bondedVessels()` returns the unavailable facade. |
| Population groups | `POPULATION_GROUPS` | Group reads are empty/unavailable and `tryAdmitV2` denies. |
| Companion provisioning | `COMPANION_PROVISIONING` | `companionProvisioning()` returns the unavailable facade. |
| Revision-fenced profile data | `PROFILE_DATA_TRANSACTIONS` | Versioned reads are empty, CAS is unavailable, and operation lookup is empty. |

Do not infer availability from `getApiVersion() == "0.9.0"`. A runtime
advertises one of these capabilities only when its authoritative persistence,
recovery, and mutation path is installed.

Tamework 3.0.0 installs production implementations for all five API 0.9
surfaces, but advertises each capability independently:

- `PROFILE_DATA_TRANSACTIONS` requires the migrated profile-data repository and
  its idempotent operation journal.
- `CAPTURE_POLICY` requires bounded capture-attempt recovery and the same
  recovered coordinator used by gameplay capture.
- `POPULATION_GROUPS` requires successful group-operation recovery,
  reconciliation, and installation on the canonical owner-population mutation
  paths.
- `COMPANION_PROVISIONING` requires recovered population/provisioning journals
  plus both dormant-profile and requested active-projection authority.
- `BONDED_VESSELS` requires recovered vessel operations plus exact inventory,
  projection-evidence, canonical-profile, unified-population, and world
  mutation authority.

If any prerequisite is unavailable or degraded, that capability is omitted and
its public facade continues to fail closed. `/tw diagnose` reports the result
for the packaged server; the presence of schema tables or Java classes is not
readiness evidence.

## Configuration families

- `TwCapturePolicyConfig` assets live under
  `Server/Tamework/CapturePolicies/*.json`.
- `TwPopulationGroupConfig` assets live under
  `Server/Tamework/PopulationGroups/*.json`.
- Probabilistic item mechanics are opt-in fields inside
  `TwSpawnerConfig.Capture`; legacy/default `ChanceMode: Guaranteed` bypasses
  role policy and preserves deterministic capture.
- Bonded-vessel config views and APIs are part of the 0.9 contract. An
  integration may author bonded configuration, but it must not invoke bonded
  mutations unless `BONDED_VESSELS` is advertised.

Both new standalone families use normal asset loaded/removed events. A failed
rebuild retains the last valid compiled index. Parent fallback follows the
standard Tamework rules: omitted fields inherit, partial objects inherit
missing nested fields, and explicit arrays/maps replace rather than merge.

## Durable identity and retries

`profile_id` remains the canonical companion identity. Live NPC UUIDs and item
locations are projections. API 0.9 operations use a caller namespace plus a
stable idempotency key; a retry of the same gameplay intent must reuse that
pair.

Bonded-vessel operations expose restart-visible states including `APPLIED` and
`TERMINAL_DENIED`. `APPLIED` is not permission to refund or begin another
operation: the caller must query/resume the original operation. Only a proven
terminal pre-apply denial may authorize compensation in the caller's domain.

Provisioning commits at most one canonical profile. If an active projection
fails after dormant creation, the operation reports `PARTIAL_DORMANT` and a
retry resumes that profile rather than creating another one.

Profile-data transactions add versioned reads and idempotent compare-and-set.
Queue acceptance is not success: a caller acts only on durable `COMMITTED`,
`TERMINAL_DENIED`, or `QUARANTINED` evidence and uses `findOperation` after a
restart or timeout.

## Schema v8

Schema v8 adds durable tables and indexes for:

- exactly-once capture attempts and failure cooldowns;
- generation-fenced bonded-vessel bindings and operations;
- population-group classifications, assignments, operations, and count
  evidence; and
- companion-provisioning operations.

The migration is additive and idempotent. Startup requires the schema-v8 marker
before a read-only storage recovery probe can restore healthy mutation state.
Diagnostics include these operation domains and integrity checks for duplicate
origins, profiles, and nonterminal generations.

Do not hand-edit schema-v8 journals or counters. Use `/tw debugdb health`,
`/tw debugdb incidents`, `/tw debugdb incident <id>`,
`/tw debugdb retry <id>`, `/tw debugdb integrity`, and a redacted support
export when investigating persistence state.

## HyDragon boundary

HyDragon requires Tamework `>=3.0.0 <4.0.0`, obtains the API through
`Tamework.getInstance().getApi()`, and gates each feature independently.

- Ordinary Draconic Stones may use probabilistic capture and bonded vessels.
- Miniwyverns are Soul Bond-exclusive and must not appear in ordinary capture
  allowlists.
- Soul Bond uses generic companion provisioning and population-group limits;
  it does not create a parallel HyDragon profile authority.
- Flight uses Tamework's `Tamework_Flightmasters_Talisman`.
- The Miniwyvern backpack/companion-inventory system is deferred and is not an
  API 0.9 or schema-v8 capability.

HyDragon owns dragon content, balance, recipes, Soul Bond entitlement, repair
materials, elemental behavior, and presentation. Tamework owns canonical
profiles, admission, lifecycle, bindings, operation journals, and recovery.

## Verification

Source-level verification:

```text
./mvnw test
./mvnw package
```

Live operator verification uses `/tw api test run hydragon-integrations` and
the existing `/tw debugdb` commands. `/tw diagnose` prints the advertised
capability set, each HyDragon integration-authority state, and persistence
health. A capability must not be described as live merely
because its DTOs, schema tables, or unit tests exist; the packaged runtime must
advertise it and its feature-specific live checks must pass.

The HyDragon suite needs no prepared live fixture. It uses isolated,
deterministic fixtures to prove guaranteed capture without entropy,
duplicate-safe failed capture, stale bonded-generation rejection, group-cap
boundary denial, dormant and active provisioning, and restart-style lookup of
one failed projection retained as `PARTIAL_DORMANT`.

For focused read-only inspection, use `/tw diagnose population`,
`/tw diagnose vessel <binding-or-profile>`, or
`/tw diagnose provisioning <caller-namespace> <idempotency-key>`. These views
are bounded and sanitized; they do not retry, repair, or expose raw item JSON or
owner UUIDs.

## Public documentation

- [Public API Overview](../wiki/Modder-Documentation/Public-API/API-Reference/Public-API-Overview.md)
- [Capture Policy API Reference](../wiki/Modder-Documentation/Public-API/API-Reference/Capture-Policy-API-Reference.md)
- [Bonded Vessels API Reference](../wiki/Modder-Documentation/Public-API/API-Reference/Bonded-Vessels-API-Reference.md)
- [Population Groups API Reference](../wiki/Modder-Documentation/Public-API/API-Reference/Population-Groups-API-Reference.md)
- [Companion Provisioning API Reference](../wiki/Modder-Documentation/Public-API/API-Reference/Companion-Provisioning-API-Reference.md)
- [HyDragon Integration Guide](../wiki/Modder-Documentation/System-Integration/HyDragon-Integration-Guide.md)
