---
title: "Public API Overview"
order: 2
published: true
draft: false
---
# Public API Overview

Parent: [API Reference](/mod/alecs-tamework/api-reference) | [Public API](/mod/alecs-tamework/public-api)

Use this page to bootstrap against `TameworkApi`, verify capabilities, and choose the correct family reference page.

> **Experimental API Contract (`0.6.0`)**
> The API is named **Public API** in docs and packages, but the contract is still experimental. Keep capability checks and plan for additive changes.

## Dependency and access pattern
Add Tamework as a dependency in your `manifest.json`:

```json
"Dependencies": {
  "Alechilles:Alec's Tamework!": "2.6.0"
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
Root API entrypoints:
- `getApiVersion()`
- `getCapabilities()`
- `profiles()`
- `commandLinks()`
- `progression()`
- `policies()`
- `interactionExtensions()`
- `traitEffects()`
- `profileData()`
- `events()`
- `configs()`
- `diagnostics()`

Current capability set:
- `PROFILES`
- `COMMAND_LINKS`
- `PROGRESSION`
- `PROGRESSION_MUTATIONS`
- `POLICY`
- `INTERACTION_EXTENSIONS`
- `TRAIT_EFFECTS`
- `PROFILE_DATA`
- `EVENTS`
- `COMPANION_XP_EVENTS`
- `CONFIG_READ`
- `DIAGNOSTICS`

## Family references
- [Profiles API Reference](/mod/alecs-tamework/profiles-api-reference)
- [Profile Data API Reference](/mod/alecs-tamework/profile-data-api-reference)
- [Command Links API Reference](/mod/alecs-tamework/command-links-api-reference)
- [Progression API Reference](/mod/alecs-tamework/progression-api-reference)
- [Policies API Reference](/mod/alecs-tamework/policies-api-reference)
- [Config Reads API Reference](/mod/alecs-tamework/config-reads-api-reference)
- [Events API Reference](/mod/alecs-tamework/events-api-reference)
- [Interaction Extensions API Reference](/mod/alecs-tamework/interaction-extensions-api-reference)
- [Trait Effects API Reference](/mod/alecs-tamework/trait-effects-api-reference)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)

## Recipes
- [Increase Mob Happiness from Custom Interaction Recipe](/mod/alecs-tamework/increase-mob-happiness-from-custom-interaction-recipe)
- [Decrease Mob Happiness from Negative Event Recipe](/mod/alecs-tamework/decrease-mob-happiness-from-negative-event-recipe)
- [Set Hunger and Thirst from Custom Feeding Recipe](/mod/alecs-tamework/set-hunger-and-thirst-from-custom-feeding-recipe)
- [Force Breeding Ready from Custom Ritual Recipe](/mod/alecs-tamework/force-breeding-ready-from-custom-ritual-recipe)
- [Reroll Traits and Show Values Recipe](/mod/alecs-tamework/reroll-traits-and-show-values-recipe)
- [Apply Attachment Preset from Custom UI Recipe](/mod/alecs-tamework/apply-attachment-preset-from-custom-ui-recipe)
- [Read Saved Home Position and Show a Waypoint Recipe](/mod/alecs-tamework/read-saved-home-position-and-show-a-waypoint-recipe)
- [Check Command Link State before Running Feature Recipe](/mod/alecs-tamework/check-command-link-state-before-running-feature-recipe)
- [Build Companion Inspector UI Card Recipe](/mod/alecs-tamework/build-companion-inspector-ui-card-recipe)
- [Store Per-Mob Plugin State JSON Recipe](/mod/alecs-tamework/store-per-mob-plugin-state-json-recipe)
- [Auto-Register Companion on Capture Event Recipe](/mod/alecs-tamework/auto-register-companion-on-capture-event-recipe)
- [Pause Companion Jobs on Death or Lost Event Recipe](/mod/alecs-tamework/pause-companion-jobs-on-death-or-lost-event-recipe)
- [Keep Companion Cache in Sync with Profile Changed Events Recipe](/mod/alecs-tamework/keep-companion-cache-in-sync-with-profile-changed-events-recipe)
- [Credit External Skill XP from Companion XP Recipe](/mod/alecs-tamework/credit-external-skill-xp-from-companion-xp-recipe)
- [Enforce Ownership before Custom Command or Effect Recipe](/mod/alecs-tamework/enforce-ownership-before-custom-command-or-effect-recipe)
- [Check Population Cap before Spawning or Taming Recipe](/mod/alecs-tamework/check-population-cap-before-spawning-or-taming-recipe)
- [Register Interaction Extensions in Plugin Lifecycle Recipe](/mod/alecs-tamework/register-interaction-extensions-in-plugin-lifecycle-recipe)
- [Register Custom Trait Effect Key Recipe](/mod/alecs-tamework/register-custom-trait-effect-key-recipe)
- [API Bootstrap and Capability Checks Recipe](/mod/alecs-tamework/api-bootstrap-and-capability-checks-recipe)
- [Event Subscription Lifecycle Recipe](/mod/alecs-tamework/event-subscription-lifecycle-recipe)
- [Progression Mutation Status Handling Recipe](/mod/alecs-tamework/progression-mutation-status-handling-recipe)
- [Interaction Extension Registration Recipe](/mod/alecs-tamework/interaction-extension-registration-recipe)
- [In-Game API Self-Test Smoke Recipe](/mod/alecs-tamework/in-game-api-self-test-smoke-recipe)

## In-game self-tests
Tamework also ships an in-game self-test harness for this API under `/tw api test ...`.

Use it when you want to validate that the live server runtime, persistence layer, bundled example assets, and public API surface all still agree without writing a separate integration mod.

See:
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)

## What not to use
- Do not write directly to `tamework.sqlite`.
- Do not depend on repository classes like `NpcProfileRepository` or `CaptureRepository`.
- Do not mutate or cache internal `Tw*Config` instances.
- Do not assume API version `0.6.0` will match the mod version.

## Related Pages
- [Setup and Quick Start](/mod/alecs-tamework/setup-and-quick-start)
- [In-Game API Self-Tests](/mod/alecs-tamework/in-game-api-self-tests)
- [Config Discovery, Resolution, and Inheritance](/mod/alecs-tamework/config-discovery-resolution-and-inheritance)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)



