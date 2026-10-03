# Progression Lifecycle

Use the applicable rows for every changed value.

| Stage | Evidence to find |
| --- | --- |
| Policy | Resolved `Tw*Config`, defaults, inheritance, and timer basis |
| Bootstrap | Initial component values and role-specific setup |
| Runtime | Mutation service, scheduler, cadence, and ECS write boundary |
| Coupling | Happiness, damage, stats, breeding, role, attachments, or talents |
| Save | Change detector tier (`CompanionFingerprints`) or `CompanionSaves.markChanged` on a loaded body |
| Capture | Full-entity snapshot (`CompanionSnapshots`), record `summary` (`CompanionSummaries`), or capture item keys |
| Persistence | Component codec, snapshot format, sentinel, and signed timestamps |
| Restore | `RestoreFlow`, `HytaleCompanionSpawner`, `SnapshotPatch` (alarm and breeding deadline re-basing, revive edits), and missing/old-field behavior |
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
  `CompanionStatModifierService`, and `CompanionTalentService`
- `CompanionSnapshots`, `CompanionSummaries`, `SnapshotPatch`, and
  `CompanionChangeDetectorSystem` for save, snapshot, and restore
- `CompanionNeedsSignedTimeTest` for the negative world-time invariant
