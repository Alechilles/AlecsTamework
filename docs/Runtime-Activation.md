# Runtime Activation

Tamework builds one immutable runtime plan after the initial asset load. The
plan starts only the modules required by effective content, early public
capability requests, durable state, and module dependencies.

Passive compatibility registrations stay available. These include codecs,
component types, asset stores, and NPC builders. A dormant module installs no
ECS or chunk system, feature listener, asset subscription, worker, scheduler,
or database runtime.

## Startup evidence

Tamework uses these evidence sources:

- Enabled Tamework configs that resolve to real NPC roles or items.
- Public capability requests made before the startup plan is published.
- Read-only evidence of generic or bonded durable work.
- Dependencies of a directly required module.

Disabled configs, empty target lists, missing target assets, debug defaults,
and an installed but unused optional integration do not activate a module.
Reusable library assets also stay passive until consumer content opts in. For
example, the bundled avatar-flight profile, Flightmaster's Talisman,
tranquilizer media, and attachment display labels do not install their runtime
systems by themselves. A downstream avatar-flight profile, enabled
tranquilizer asset set, or role-targeted runtime config supplies the required
intent. The optional example pack is absent from the main plugin jar, so its
teaching assets do not wake production modules.

Each module has one state:

- `ACTIVE`: Tamework installs its declared runtime participants.
- `DORMANT`: Tamework installs none of its runtime participants.
- `UNAVAILABLE`: Content or state requires the module, but a required
  capability or writable persistence authority is not ready.

## Persistence boundary

Generic and bonded persistence are separate authorities. Before either
authority opens a writer, Tamework uses a bounded read-only probe. A missing
store with no source data stays dormant and no database file is created.
Pending durable work starts its recovery authority. Corrupt, unreadable, or
uncertain evidence fails closed as read-only. A valid WAL sidecar activates
recovery; an orphan or non-regular sidecar remains read-only.

## Fixed topology and reloads

The startup topology does not change while the server process runs. A config
reload builds a candidate plan and compares its fingerprint with the startup
plan. If module states changed, `/tw reloadconfig` reports that a restart is
required. It does not add or remove live systems.

Use `/tw activation` to inspect the startup fingerprint, reload result,
module states, reasons, and passive counters. Dormant modules report zero
systems, callbacks, work cycles, workers, subscriptions, and database opens.
The diagnostics service has no monitor thread.

## Downstream capability requests

A Java integration can request a Tamework module before Tamework publishes
its startup plan:

```java
Tamework plugin = Tamework.getInstance();
plugin.requestRuntimeCapability(
        TameworkRuntimeModule.GENERIC_PERSISTENCE,
        "your-mod:husbandry-output"
);
```

Requests after startup are rejected because they would require a topology
change. After startup, read `getRuntimeActivationState()` and fail closed when
a required module is unavailable. Do not create a parallel worker or write
Tamework SQLite rows directly.

For Runeteria Husbandry, installed Runeteria assets provide normal content
evidence. RuneProfessions must request only the generic capability modules it
consumes, during setup. Installing RuneProfessions by itself does not activate
its bridge. Husbandry output and XP delivery remain event-driven; provider
reads use the immutable activation snapshot instead of polling.

The activation seam does not define profession rules. Tamework keeps no
RuneProfessions class names or profession logic. Admission rules, activity
events, and durable output APIs are separate public contracts and must use the
same module plan when they are added.

## Verification profiles

The focused runtime tests cover three observable states:

- Inactive: no participant factory or registration runs and all counters are
  zero.
- Active-idle: only active-module participants are installed; callback and
  work-cycle counters stay zero until work occurs.
- Loaded-animal: callback and work-cycle observations change only for the
  active module.

Live server validation must also cover a minimal production install, a
Runeteria Husbandry content profile, a missing provider, and retained durable
recovery state.
