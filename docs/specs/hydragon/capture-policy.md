# Capture Policy and Durable Resolved Attempts

Status: implemented and independently capability-gated; exact live acceptance
pending

## Goal

Allow an item to opt into generic probabilistic capture while preserving
ordinary deterministic filled-spawner behavior by default. HyDragon Draconic
Stones additionally require one durable terminal result, exactly-once
configured source consumption on success or failure, and in-place
tame-and-command-link success.

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
SuccessDisposition = TameAndCommandLink
CommandFamilyId = hydragon:dragon_horn
RequiredCommandConfigId = HyDragonDragonHorn
RequireCommandAccessItem = true
```

`ResolvedAttempt` consumes exactly one source item for either terminal result.
`TameAndCommandLink` preserves the target entity instead of producing a filled
item.

## Attempt boundary

1. Preflight validates actor, target, role, health/effects, range, source
   stack, Dragon Horn access, population policy, configuration readiness, and
   required capability readiness. It consumes nothing and obtains no entropy.
2. Channel cancellation or eligibility loss before terminal resolution consumes
   nothing and obtains no entropy.
3. Terminal preparation freezes the target/profile revisions, source
   fingerprint, policy/config revisions, formula inputs, population/roster/timed
   participants, and idempotency key.
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

## Successful tame/link

A successful HyDragon result atomically composes:

- the existing target's stable profile and current alias;
- tamed state, owner, configured role transformation, and spawn-authority
  detachment on the owning world thread;
- canonical active lifecycle;
- population-group classification and active admission;
- one `hydragon:dragon_horn` roster membership;
- one initial timed summon lease;
- source-spend evidence and post-commit event publication.

The target remains live at its capture location. The operation does not run the
ordinary captured-item finalizer and does not create a filled Draconic Stone.

If a source spend is positively proven but an internal failure makes successful
tame/link terminally impossible, the operation may create one exact
replacement-item refund claim. Successful capture and a spendable refund are
mutually exclusive.

## Capability boundary

- `CAPTURE_POLICY` covers configuration, deterministic policy resolution, and
  side-effect-free requirement registration.
- `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION` is advertised only when the durable
  terminal result/source-spend path is ready.
- `CAPTURE_TAME_AND_LINK` is advertised only when profile, lifecycle,
  population, roster, timed lease, live apply, and recovery are all ready.

HyDragon must require every needed capability before completing channeling. It
must not pre-roll, mirror cooldown, or write Tamework state.

## Acceptance

- invalid/interrupted attempts obtain no roll and consume nothing;
- failed and successful eligible attempts consume exactly one configured
  source;
- retry/restart never rerolls;
- failed rolls do not mutate the target;
- successful capture leaves one live projection, one profile, one roster row,
  and one lease;
- stale config/source/target evidence fails before irreversible mutation;
- listener, UI, sound, or particle failure cannot change the durable result.
