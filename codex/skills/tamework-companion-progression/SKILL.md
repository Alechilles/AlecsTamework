---
name: tamework-companion-progression
description: Use when changing Tamework companion needs, hunger, thirst, happiness, breeding, traits, talents, levels, life stages, progression modifiers, offline progression, progression timestamps, dynamic attachments, snapshot restore, or progression presentation. Also use for restart-only progression bugs.
---

# Tamework Companion Progression

Treat progression as one state lifecycle across live entities, saved snapshots,
offline time, restoration, and presentation.

## Trace the State Lifecycle

1. Read `references/progression-lifecycle.md`.
2. Identify the component that owns the value and the `Tw*Config` family that
   defines its policy.
3. Trace initialization, runtime mutation, checkpoint or snapshot capture,
   restore, load bootstrap, and UI/API presentation.
4. Find coupled systems. Needs can affect happiness and damage; life stage can
   affect roles and attachments; traits and talents can affect stats and time
   scales.
5. For restart-only reports, compare source, packaged runtime, persisted value,
   restored value, resolved config, and the first post-load tick.

## Preserve Time Semantics

- World-time epoch milliseconds can be negative. Zero is the explicit unset
  sentinel; sign is not validity.
- Preserve signed timestamps through codecs, snapshots, databases, arithmetic,
  logs, and UI.
- Compare by ordering. Do not gate valid timers with `> 0` or clamp them to
  positive values.
- Distinguish real time from scaled world time before computing offline elapsed
  time, catch-up limits, cooldowns, or owner absence.
- Change sweep cadence only for cadence behavior. Do not use it to mask a
  restore or timestamp defect.

## Keep Responsibilities Focused

- Keep system classes as scheduling and ECS orchestration.
- Put needs, happiness, breeding, traits, talents, level, and life-stage rules
  in their focused services.
- Do not add another responsibility to a progression service that is already
  over the repository size limit. Extract the new policy or calculation.
- Avoid per-tick scans and allocations. Reuse the current indexes, cadence
  policies, and dirty-state boundaries.

## Route Related Work

- Use `$tamework-config-authoring` for `Tw*Config` schema, inheritance,
  override, editor, or reload changes.
- Use `$tamework-persistence` for durable state, snapshots, checkpoints,
  database rows, recovery, or migration.
- Use `$tamework-runtime-safety` for ECS writes, world-thread access, async
  work, and sweep performance.
- Use `$tamework-api-evolution` when public progression views or mutations
  change.

## Verify Observable Behavior

1. Test the reported transition, such as save -> restart -> restore -> first
   tick, not source structure or asset presence.
2. Keep or extend signed-time coverage when a timer changes.
3. Verify affected coupled outputs separately: state value, happiness, damage,
   role/life stage, attachment, HUD/panel, or API view.
4. Run `bash ../gradlew -p .. :alecstamework:test`.
5. Report timer basis, persisted fields, restore path, catch-up policy, coupled
   effects, and any runtime evidence gap.
