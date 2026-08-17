---
name: tamework-command-runtime
description: Use when changing Tamework command items, hotswap actions, radial controls, command target selection, linked or bonded companion panels, command HUD state, panel events, target authority, action routing, or command cleanup. Use when one action must work in both generic and bonded companion flows.
---

# Tamework Command Runtime

Trace a command as a lifecycle. Similar labels do not make generic and bonded
routes interchangeable.

## Map the Existing Action First

1. Read `references/command-lifecycle.md`.
2. Search current config assets, action enums, handlers, panel callbacks,
   localization, icons, tests, and docs for the requested action. Do not add a
   second action because one entry point was missed.
3. Read
   `wiki/Developer-Documentation/Runtime-Subsystems/Command-Runtime-and-Linked-Panel-Internals.md`.
4. Identify the source of target authority for every entry point:
   command item, radial menu, generic linked panel, and bonded panel.
5. Identify the presentation state that advertises the action and the cleanup
   state that removes it.

## Preserve Domain Boundaries

- Generic commands act on live linked targets through generic selection and
  operation authority.
- Bonded panels act through durable profile, roster, revision, lease, and
  capability gates. Never send a bonded event through a generic callback.
- Revalidate authority at execution time. A rendered button or cached selected
  target is not authority.
- Pass stable IDs across deferred or async boundaries, then resolve live state
  on the correct world thread.
- Keep the central feature handler as an orchestrator. Add behavior to a
  focused service when the handler would gain another responsibility.

## Keep Presentation and Execution Aligned

- Update assignment, prompt, icon, radial slice, HUD, panel action state,
  localization, and execution only where each surface uses the action.
- Hide or disable an action when its exact route cannot execute.
- Handle target loss, world change, stale panel revision, unload, cooldown,
  cancellation, and session closure.
- Emit refresh or invalidation through the current coordinator. Do not create a
  second UI truth source.

## Route Related Work

- Use `$tamework-interaction-configurator` for `TwInteractionConfig` prompt,
  sensor, action, state, and cooldown definitions.
- Use `$tamework-persistence` when command selection, assignments, bonded
  profiles, or recovery state crosses a save boundary.
- Use `$tamework-avatar-flight` when a command changes transformed-player or
  companion flight state.
- Use `$tamework-runtime-safety` for ECS, threading, cadence, or hot-path work.

## Verify

1. Test one observable generic route and one observable bonded route when both
   are affected. Include a forged or stale event and target-loss case when
   those are the regression risks.
2. Do not add tests for enum membership, registration calls, asset presence, or
   source structure.
3. Run `bash ../gradlew -p .. :alecstamework:test`.
4. Report the authority source, route, presentation updates, cleanup behavior,
   refresh signal, and unsupported evidence for each entry point.
