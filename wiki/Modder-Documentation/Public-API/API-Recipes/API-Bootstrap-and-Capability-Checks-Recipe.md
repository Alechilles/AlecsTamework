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

For durable integration state, resolve a canonical ID through `profiles()` and
store namespaced data through `profileData()`. Never write Tamework persistence
directly.

Replacement persistence features are advertised independently. Check the exact
set your action needs before taking an item, charging a cost, spawning an NPC,
or changing live state:

```java
EnumSet<TameworkApiCapability> required = EnumSet.of(
        TameworkApiCapability.POPULATION_GROUPS,
        TameworkApiCapability.COMMAND_FAMILY_ROSTERS,
        TameworkApiCapability.COMMAND_TIMED_SUMMONING
);
if (!capabilities.containsAll(required)) {
    return; // Fail closed before player cost or live mutation.
}

CommandFamilyRosterApi rosters = api.commandFamilyRosters();
CommandTimedSummoningApi timed = api.commandTimedSummoning();
PopulationGroupApi groups = api.populationGroups();
```

The readiness-gated persistence capabilities are:

| Capability | API or contract |
| --- | --- |
| `POPULATION_GROUPS` | `populationGroups()` |
| `DURABLE_POPULATION_GROUP_COUNTS` | durable owned counts from `populationGroups()` |
| `LOADED_POPULATION_GROUP_COUNTS` | process-local loaded counts from `populationGroups()` |
| `COMMAND_FAMILY_ROSTERS` | `commandFamilyRosters()` |
| `COMMAND_TIMED_SUMMONING` | `commandTimedSummoning()` |
| `COMPANION_PROVISIONING` | `companionProvisioning()` |
| `PAID_COMMAND_REVIVAL` | `paidCommandRevival()` |
| `CAPTURE_RESOLVED_ATTEMPT_CONSUMPTION` | resolved capture-attempt contract |
| `CAPTURE_TAME_AND_LINK` | successful capture tame/link contract |
| `PERSISTENCE_RESILIENCE` | replacement persistence health and resilience |

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

Capabilities can become available after startup readiness completes or become
unavailable when their own persistence scope is quarantined. Resolve the
capability and API for each player action; do not cache startup availability as
a permanent answer. The default API implementations remain fail-closed for
older Tamework versions.
