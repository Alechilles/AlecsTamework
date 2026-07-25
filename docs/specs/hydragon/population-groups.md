# Population Groups and Companion Provisioning

Status: required recovery contract; implementation in progress

## Goal

Provide one durable, data-driven authority for owned and active companion
limits, including HyDragon's full-dragon active limit and unique Soul Bond
Miniwyvern. Provide idempotent creation of an owned dormant companion without
creating a second lifecycle or provisioning journal.

## Authority

- `companion_profile` owns role and descriptive metadata.
- `companion_lifecycle` owns owner, owner-world bucket, lifecycle, location,
  revision, and active operation fence.
- Group classification explains which policies apply to a profile.
- Counts are canonical joins or rebuildable projections, never copied committed
  counters.
- Positive reservations belong to the existing shared operation envelope and
  have no independent phase.

## Group configuration

`TwPopulationGroupConfig` supplies:

- stable namespaced group ID;
- role selectors and deterministic priority;
- `GLOBAL` or `PER_WORLD` scope;
- nonnegative `MaxOwnedPerOwner`;
- nonnegative `MaxActivePerOwner`;
- enabled/config revision evidence.

Zero means unlimited. Higher priority wins duplicate logical group IDs; stable
asset-ID ordering breaks equal-priority ties. A role maps to the complete
sorted, unique winning group set.

HyDragon initially requires:

```json
{
  "GroupId": "hydragon:full_dragons",
  "Limits": {
    "MaxOwnedPerOwner": 0,
    "MaxActivePerOwner": 1,
    "Scope": "Global"
  }
}
```

and a separate Soul Bond group that admits at most one owned Miniwyvern per
owner.

## Classification

One classification row records the exact profile role, policy revision,
metadata revision, lifecycle revision, and assignment revision. Zero or more
normalized membership rows store only group ID and scope.

An explicit empty classification is valid and distinguishable from a profile
that has never been classified. Membership never copies owner, lifecycle,
owner-world, role, or counts.

## Admission

Every capacity-increasing operation:

1. resolves the authoritative complete group policy snapshot from the role;
2. validates exact profile/lifecycle/owner/owner-world/config revisions;
3. derives positive owned and active deltas for every group and owner scope;
4. counts committed canonical membership plus positive reservations on
   nonterminal operations;
5. reserves all required buckets atomically or none;
6. revalidates the same evidence immediately before live apply;
7. retires reservations in the transaction that commits canonical success.

Negative transitions do not release headroom before canonical durable commit.
`ADMIN_OVERRIDE` does not bypass group limits used as uniqueness constraints.

Active classification includes any lifecycle/operation state that may still
have a live projection. Storage retains active capacity until exact
snapshot/despawn evidence and `ROSTER_STORED` commit.

## Durable owner population

Global and per-world owner caps use the same model:

- lifecycle remains committed authority;
- owner population reservations are focused operation participants;
- owner-world survives dormant physical states;
- sealed same-generation live/disk evidence is required before absence may
  reconcile state;
- contradictions quarantine the narrowest safe profile/owner scope;
- a failed or incomplete evidence source never frees capacity.

## Provisioning

`COMPANION_PROVISIONING` creates an owned canonical profile from:

- caller namespace and stable idempotency key;
- owner UUID;
- authoritative target role;
- optional command-family membership;
- resolved population policy;
- retained owner-world evidence;
- optional diagnostic correlation ID.

The caller origin deterministically addresses one profile and one immutable
provenance record.

Dormant creation is one database-only operation that atomically commits:

- profile and metadata;
- `PROVISIONED_DORMANT` lifecycle at `PROVISIONING`;
- immutable provenance;
- owner/group reservations and final classification;
- optional Dragon Horn membership;
- outbox and operation evidence.

Initial live projection is a separate idempotent activation operation. It
freezes destination placement, reserves active capacity, leases the target
alias, applies one spawn receipt, commits `ACTIVE`, and optionally begins the
first timed lease.

A failed activation preserves the one granted dormant profile and does not
authorize another profile or another Egg claim.

## Bonded Miniwyvern contract

The Soul Bond itself is HyDragon-owned entitlement state. Tamework owns the
provisioned companion.

HyDragon's Wyvern Egg requests one `Tamed_Wyvern_Mini` profile in the Soul Bond
group with `hydragon:dragon_horn` membership. A claimed, pending, dormant,
active, unloaded, lost, dead, or recoverable profile denies another Egg claim
without consuming the Egg.

This is not the removed bonded-vessel item system:

- no persistent vessel item binds to the profile;
- no filled/active/damaged/lost item states exist;
- the Egg is an entitlement source, not companion storage;
- the Dragon Horn is roster access, not canonical authority.

## Capability and failure behavior

`POPULATION_GROUPS` is available only when config, canonical reads,
reservations, classification, projection rebuild, recovery, and diagnostics are
ready. `COMPANION_PROVISIONING` additionally requires profile/lifecycle and its
configured roster dependencies.

Unavailable authority denies positive work before source consumption. Existing
profiles remain readable and conservative capacity remains occupied while
evidence is ambiguous.

## Acceptance

- concurrent requests cannot over-admit the final slot;
- every matching group admits atomically;
- per-world dormant ownership retains its bucket;
- role/group changes move all buckets atomically;
- unique Miniwyvern claims create one profile and one roster row;
- repeated provisioning callbacks return the same profile;
- activation failure preserves the entitlement and starts no lease;
- restart/projection rebuild returns the same counts and classifications;
- no old provisioning/population phase graph or mutable committed counter
  returns.
