# Command Rosters, Timed Summoning, Capture, and Revival

Status: implemented and independently capability-gated; exact live acceptance
pending

## Goal

Make the Dragon Horn a durable roster interface rather than a companion storage
item. Preserve one canonical profile across capture, active projection, roster
storage, unload, loss, death, and paid revival.

## Command-family roster authority

One roster is identified by `(owner_uuid, family_id)`. One membership contains:

- stable unique slot ID;
- stable unique profile ID;
- family owner and ID;
- membership revision;
- optional command group;
- bulk-command selection;
- optional complete home world/position.

Membership does not copy role, display name, alias, owner-world, lifecycle,
location, active count, lease, operation phase, or recovery state.

`command_family` owns only family identity/revision. Canonical profile and
lifecycle joins supply current presentation and action authority.

A profile cannot occupy two roster slots or families. Dead, lost, unloaded,
active, provisioned, and roster-stored states preserve the same membership
unless an explicit authorized unlink succeeds.

The Dragon Horn is an access item. Multiple copies held by the same owner expose
the same roster. A copied/dropped Horn cannot transfer, fork, suppress, or
delete canonical membership.

HyDragon uses:

```text
CommandConfigId = HyDragonDragonHorn
CommandFamilyId = hydragon:dragon_horn
RosterStorage = OwnerCommandFamily
MembershipMode = LinkedOnly
RequireOwner = true
RequireTamed = true
```

## Roster lifecycle

Add canonical:

- `PROVISIONED_DORMANT` at `PROVISIONING`;
- `ROSTER_STORED` at `COMMAND_ROSTER`.

`ROSTER_STORED` means an owned roster profile has no live projection and no
captured item. Its current full snapshot remains in the shared snapshot
authority.

`RESTORING` and `STORING` remain shared operation phases/fences, not lifecycle
states. During storage, canonical lifecycle remains potentially live until
source retirement and roster storage commit, so active capacity cannot be
released early.

## Roster operations

Two shared operation kinds are sufficient:

1. Membership mutation creates, updates, or removes one exact slot.
2. Roster transition changes canonical lifecycle for one exact membership and
   composes positive population admission when required.

Roster mutations, lifecycle changes, group reservations, operation evidence,
and outbox events commit atomically. There is no roster operation/receipt table,
feature phase graph, listener-backed committed cache, or independent recovery
queue.

## Timed summon lease

One `timed_summon_lease` row per profile stores only:

- lease revision;
- optional active session ID;
- nonnegative remaining active duration;
- optional signed cooldown deadline;
- snapshotted config ID/revision and policy values;
- durable warning receipts;
- optional signed checkpoint timestamp;
- creation/update timestamps.

It does not copy roster, profile, owner, role, alias, lifecycle, location,
operation phase, active count, or recovery state.

Role-scoped configuration supplies:

```json
{
  "Command": {
    "Summon": {
      "Enabled": true,
      "ActiveDurationMs": 600000,
      "ResummonCooldownMs": 300000,
      "AutoStoreOnOwnerLogout": true,
      "ExpiryWarningThresholdsMs": [60000, 30000, 10000]
    }
  }
}
```

Zero active duration is generic unlimited behavior. HyDragon full dragons use
a positive duration.

## Summon

Summon:

1. validates exact owner, membership, `ROSTER_STORED` lifecycle, stored
   snapshot, lease/cooldown, group policy, config, and destination;
2. freezes a world-qualified safe placement before preparation;
3. reserves active capacity and leases a planned alias;
4. applies one receipt-addressed spawn on the destination world thread;
5. atomically promotes the alias, retires the stored snapshot, commits
   `ACTIVE`, starts one session, retires reservations, and emits outbox events.

Recovery reuses the frozen placement and same receipt. It does not choose a new
destination or create a second projection.

## Dismiss, expiry, and logout storage

All storage triggers use the same operation:

1. validate exact active/unloaded lifecycle, alias, membership, lease/session,
   policy, and source world;
2. checkpoint nonincreasing remaining duration;
3. capture a complete canonical snapshot;
4. durably fence and retire the exact live projection;
5. atomically install the snapshot, retire the alias, commit `ROSTER_STORED`,
   clear the session, start cooldown, and publish;
6. release active capacity only in that final commit.

Failure leaves one recoverable operation and occupied active capacity. It cannot
authorize another summon.

Owner logout stores configured dragons. The next successful summon starts a new
full lease after cooldown. Downtime does not consume stored or pending time.

## Time semantics

- Remaining duration is elapsed time measured from a monotonic process clock.
- Server downtime neither consumes nor replenishes it.
- World timestamp deadlines preserve sign; zero/null semantics are explicit.
- Recall, relocation, command changes, mounting, chunk unload, and UI refresh do
  not reset a running lease.
- Warning thresholds are positive, unique, descending, and durably receipted
  per session.

## Tame-and-command-link capture

The full operation is defined in
[capture-policy.md](capture-policy.md). Its successful durable transaction must
leave:

- one stable profile and current live alias;
- canonical `ACTIVE` lifecycle;
- exact owner and tamed role;
- one population classification/admission;
- one Dragon Horn membership;
- one active timed lease;
- one recorded capture result/source spend;
- no filled Draconic Stone.

No callback chain may commit roster, lease, and capture independently.

## Death

Death preserves profile and roster membership, records the exact death snapshot,
commits `DEAD_REVIVABLE`, ends active timed projection consistently, and releases
active capacity only with canonical durable evidence. It does not create or
damage a stone.

## Paid command revival

Role-scoped configuration supplies an ordered AND-list:

```json
{
  "Command": {
    "Revive": {
      "Enabled": true,
      "GameplayCooldownMs": 0,
      "Costs": [
        { "ItemId": "Revitalizing_Essence", "Quantity": 2 },
        { "ItemId": "Draconic_Essence", "Quantity": 4 }
      ]
    }
  }
}
```

The item IDs and quantities are content data. Tamework does not hardcode an
essence type.

A quote is read-only and shows every item, required quantity, owned quantity,
and shortage. Confirmation revalidates:

- acting owner and exact roster membership;
- `DEAD_REVIVABLE` lifecycle and death snapshot;
- config/cost revision;
- exact inventory sources;
- population active admission;
- safe frozen placement;
- alias and optional timed-lease transition.

One shared operation owns exact charge plus same-profile restoration. Durable
success consumes every cost component once, promotes one alias, retires the
death snapshot, commits `ACTIVE`, starts one lease, and publishes once.

A positively proven charge with positively proven terminal spawn absence may
create one normalized generic multi-item refund recipe. Absence alone is not
proof. An ambiguous result remains contained and never guesses a refund or
authorizes a second revival.

Free restoration remains available for ordinary configured Tamework companions
outside paid command revival.

## Public capability behavior

- `COMMAND_FAMILY_ROSTERS` requires canonical roster/query/recovery readiness.
- `COMMAND_TIMED_SUMMONING` additionally requires population, snapshot, alias,
  live-boundary, and lease readiness.
- `PAID_COMMAND_REVIVAL` additionally requires roster, population, death,
  inventory receipt, refund, placement, alias, and timed readiness.

Unavailable capability disables only dependent positive actions. Existing
canonical rows remain visible and unrelated safe features remain available.

## Acceptance

- copied Horns expose one authoritative roster;
- one profile cannot occupy two slots;
- death/loss/unload preserve membership;
- summon and storage do not duplicate or lose a projection at crash seams;
- lease time does not reset through reload/restart/world movement;
- capacity remains occupied until storage commits;
- failed paid revival charges nothing;
- successful paid revival charges once and restores the same profile once;
- proven terminal compensation creates one exact refund;
- no bonded-vessel item state, roster operation table, timed session table, or
  paid-revival journal returns.
