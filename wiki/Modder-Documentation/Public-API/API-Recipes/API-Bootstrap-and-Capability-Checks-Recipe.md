---
title: "API Bootstrap and Capability Checks"
order: 1
published: true
draft: false
---
# API Bootstrap and Capability Checks

Resolve `TameworkApi`, then test every capability needed by your feature.

```java
Tamework plugin = Tamework.getInstance();
TameworkApi api = plugin == null ? null : plugin.getApi();
if (api == null) {
    return;
}

EnumSet<TameworkApiCapability> capabilities = api.getCapabilities();
if (!capabilities.contains(TameworkApiCapability.PROFILE_DATA_TRANSACTIONS)) {
    return;
}
```

`getApiVersion()` is useful for logs and compatibility diagnostics, but it is
not a capability check. Do not infer an optional feature from the Tamework
version or from DTO classes being present.

For durable integration state, resolve a profile ID through `profiles()` and
store namespaced data through `profileData()`. Never write Tamework's
companion files directly.

Check the exact set your action needs before taking an item, charging a cost,
spawning an NPC, or changing live state:

```java
EnumSet<TameworkApiCapability> required = EnumSet.of(
        TameworkApiCapability.POPULATION_GROUPS,
        TameworkApiCapability.DURABLE_POPULATION_GROUP_COUNTS,
        TameworkApiCapability.BONDED_COMPANIONS
);
if (!capabilities.containsAll(required)) {
    return; // Fail closed before player cost or live mutation.
}

PopulationGroupApi groups = api.populationGroups();
BondedCompanionApi bonded = api.bondedCompanions();
```

Companion store capabilities in API 3.0.0:

| Capability | API or contract |
| --- | --- |
| `POPULATION_GROUPS` | `populationGroups()` |
| `DURABLE_POPULATION_GROUP_COUNTS` | owned counts from `populationGroups()` |
| `DURABLE_DEPLOYABLE_POPULATION_COUNTS` | `LIVE` counts from `populationGroups()` |
| `EXTERNAL_ADMISSION_PROVIDERS` | `policies().admissionProviders()` |
| `REQUIRED_CONTENT_PROFILES` | `requiredContentProfiles()` |
| `BONDED_COMPANIONS` | `bondedCompanions()` |
| `PROFILE_DATA_TRANSACTIONS` | `profileData().compareAndSet(...)` |
| `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION` | resolved capture-attempt contract |
| `CAPTURE_TAME_AND_LINK` | successful capture tame/link contract |

API 3.0.0 removed `COMMAND_FAMILY_ROSTERS`, `COMMAND_TIMED_SUMMONING`,
`COMPANION_PROVISIONING`, `PAID_COMMAND_REVIVAL`,
`NAMED_CAPACITY_RESERVATIONS`, and `PERSISTENCE_RESILIENCE`. A mod that looks
capabilities up by name must drop these names. `Enum.valueOf` throws for them,
and a required-capability list that still holds one never becomes ready.

Command UI features use four separate capabilities:

```java
EnumSet<TameworkApiCapability> requiredUi = EnumSet.of(
        TameworkApiCapability.COMMAND_UI_RENDERERS,
        TameworkApiCapability.COMMAND_UI_CONTRIBUTORS,
        TameworkApiCapability.COMMAND_UI_CUSTOM_ACTIONS,
        TameworkApiCapability.COMMAND_UI_CUSTOM_FLOWS
);
if (!capabilities.containsAll(requiredUi)) {
    return; // Keep the integration inactive and use Tamework's standard UI.
}
```

`COMMAND_UI_RENDERERS` supplies custom page registration, snapshots, opaque
built-in actions, and partial updates. `COMMAND_UI_CONTRIBUTORS` supplies
namespaced page and row presentation. `COMMAND_UI_CUSTOM_ACTIONS` permits
contributor-owned server actions. `COMMAND_UI_CUSTOM_FLOWS` permits
contributor-owned multi-step flows. Check only the exact set that your plugin
uses, and also require `api.commandUi().available()` before registration.

`Tamework.getApi()` returns null until the companion store is ready, and
capabilities can change while the server runs (for example when Tamework shuts
a runtime down). Resolve the API and the capability for each player action; do
not cache startup availability as a permanent answer.

Command HUD features use two capabilities:

```java
EnumSet<TameworkApiCapability> requiredHud = EnumSet.of(
        TameworkApiCapability.COMMAND_HUD_RENDERERS,
        TameworkApiCapability.COMMAND_HUD_CONTRIBUTORS
);
if (!capabilities.containsAll(requiredHud)
        || !api.commandHud().available()) {
    return; // Keep the standard target and equipped-tool HUDs active.
}
```

`COMMAND_HUD_RENDERERS` permits custom target-HUD and equipped-tool hotswap-HUD
renderer registration. `COMMAND_HUD_CONTRIBUTORS` permits namespaced detached
presentation contributors for those surfaces. The two capabilities are
advertised together by the current implementation. Older or degraded adapters
return an unavailable `CommandHudApi` and must be treated as unsupported.
