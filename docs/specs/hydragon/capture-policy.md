# Capture Policy and Durable Resolved Attempts

Status: bonded capture implementation and automated contract coverage complete;
clean package verification and fresh-world acceptance pending

## Goal

Allow an item to opt into generic probabilistic capture while preserving
ordinary deterministic filled-spawner behavior by default. HyDragon Draconic
Stones additionally require one durable terminal result, exactly-once
configured source consumption on success or failure, and durable storage in the
separate bonded Horn roster before source removal.

## Existing probability policy

- `ChanceMode: Guaranteed` bypasses role capture policy.
- `ChanceMode: Probability` combines item power/chance settings with the
  resolved `TwCapturePolicyConfig` for the target role.
- Role policy may supply minimum power, resistance, chance multiplier,
  missing-health bonus, guaranteed power, and side-effect-free registered
  requirements.
- Invalid probability input or a missing required requirement handler denies
  before entropy.
- Existing configs that omit the new source/disposition settings retain
  success-only ordinary filled-spawner behavior.

## Required source and success policies

HyDragon uses:

```text
SourceConsumption = ResolvedAttempt
SuccessDisposition = StoreBondedCompanion
BondedRosterId = hydragon:dragon_horn
RequiredCommandConfigId = HyDragonDragonHorn
RequireCommandAccessItem = true
RequiredEffectId = Tw_Status_Tranquilized
```

`ResolvedAttempt` consumes exactly one source item for either terminal result.
`StoreBondedCompanion` creates one durable `STORED` profile, retires the source
NPC, and produces no filled item. Current Draconic capture requires
tranquilized state and intentionally has no health-percentage threshold.

## Attempt boundary

1. Preflight validates actor, target, role/effect, range, source stack, Dragon
   Horn access, bonded family/capacity, configuration readiness, and required
   capability readiness. It consumes nothing and obtains no entropy.
2. Channel cancellation or eligibility loss before terminal resolution consumes
   nothing and obtains no entropy.
3. Terminal preparation freezes the target snapshot, source fingerprint,
   policy/config generation, formula inputs, bonded roster/family evidence,
   source world, and idempotency key.
4. One durable operation resolves exactly one terminal result.
5. Exact source retirement and the recorded result use receipt-addressed,
   restart-safe evidence.
6. Duplicate callbacks and restart recovery return the same result without
   another roll or source decrement.

## Failed roll

A failed roll:

- consumes exactly one source under `ResolvedAttempt`;
- records one failure cooldown;
- leaves the target alive, wild, untamed, unowned, and otherwise unchanged;
- publishes feedback only after the result and source spend are durable.

Inventory failure before a durable spend/result does not grant a free result.
Ambiguous spend evidence remains contained under the same operation.

## Successful bonded storage

A successful HyDragon result owns:

- the complete source snapshot before removal;
- tamed role transformation and exact owner evidence;
- one stable profile in roster `hydragon:dragon_horn` and family
  `hydragon:full_dragons`;
- initial state `STORED` with no lease or live projection;
- profile-lifetime proof that the original source UUID was captured once;
- exact source-item spend and bounded source cleanup; and
- post-commit event and one completion feedback dispatch.

The operation does not create a generic profile/lifecycle, population row,
command-family membership, generic timed lease, generic outbox entry, or filled
Draconic Stone. The durable profile commit precedes source cleanup. If cleanup
must retry, its exact source/world/profile evidence remains bounded and cannot
authorize a second profile.

If a source spend is positively proven but an internal failure makes successful
bonded storage terminally impossible, the operation may create one exact
replacement-item refund claim. Successful capture and a spendable refund are
mutually exclusive.

## Capability boundary

- `CAPTURE_POLICY` covers configuration, deterministic policy resolution, and
  side-effect-free requirement registration.
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION` is advertised only when the durable
  terminal result/source-spend path is ready.
- `BONDED_COMPANIONS` supplies profile, family admission, snapshot, source
  proof, cleanup, and result recovery for `StoreBondedCompanion`.

HyDragon must require every needed capability before completing channeling. It
must not pre-roll, mirror cooldown, or write Tamework state.

## Acceptance

- invalid/interrupted attempts obtain no roll and consume nothing;
- failed and successful eligible attempts consume exactly one configured
  source;
- retry/restart never rerolls;
- failed rolls do not mutate the target;
- successful capture leaves one stored bonded profile, no live source or
  projection, and no generic persistence row;
- exactly one completion effect is emitted after durable success;
- stale config/source/target evidence fails before irreversible mutation;
- listener, UI, sound, or particle failure cannot change the durable result.
