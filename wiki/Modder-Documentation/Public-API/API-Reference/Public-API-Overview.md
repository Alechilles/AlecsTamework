---
title: "Public API Overview"
order: 1
published: true
draft: false
---
# Public API Overview

Obtain the API from the loaded Tamework plugin:

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) {
    return;
}
```

Read `getApiVersion()` for compatibility diagnostics and check
`getCapabilities()` before using a surface. Capabilities, not version strings,
authorize optional behavior.

## Entry points

- `profiles()` for canonical companion profile reads;
- `commandLinks()` for command-tool links;
- `progression()` for progression reads and supported mutations;
- `policies()` for ownership, damage, claim access, and the live owner-cap
  preflight;
- `interactionExtensions()` and `traitEffects()` for registered extensions;
- `profileData()` for namespaced canonical-profile data;
- `events()` for immutable notifications;
- `configs()` for detached config views; and
- `diagnostics()` for the retained read-only diagnostic snapshot.

The current capability enum contains `PROFILES`, `COMMAND_LINKS`,
`PROGRESSION`, `PROGRESSION_MUTATIONS`, `POLICY`,
`INTERACTION_EXTENSIONS`, `TRAIT_EFFECTS`, `PROFILE_DATA`, `EVENTS`,
`COMPANION_XP_EVENTS`, `CONFIG_READ`, `DIAGNOSTICS`, `CAPTURE_POLICY`, and
`PROFILE_DATA_TRANSACTIONS`.

## Canonical profile extension data

Require `PROFILE_DATA_TRANSACTIONS` before using versioned profile data.
`compareAndSet` uses the expected revision and a stable idempotency key.
`findOperation` lets a caller recover the durable result after restart.

This is the supported place for integration-owned companion state. Do not use a
live NPC UUID, command-item row, or direct SQLite write as a substitute for the
canonical profile ID.

An unadvertised capability is unavailable. Integrations must not infer private
operations from the API version.

There is no public population-reservation, population-group, companion-
provisioning, timed-roster, or paid-restoration authority. Use the supported
gameplay paths and API families above instead of internal replacement-
persistence classes.
