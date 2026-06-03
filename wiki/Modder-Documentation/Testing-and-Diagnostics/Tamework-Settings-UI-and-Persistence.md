---
title: "Tamework Settings UI and Persistence"
order: 14
published: true
draft: false
---
# Tamework Settings UI and Persistence

Parent: [Testing and Diagnostics](/mod/alecs-tamework/testing-and-diagnostics) | [Modder Documentation](/mod/alecs-tamework/modder-documentation)

This page explains what `/tw settings` changes, where those values are stored, and how they interact with config assets.

## Command
- `/tw settings` opens the curated in-game settings page.
- `/tw news` opens the current settings announcement popup on demand.

Use it for live world tuning of high-impact server settings without editing many JSON files manually.

## Presets
- `/tw settings` now includes experience presets that load recommended values into the form before apply.
- Presets do not save immediately; they update the current form and still require `Apply`.
- Presets only change the experience/system toggles below, so ownership, claims, telemetry, and other admin policies stay under manual control.

Current presets:
- `Simplified (Minecraft-like)`: disables needs, needs damage, happiness, passive breeding, breeding happiness requirements, traits, leveling, and talents.
- `Easier`: enables most systems but keeps needs damage off.
- `Full Experience`: enables the full needs, happiness, passive breeding, breeding happiness, traits, leveling, and talents stack.
- Presets do not change the breeding-gender toggle; that setting remains a manual server policy.

## Automatic Review Announcement
- Eligible players can also see a login-time settings review popup before opening `/tw settings` manually.
- The popup uses the same access rule as `/tw settings`: `tamework.config` with the usual OP/Admin/Operator fallback groups.
- `/tw news` uses the same access rule and ignores prior opt-out state so eligible players can reopen the current announcement whenever they want.
- Players only stop seeing the popup for the current announcement after checking the opt-out box.
- Changing the active announcement id re-arms the popup for eligible players on later logins.
- If `useBuiltInAnnouncementId` stays `true`, future Tamework releases can re-arm the popup automatically when the built-in announcement id changes.

## Where Data Is Stored
- Universe settings file: `universe/Tamework/Settings/tamework-settings.json`
- Settings announcement file: `universe/Tamework/Settings/tamework-settings-announcement.json`
- Settings announcement opt-out state: `universe/Tamework/Settings/tamework-settings-announcement-state.json`
- Telemetry settings are stored in the universe settings file under `telemetry`

These files are universe-local runtime settings, not shipped mod assets.

## Settings Announcement File
- `enabled`: turns the login popup on or off without disabling `/tw settings` itself.
- `useBuiltInAnnouncementId`: when `true`, Tamework's built-in announcement id controls re-show behavior across releases.
- `useBuiltInText`: when `true`, the built-in announcement copy is resolved from `Server/Languages/en-US/server.lang` for the player's language.
- `announcementId`: used only when `useBuiltInAnnouncementId` is `false`; bump it to force the popup to appear again.
- `title`, `bodyLines`, and `optOutLabel`: let the server owner customize the popup copy.

When `useBuiltInText` is `false`, you can still provide raw `title`, `subtitle`, `bodyLines`, and `optOutLabel` values directly in the universe file for one-off server messaging.

## Built-In Localization Keys
- `tamework.ui.settingsAnnouncement.title`
- `tamework.ui.settingsAnnouncement.subtitle`
- `tamework.ui.settingsAnnouncement.body.intro`
- `tamework.ui.settingsAnnouncement.body.defaults`
- `tamework.ui.settingsAnnouncement.body.scope`
- `tamework.ui.settingsAnnouncement.optOut`

This lets you keep Alec's built-in re-arm behavior while still replacing the visible message with server-specific instructions.

## Settings Covered by the UI
These values are owned by `/tw settings` at runtime. Legacy config keys may still be accepted for older packs, but new shipped examples and `/tw config` no longer advertise these duplicated fields.

| Area | Settings-owned values |
| --- | --- |
| Population | Owned NPC limits and per-player counting scope |
| SimpleClaims | Enablement, claim limits, breeding-claim requirement, and tamed damage protection |
| Ownership | Owner damage protection, capture/spawn/interaction/linking owner requirements, capture owner clearing, and spawn owner assignment |
| Needs | Master enable, owner-offline tick policy, damage model/rates/lethal |
| Progression | Happiness master enable, passive breeding master enable, breeding happiness requirement, breeding gender master toggle, traits master enable, leveling master enable, and talent tree master enable |
| Commands | Revive system master enable and recall/return-home teleporting master enable |
| Telemetry | Crash telemetry and breadcrumb enablement |

### Population
- `LimitPerPlayerOwnedTotal`
- `PerPlayerLimitScope`

### SimpleClaims
- `SimpleClaimsEnabled`
- `LimitPerClaimChunk`
- `LimitPerClaimTotal`
- `BreedingRequiresClaim`
- `ProtectTamedFromNonMembers`

### Ownership and Interaction Defaults
- `BlockOwnerDamage`
- `BlockAllPlayerDamageIfOwned`
- `InvulnerableIfOwned`
- `CaptureRequiresOwner`
- `SpawnRequiresOwner`
- `InteractionRequiresOwner`
- `LinkingRequiresOwner`
- `CaptureClearsOwner`
- `SpawnSetsOwner`

### Needs and Revive
- Needs system enabled toggle
- Needs tick policy and needs-damage values (including model/rates/lethal)
- Happiness system enabled toggle
- Passive breeding enabled toggle
- Breeding requires happiness toggle
- Breeding genders enabled toggle
- Traits system enabled toggle
- Leveling system enabled toggle
- Talents system enabled toggle
- Revive system enabled toggle

### Crash Telemetry
- `telemetry.enabled`
- `telemetry.breadcrumbsEnabled`

## Runtime Behavior
- Applying settings writes updated files and refreshes runtime state.
- Loading a preset only changes the current UI form; `Apply` persists it.
- Disabling leveling stops companion XP awards, level snapshots, level-based growth, and new leveling component bootstrapping. Applying the setting also refreshes loaded NPC stat modifiers so stale level-based bonuses are removed immediately.
- Disabling talents hides talent availability, blocks talent purchases, and suppresses purchased talent passive effects. Applying the setting also refreshes loaded NPC stat modifiers so stale talent bonuses are removed immediately.
- Crash telemetry enablement and breadcrumbs are applied immediately when possible and mirrored into the embedded Alec's Telemetry project override.
- Legacy `crash-telemetry.json` and `tamework-crash-telemetry.txt` values are imported only when the universe settings file does not already contain telemetry values.
- `/tw settings` is intended for world-level operations and diagnostics, not per-mod content packs.
- The login popup is shown at most once per player login session, so world changes do not reopen it repeatedly.

## Relationship to `Tw*Config` Assets
- `TwGlobalConfig`, `TwNeedsConfig`, and related assets remain the content-authoring path for role, item, timing, refill, command-distance, and integration details.
- `/tw settings` is the runtime source of truth for the common server-owner controls listed above.
- Settings-owned config fields are legacy compatibility fields. They remain readable so older packs do not break, but the config editor hides them and new examples omit them.
- `/tw reloadconfig` does not replace or clear settings stored by `/tw settings`.

## Best Practices
- Use config assets for shipped defaults and mod distribution.
- Use `/tw settings` for server-specific tuning after deployment.
- Keep the announcement copy focused on major setting changes and use `announcementId` changes sparingly so the popup stays meaningful.
- Keep a backup of `universe/Tamework/Settings/` before major balancing experiments.

## Related Pages
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [Integrations, Telemetry, and Build Workflow](/mod/alecs-tamework/integrations-telemetry-and-build-workflow)
