# Progression Lifecycle

Use the applicable rows for every changed value.

| Stage | Evidence to find |
| --- | --- |
| Policy | Resolved `Tw*Config`, defaults, inheritance, and timer basis |
| Bootstrap | Initial component values and role-specific setup |
| Runtime | Mutation service, scheduler, cadence, and ECS write boundary |
| Coupling | Happiness, damage, stats, breeding, role, attachments, or talents |
| Capture | Entity checkpoint, coop state, bonded snapshot, or item metadata |
| Persistence | Stored representation, version, sentinel, and signed timestamps |
| Restore | Decoder, restorer, reconciliation, and missing/old-field behavior |
| Resume | Load system, offline elapsed policy, cap, and first tick |
| Presentation | Command, panel, HUD, diagnostics, and public API mapping |

## Timer Review

For each timer, record:

- clock source: real time or scaled world time;
- unset sentinel;
- whether negative values are valid;
- saved field and codec;
- elapsed-time and ordering calculation;
- offline owner policy and catch-up cap;
- behavior after world change or restart.

## Useful Starting Points

Verify all names in current source:

- `CompanionProgressionBootstrapOnLoadSystem`
- `CompanionProgressionBootstrapService`
- `CompanionNeedsSystem`, `CompanionNeedsService`, and
  `CompanionNeedsRuntimePolicy`
- `CompanionHappinessService`, `CompanionLifeStageService`,
  `CompanionTraitEffectService`, and `CompanionTalentService`
- companion checkpoint, coop snapshot, bonded snapshot, and progression
  metadata services
- `CompanionNeedsSignedTimeTest` for the negative world-time invariant
