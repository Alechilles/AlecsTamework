---
title: "Tamework API (Experimental)"
order: 3
published: true
draft: false
---
# Tamework API (Experimental)

Parent: [Modder Documentation Index](/mod/alecs-tamework/modder-documentation-index) | [Home](/mod/alecs-tamework/alecs-tamework-wiki)

Use this page when another mod wants to read Tamework profile data, store profile-scoped extension JSON, or subscribe to Tamework lifecycle events without reaching into internal classes.

## Experimental contract
- The API contract is currently experimental and versioned separately from the mod version.
- The current experimental API version is `0.4.0`.
- Expect additive changes and occasional cleanup while downstream integrations prove out the surface.
- Do not depend on internal repositories, SQLite tables, or `Tw*Config` implementation classes even if they are visible in the jar.

## Dependency and access pattern
Add Tamework as a dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "2.5.3"
}
```

Access the API from Java through `Tamework.getInstance()` and always null-check both the plugin instance and the API accessor:

```java
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.TameworkApi;

Tamework tamework = Tamework.getInstance();
TameworkApi api = tamework != null ? tamework.getApi() : null;
if (api == null) {
    return;
}
```

## Available capabilities
The root API exposes:
- `getApiVersion()`
- `getCapabilities()`
- `profiles()`
- `commandLinks()`
- `progression()`
- `policies()`
- `interactionExtensions()`
- `profileData()`
- `events()`
- `configs()`
- `diagnostics()`

Check `getCapabilities()` before assuming a feature exists. The current experimental build advertises:
- `PROFILES`
- `COMMAND_LINKS`
- `PROGRESSION`
- `PROGRESSION_MUTATIONS`
- `POLICY`
- `INTERACTION_EXTENSIONS`
- `PROFILE_DATA`
- `EVENTS`
- `CONFIG_READ`
- `DIAGNOSTICS`

## Profile reads
`profiles()` gives read access to Tamework’s stable NPC profile model instead of raw tables:
- Resolve a profile id from an NPC UUID
- Read a profile by profile id or current UUID
- Read the active snapshot payload for a snapshot type
- List the active snapshot types currently attached to the profile

`NpcProfileView` is detached and immutable. It includes the stable profile id, current UUID, owner info, role, names, tame flag, coop assignment, linked tool ids, active snapshot types, and the last update timestamp.

## Command link reads
`commandLinks()` gives read access to the command-link state that powers follow/stay/home-style companion behavior:
- Read command-link state by profile id or current UUID
- Read the currently linked tool ids
- Read whether the mob has a saved home position
- Read the saved home position for a linked mob

`CommandLinkView` is detached and immutable. It includes the stable profile id, current UUID, owner UUID, linked tool ids, current home position if present, last known position if available, active snapshot types, and the last profile update timestamp.

Home-position resolution order is:
- The live `TameworkCommandLinksComponent` on the NPC if the mob is currently loaded
- Tamework’s in-memory linked-state snapshot cache if the mob is temporarily unloaded
- The active persisted `capture`, `death`, or `lost` snapshot payload if one exists

Example:

```java
api.commandLinks().getHomePosition(profileId).ifPresent(home -> {
    double x = home.x();
    double y = home.y();
    double z = home.z();
});
```

## Progression reads
`progression()` exposes live companion progression state for loaded NPCs:
- Read progression by profile id
- Read progression by current NPC UUID
- Read detached snapshots for happiness, needs, breeding, life stage, traits, and attachments

`ProgressionView` is live-state only. If the NPC is not currently loaded, `progression().getBy...(...)` returns empty instead of guessing from persistence snapshots.

The progression subviews currently expose:
- `HappinessView`: current value, min/max band, last update timestamp, source, equilibrium base/target, and active modifiers
- `NeedsView`: hunger, thirst, percent values when config is available, applied happiness penalty, and timestamps
- `BreedingView`: enabled/ready flags, cooldown state, tracked happiness, effective happiness, threshold, eligibility, and fertility multiplier
- `LifeStageView`: resolved stage, growth timeline, current scale, and configured role/scaling breakpoints
- `TraitsView`: roll seed and rolled trait values
- `AttachmentsView`: stored attachment ids and the currently applied model attachments

Example:

```java
api.progression().getByNpcUuid(npcUuid).ifPresent(progression -> {
    ProgressionView.BreedingView breeding = progression.breeding();
    if (breeding != null && Boolean.TRUE.equals(breeding.eligible())) {
        double effective = breeding.effectiveHappiness();
    }
});
```

## Progression mutations
`progression()` also exposes controlled live-state writes for loaded NPCs. These methods mutate Tamework’s progression components through the same rules-aware services the mod uses internally rather than asking integrations to edit ECS components directly.

Current mutation methods:
- `setHappiness(...)`
- `applyHappinessDelta(...)`
- `setNeeds(...)`
- `setBreedingReady(...)`
- `rerollTraits(...)`
- `setTraits(...)`
- `refreshLifeStage(...)`
- `setStoredAttachments(...)`
- `syncStoredAttachments(...)`

All progression mutations return a `ProgressionMutationResult` with:
- `status()`: `APPLIED`, `NOT_FOUND`, `NOT_LOADED`, `INVALID_ARGUMENT`, `UNSUPPORTED`, or `ERROR`
- `message()`: a compact explanation of what happened
- `progression()`: the detached post-mutation `ProgressionView` when Tamework can snapshot the live NPC state

Guidelines:
- Use profile ids when you want a stable target across UUID remaps.
- Expect `NOT_LOADED` when the profile exists but the live NPC is not currently loaded.
- Expect `UNSUPPORTED` when the target NPC does not currently have the relevant progression system enabled.
- Treat `ERROR` as an unexpected runtime failure rather than a validation result.

Example:

```java
ProgressionMutationResult result = api.progression().setHappiness(profileId, 82.5);
if (result.applied() && result.progression() != null && result.progression().happiness() != null) {
    double appliedValue = result.progression().happiness().value();
}
```

## Interaction extensions
`interactionExtensions()` lets downstream mods register custom interaction requirements/effects and optional reusable presets without patching Tamework internals.

Phase 3 extension hooks:
- register requirement handlers by id
- register effect handlers by id
- register preset definitions that bundle requirement/effect specs
- unregister by closing the returned `AutoCloseable`

At runtime, Tamework evaluates custom requirement specs from `Requires.All.Custom` / `Requires.Any.Custom`, and executes custom effect specs from `Effects.Custom`. `Custom` interaction entries can also reference a registered preset via `PresetId`.

## Profile-scoped extension data
`profileData()` is the persistence-backed extension write surface. It stores UTF-8 JSON text keyed by:
- `profileId`
- `namespace`
- `key`

Rules:
- Use your plugin id as the namespace.
- `Alechilles:Tamework` is reserved for internal use.
- Namespace and key must be nonblank.
- Payloads must be valid JSON text.
- Writes go through Tamework’s queued persistence path, not direct SQL.

Example:

```java
boolean accepted = api.profileData().put(
        profileId,
        "example.plugin",
        "behavior_state",
        "{\"mode\":\"follow\",\"level\":2}"
);
```

## Events
`events()` exposes a Tamework-owned listener bus:

```java
AutoCloseable subscription = api.events().subscribe(NpcProfileChangedEvent.class, event -> {
    // handle the immutable event snapshot
});
```

Event semantics:
- Callbacks run synchronously on the thread where Tamework emits the event.
- Listener exceptions are caught so one integration cannot break the rest.
- Close the returned subscription when your mod unloads or no longer needs the listener.
- Persistence-backed events are emitted after Tamework accepts and commits the related queued write. Treat that as “write committed in Tamework,” not as a cross-process durability guarantee.

Phase 1 event types:
- `NpcProfileChangedEvent`
- `NpcCapturedEvent`
- `NpcDeathRecordedEvent`
- `NpcLostRecordedEvent`
- `ConfigReloadedEvent`

## Config reads
`configs()` currently exposes immutable read views for:
- `GlobalConfigView`
- `InteractionConfigView`
- `RoleScopedConfigView` for companion, happiness, needs, breeding, and trait families
- `SpawnerConfigView`
- `NameItemConfigView`
- `CommandItemConfigView`

These views are detached from the live asset classes and represent the effective resolved config state that Tamework uses at runtime.

Role-scoped families expose:
- lookup by config id
- resolution by NPC role id

Item-facing families expose:
- lookup by config id
- resolution by item id

The compact `detailsJson` field is the detached JSON payload for the resolved config object, while the top-level DTO fields expose the most common selectors such as role ids and item ids.

## Policy reads
`policies()` exposes stable ownership, claim, and damage-policy reads without requiring mods to reimplement Tamework’s internal checks:
- `getOwnershipByProfileId(...)`
- `getOwnershipByNpcUuid(...)`
- `isOwner(...)`
- `evaluateClaimAccess(...)`
- `evaluateDamage(...)`
- `evaluatePopulationCap(...)`

`OwnershipPolicyView` includes the profile id, owner info, tame/co-op state, and the effective companion ownership-protection flags for that mob’s role.

Claim and damage checks are best-effort and use the mob’s live world context when it is currently loaded. If live claim context is unavailable, the API reports that through the returned decision status instead of pretending the lookup was authoritative.

## Diagnostics
`diagnostics()` currently exposes:
- `getPersistenceDiagnostics()`

`PersistenceDiagnosticsView` includes the SQLite path, file sizes, write-queue metrics, and current persistence health state. This is intended for tooling, admin UIs, and integration debugging rather than gameplay logic.

## In-game self-tests
Tamework also ships an in-game self-test harness for this API under `/tw api test ...`.

Use it when you want to validate that the live server runtime, persistence layer, bundled example assets, and public API surface all still agree without writing a separate integration mod.

See:
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)

## What not to use
- Do not write directly to `tamework.sqlite`.
- Do not depend on repository classes like `NpcProfileRepository` or `CaptureRepository`.
- Do not mutate or cache internal `Tw*Config` instances.
- Do not assume the experimental API version matches the mod version.

## Related Pages
- [Setup and Quick Start](/mod/alecs-tamework/setup-and-quick-start)
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
