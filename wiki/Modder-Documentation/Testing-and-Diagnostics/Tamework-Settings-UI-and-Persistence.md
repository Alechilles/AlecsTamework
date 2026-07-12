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
| Population | Canonical owned-companion limits and per-player counting scope |
| Claim integration | Master enablement, provider, claim admission limits, breeding-claim requirement, and SimpleClaims tamed-target damage policy |
| Ownership | Owner damage protection, capture/spawn/interaction/linking owner requirements, capture owner clearing, and spawn owner assignment |
| Needs | Master enable, resource seeking mode, owner-offline tick policy, damage model/rates/lethal |
| Progression | Happiness master enable, passive breeding master enable, breeding happiness requirement, breeding gender master toggle, traits master enable, leveling master enable, and talent tree master enable |
| Commands | Revive system master enable and recall/return-home teleporting master enable |
| Telemetry | Crash telemetry and breadcrumb enablement |

### Population
- `LimitPerPlayerOwnedTotal`
- `PerPlayerLimitScope`

The limit counts canonical profiles with a non-null owner, not only loaded NPCs. `ACTIVE`, `UNLOADED`, `CAPTURED`, `COOP`, `DEAD_REVIVABLE`, `LOST`, `RESTORING`, and conservatively classified dormant profiles all consume one owner slot. A zero limit disables denial but keeps tracking current so a later positive limit starts from the real population.

- `Global` counts an owner across the universe.
- `Per World` counts the profile's authoritative ownership world. Dormant companions retain their last ownership world.
- Owner transfers reserve the destination before releasing the source. A denied transfer leaves the old owner unchanged.
- Existing over-cap companions are preserved; later positive admissions are blocked until the owner is below the cap.
- A companion whose authoritative ownership world is unknown still counts globally, while per-world positive admissions remain blocked until reconciliation establishes its scope.
- Tamework currently has no automatic population-repair command. Diagnostics report the unresolved source; recovery requires complete save/SQLite backups and restoration of authoritative evidence or a supported release flow, not manual counter/row edits.

### Claim Integration
- `Provider` (`Auto`, `QuestLinesClaims`, `SimpleClaims`, or `Off`)
- `SimpleClaimsEnabled`
- `LimitPerClaimChunk`
- `LimitPerClaimTotal`
- `BreedingRequiresClaim`
- `ProtectTamedFromNonMembers`

The persisted names remain `SimpleClaims*` for compatibility, but population limits can use either supported provider:

- QuestLines Claims exactly `1.3.1`.
- SimpleClaims `>=1.0.38 <1.1.0`.

`Auto` prefers QuestLines Claims and tries SimpleClaims only if QuestLines Claims is absent or disabled. It does not bypass an installed-but-not-ready, incompatible, or broken QuestLines Claims. Explicit providers never fall back. `/tw settings` rejects unknown provider text; an unknown legacy file value remains visible as invalid instead of silently becoming `Auto`.

Applying settings invalidates provider caches without restarting the server. The next operation sees the new settings/provider generation, while a reservation already in flight keeps its original context.

Claim policy activation is operation-specific:

| Master enable | Population rule relevant to operation | Protect toggle | Behavior |
| --- | --- | --- | --- |
| Off | Any | Any | No population or damage provider work. Owner limits remain independent. |
| On | Positive chunk/total cap | Any | Tame, owner assignment, restore, release, relocation, and breeding placements enforce claim admission. |
| On | No positive cap, `BreedingRequiresClaim` on | Any | Breeding alone requires a claim; other admissions do not probe a provider. |
| On | No relevant population rule | Off | No provider work. |
| On | Any | On | Eligible live tamed targets use SimpleClaims native damage policy independently of population. |

Active population rule errors fail closed. SimpleClaims damage lookup/invocation errors fail open so an optional integration failure cannot make companions invulnerable. QuestLines Claims is population-only.

Claim limits count owned `ACTIVE` and durably `UNLOADED` physical companions. Captured, cooped, dead, and lost companions keep their owner slot but do not occupy a claim. Explicit placements are gated; natural movement is allowed and can create an over-cap claim that blocks later admissions.

`ProtectTamedFromNonMembers` is a legacy name for the native SimpleClaims tamed-target policy, not a membership-only deny. It preserves full-world protection, administrator/member permissions, player and party allies, and the claim's outsider setting. Eligibility requires a live tamed target; owned-but-not-tamed targets skip this claim policy.

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
- Needs resource mode (`Accurate`, `AutoFast`, or `AlwaysFast`) for food/water seeking and consumption performance
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
- Positive owner/claim admissions fail closed while population state is `LOADING`, `RECONCILING`, or `DEGRADED`. This avoids treating incomplete upgrade coverage as zero companions.
- Claim population and damage capabilities are independent. Enabling damage does not make breeding scan claims when no population rule is relevant.

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
- Before enabling a positive cap on an upgraded save, also back up the complete save and wait for `getPopulationDiagnostics()` readiness to report `READY` for the configured scope.

## Related Pages
- [Debugging and Debug Commands](/mod/alecs-tamework/debugging-and-debug-commands)
- [TwGlobalConfig Reference](/mod/alecs-tamework/twglobalconfig-reference)
- [TwNeedsConfig Reference](/mod/alecs-tamework/twneedsconfig-reference)
- [Integrations, Telemetry, and Build Workflow](/mod/alecs-tamework/integrations-telemetry-and-build-workflow)
- [Hooks, Bridges, and Optional Integrations](/mod/alecs-tamework/hooks-bridges-and-optional-integrations)
- [Diagnostics API Reference](/mod/alecs-tamework/diagnostics-api-reference)
- [Persistence, SQLite, and Data Paths](/mod/alecs-tamework/persistence-sqlite-and-data-paths)
