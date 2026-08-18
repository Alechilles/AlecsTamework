---
title: "Runtime Activation"
order: 1
published: true
draft: false
---
# Runtime Activation

Parent: [Runtime Subsystems](/mod/alecs-tamework/runtime-subsystems) | [Developer Documentation](/mod/alecs-tamework/developer-documentation)

Tamework builds one immutable runtime plan after the initial asset load. It
starts only modules required by effective content, early public capability
requests, durable state, and dependencies.

Passive codecs, components, asset stores, and NPC builders remain available.
A dormant module installs no ECS or chunk system, feature listener, asset
subscription, worker, scheduler, or database runtime.

## Evidence and states

Enabled configs count only when they resolve to real roles or items. Disabled
configs, empty targets, missing assets, debug defaults, and installed but
unused integrations do not count. Reusable library content is passive too.
The bundled avatar-flight profile, Flightmaster's Talisman, tranquilizer
media, and attachment display labels do not install gameplay systems unless a
downstream profile or feature gate declares that it uses them. Pending durable
state can keep its recovery authority active after content is removed.

Each module is `ACTIVE`, `DORMANT`, or `UNAVAILABLE`. An unavailable module is
required but lacks a required capability or writable persistence authority.
Generic and bonded persistence use separate bounded read-only probes. Missing
stores stay absent; valid WAL state wakes recovery; uncertain stores fail
closed as read-only.

## Operations

Runtime topology is fixed until restart. `/tw reloadconfig` compares a new
candidate with the startup plan and reports `restart required` when module
states differ. It does not change live systems.

Use `/tw activation` to view the topology fingerprint, reload result, states,
reasons, system registrations, callbacks, work cycles, workers,
subscriptions, and database opens. Dormant modules report exact zeros. No
background monitor produces these values.

## Runeteria and RuneProfessions

Runeteria Husbandry assets activate their normal Tamework modules. An
installed RuneProfessions plugin does not activate a bridge by itself. A Java
provider requests only the generic module capabilities that it consumes,
before Tamework publishes its startup plan:

```java
Tamework.getInstance().requestRuntimeCapability(
        TameworkRuntimeModule.GENERIC_PERSISTENCE,
        "runeprofessions:husbandry-output"
);
```

After startup, the provider reads the immutable activation state and fails
closed when a required module is unavailable. Output and XP paths remain
event-driven. Tamework does not contain RuneProfessions-specific profession
logic, and integrations must not write Tamework SQLite rows directly.

The activation seam does not yet define Husbandry admission, activity, or
durable output APIs. Those public contracts must join this module plan when
they are implemented.
