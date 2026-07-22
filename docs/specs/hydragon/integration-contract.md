# HyDragon Integration Contract

Status: Proposed 3.x contract after the command-roster redesign

Related Tamework specs: [index](README.md), [capture policy](capture-policy.md), [command-roster capture and revival](command-roster-capture-revival.md), [population groups](population-groups.md), and deferred [companion inventory](companion-inventory.md).

HyDragon specs: [index](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/README.md), [capture and Dragon Horn](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/capture-summoning-maintenance.md), and [Miniwyvern](https://github.com/Alechilles/HyDragon/blob/main/docs/specs/soul-bond-miniwyvern.md).

## 1. Goal

Define the only supported boundary between Tamework 3.x and HyDragon. HyDragon consumes versioned public capabilities and immutable data. It does not link against internal Tamework services, duplicate canonical companion state, or infer feature readiness from the mod version alone.

## 2. Version and capability discovery

HyDragon declares Tamework `>=3.0.0 <4.0.0`. On startup it verifies the public API major version and queries capabilities independently.

Required capability IDs:

- `PROFILES`
- `PROFILE_DATA`
- `POLICY`
- `PERSISTENCE_RESILIENCE`
- `CAPTURE_POLICY`
- `POPULATION_GROUPS`
- `COMPANION_PROVISIONING`
- `COMMAND_FAMILY_ROSTERS`
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION`
- `CAPTURE_TAME_AND_LINK`
- `PAID_COMMAND_REVIVAL`

Optional/deferred:

- `COMPANION_INVENTORY`

Removed:

- `BONDED_VESSELS`

Each capability reports `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, or `UNSUPPORTED`, plus a stable reason and bounded incident ID. `AVAILABLE` means its complete runtime and persistence authority is ready. Tamework must withdraw or degrade a capability when its required circuit, repository, config registry, or recovery queue is not trustworthy.

## 3. Feature gates

| HyDragon feature | Required capabilities | Fail-closed behavior |
| --- | --- | --- |
| Probabilistic stone attempt | Profiles, policy, persistence resilience, capture policy, population groups, resolved-attempt consumption | Deny before channel completion/entropy; retain stone and target |
| Tame in place and add to Horn | Above plus command-family rosters and capture tame-and-link | Deny before roll; retain stone and target |
| Wyvern Egg claim | Profiles, persistence resilience, population groups, provisioning, command-family rosters | Deny before Egg consumption |
| Horn commands/roster | Profiles, policy, persistence resilience, command-family rosters | Disable roster mutation/positive relocation; preserve rows/profiles |
| Paid revival | Profiles, persistence resilience, population groups, command-family rosters, paid command revival | Disable Revive; do not reserve or consume essence |
| Elemental archetype data | Profiles and profile data | Disable attunement before essence consumption; retain existing profile data |
| Miniwyvern backpack | Companion inventory plus profile/lifecycle authority | Feature absent until later update |

Missing one feature's capability must not disable unrelated safe features. In particular, missing paid revival does not disable ordinary Horn commands, and missing backpack support does not disable the Miniwyvern.

## 4. Authority matrix

| State | Authority | Consumer rule |
| --- | --- | --- |
| Profile ID, owner, lifecycle, death/lost state | Tamework | HyDragon stores references only where its domain record requires them |
| Capture attempt/result/cooldown/source spend | Tamework | HyDragon supplies data and presentation; never pre-rolls or mirrors cooldown |
| Command-family membership and group/home preferences | Tamework | Horn metadata is cache only |
| Population group membership/admission | Tamework | HyDragon configures role rules; never maintains a second count |
| Revival quote, reservation, cost consumption, recovery | Tamework | HyDragon supplies item IDs/quantities and localized presentation |
| Soul Bond entitlement | HyDragon | Tamework provisions/links the requested profile idempotently |
| Miniwyvern archetype/ability state | HyDragon namespaced profile data | Tamework persists and revision-fences opaque domain values |
| Stone tiers, role difficulty, materials, Dragon Horn/Egg assets | HyDragon | Tamework treats IDs and values as content data |
| Companion inventory | Deferred Tamework authority | HyDragon does not implement a private backpack |

Live entity UUIDs, item slots, cached Horn rows, and client UI state are projections. They cannot substitute for canonical profile, roster, population, or operation records.

## 5. Idempotency and transaction ownership

Every cross-plugin mutation uses a namespaced caller key:

```text
hydragon:<operation-kind>:<stable-player-action-id>
```

The key is stable across callbacks, async continuation, retry, relog, and restart. Tamework maps it to its durable operation and returns the prior result for duplicates. HyDragon persists the same key when it owns part of a saga, such as the Soul Bond entitlement or attunement ledger.

Rules:

1. Preflight queries are advisory and side-effect-free.
2. Positive mutation uses prepare/claim/apply/commit or prepare/cancel.
3. Inventory consumption uses exact slot/stack/revision fencing and is never inferred from a later inventory snapshot.
4. A committed source spend or revival charge is never repeated.
5. A recorded capture result is never rolled again.
6. Listener exceptions cannot roll back committed state and cannot initiate compensating mutation implicitly.
7. Ambiguous outcomes remain pending until queried/recovered; callers do not guess that failure occurred.
8. Terminal compensation produces at most one durable refund/recovery claim and never both the gameplay result and a spendable refund.

## 6. Capture integration

HyDragon authors `TwSpawnerConfig` and role-scoped capture-policy assets. Tamework snapshots their revisions at attempt preparation.

For HyDragon stones:

```text
SourceConsumption = ResolvedAttempt
SuccessDisposition = TameAndCommandLink
CommandFamilyId = hydragon:dragon_horn
RequiredCommandConfigId = HyDragonDragonHorn
RequireCommandAccessItem = true
```

Tamework owns validation, attempt identity, entropy, exact source spend, cooldown, tame/owner/role/profile apply, population mutation, and Horn membership. HyDragon may register deterministic side-effect-free special encounter requirements and consume post-commit events for presentation/domain bookkeeping.

`CaptureAttemptResolvedEvent` includes attempt/operation IDs, config IDs/revisions, actor/target/profile identity, formula inputs, outcome, source-consumption status, command-family membership status for success, stable reason, and timestamps. Secret entropy is omitted from normal public payloads.

Failure before a roll emits no resolved event. A resolved failed roll reports one consumed source item. A successful event is emitted only after profile and roster membership commit.

## 7. Command-family roster integration

Tamework exposes immutable roster queries and idempotent mutations by acting owner, family ID, and profile ID. HyDragon's Dragon Horn uses `hydragon:dragon_horn` with `OwnerCommandFamily` storage.

Public operations include:

- list/status query;
- add/upsert membership;
- unlink membership;
- set active-for-bulk-command;
- assign command group;
- set/query home;
- resolve a compatible access item;
- refresh physical item projections where safe.

Authorization is evaluated from the acting owner and canonical profile. Supplying a copied item or cached row never grants authority. Queries usable for diagnostics accept an optional owner and must not require a live `Player` entity.

## 8. Provisioning integration

HyDragon's Wyvern Egg requests one owned `Tamed_Wyvern_Mini` profile, group `hydragon:soulbound_mini`, dormant initial lifecycle, and `hydragon:dragon_horn` membership under the Egg operation ID.

Provisioning returns:

- canonical profile ID and revision;
- resolved owner/group/family IDs;
- whether each mutation was newly applied or already committed;
- lifecycle/projection status;
- stable denial/recovery reason and incident ID.

Initial projection is separately admitted. Its failure does not undo a committed entitlement/profile/membership and does not authorize a replacement profile.

## 9. Paid revival integration

HyDragon supplies role-scoped cost data through `TwCompanionConfig.Command.Revive`. Tamework exposes:

- immutable revive quote;
- prepare/confirm request with actor, profile, command family, quoted config revision, and caller key;
- operation query/recovery result;
- post-commit paid-revival event.

Tamework owns ownership/roster/death/cooldown/population/placement validation, inventory reservation and consumption, profile recovery, projection, refund claims, and result persistence. HyDragon supplies localized labels/effects and may react to the post-commit event.

A quote is not a reservation. Confirmation revalidates the snapshotted config and exact inventory. A missing or changed source stack before consumption denies with no charge.

## 10. Profile data and HyDragon extensions

HyDragon registers namespaced profile-data schemas and optimistic mutations for elemental archetypes. Tamework treats the content as opaque except for size/version/safety limits.

An attunement operation:

1. resolves the canonical Miniwyvern profile;
2. fences profile-data revision and essence stack;
3. writes the new archetype under one HyDragon operation ID;
4. consumes one essence exactly once;
5. reconciles attachment/runtime presentation;
6. returns the existing result on retry.

No profile-data value may represent command roster authority, population membership, capture spend, or revival payment.

## 11. Threading and callbacks

- Entity/component mutation executes on the owning world thread.
- SQLite and other blocking persistence runs off the world thread.
- Async work carries immutable IDs, revisions, and snapshots, not stale `Player`, component, entity, or inventory references.
- Before apply, Tamework reacquires and validates current world/entity/inventory state.
- Public listeners are post-commit notifications unless explicitly registered as synchronous side-effect-free requirements.
- Listener order is deterministic; exceptions are isolated and diagnosed.

## 12. Reload semantics

- `/tw reloadconfig` refreshes `TwSpawnerConfig` and `TwCommandItemConfig`, including new capture/roster fields.
- `TwCompanionConfig` revival fields update through normal asset events.
- An in-flight operation remains bound to snapshotted config revisions.
- Invalid reload input does not replace the last valid compiled registry.
- Capability availability is recomputed after registry/persistence changes.

## 13. Diagnostics

Read-only health and operation diagnostics must run from the server console. Player identity is an optional filter.

Required surfaces include:

- `/tw api test`
- `/tw diagnose capture-attempt <id>`
- `/tw diagnose command-family [owner] [family]`
- `/tw diagnose provision <operation-id>`
- `/tw diagnose revive <operation-id-or-profile>`
- aggregate persistence/recovery/circuit health

HyDragon's diagnostics may query these immutable summaries and add domain context. Neither plugin may expose inventory contents, secret entropy, or unrelated player data in routine output.

## 14. Bonded-vessel removal contract

`BONDED_VESSELS`, `TameworkApi.bondedVessels()`, all vessel DTOs/events/config, binding state/generation persistence, runtime orchestration, diagnostics, examples, and schema documentation are deleted. HyDragon simultaneously removes its vessel calls and assets.

There is no HyDragon migration/adoption contract. Development-only worlds and items are unsupported. Tamework may perform only its own internal schema housekeeping required to remove obsolete development tables.

## 15. Acceptance criteria

1. Capability tests prove each new surface fails closed when unavailable and returns immutable values when ready.
2. Existing public API consumers that do not request new capabilities remain source/behavior compatible within the supported 3.x policy.
3. HyDragon capture cannot roll unless capture policy, spend, tame/link, roster, profile, and population authority are ready.
4. Failed capture spends one stone once; successful capture spends one stone once and commits one profile/roster membership.
5. Egg retries return one Miniwyvern profile and one Horn membership.
6. Roster queries and diagnostics work without a live player entity; mutations still require explicit authorization/context.
7. Paid revival charges once and restores the same profile once; terminal compensation creates at most one refund claim.
8. Config reload cannot alter in-flight capture/revival costs or semantics.
9. World-transfer, unload, death, lost recovery, item replacement, relog, and restart preserve profile and roster identity.
10. Missing listener, listener exception, cosmetic failure, or UI refresh failure cannot corrupt committed state.
11. Repository and packaged-artifact scans find no bonded-vessel API/config/runtime/docs other than the withdrawn-design notice.
12. Cross-repository tests exercise all failure checkpoints and prove no free roll, double spend, duplicate profile, duplicate projection, or lost paid item.
