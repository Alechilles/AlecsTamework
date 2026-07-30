# Companion Movement Speed Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Give companions one configurable, quantized movement-speed multiplier that applies equally to unmounted NPC motion and native mounted-player movement, with species, attachment, trait, level-growth, and talent inputs.

**Architecture:** Resolve the effective multiplier once from a role-scoped config, effective attachments, and the existing progression modifier service. Apply that value through two small adapters: a companion-owned static EntityEffect for NPC AI movement and a copied/scaled `MovementSettings` profile for native riders. A sync system handles lifecycle and external changes; successful attachment mutations request an immediate refresh.

**Tech Stack:** Java 25, Hytale 0.5.7 server ECS/asset APIs, Tamework JSON asset codecs, JUnit 5, Maven.

## Non-goals and invariants

- Do not revive, configure, or reference the legacy `TameworkRide` controller.
- Do not change `TameworkMountedGlide` or `TameworkAvatarFlight`; their movement models stay independent.
- Preserve the native role's `MountMovementConfig` as the base rider profile. Only its copied `MovementSettings.BaseSpeed` is scaled.
- The multiplier applies to both unmounted AI motion and the native rider. It must not stack twice.
- The managed effect family is `Tw_MovementSpeed_*`; only this family and legacy `Tw_Trait_MoveSpeed_*` effects may be removed. Never remove base-game or third-party effects.
- Runtime systems may not mutate components directly. Queue mutations via `CommandBuffer` / its store callback, and resolve players in the active world/store rather than via `PlayerRef.getComponent(Player)` or `Universe.getPlayers()`.
- Missing config is neutral and safe: base `1.0`, bounds `0.50`–`2.00`, no attachment modifiers. Missing effect assets, movement config, mounted rider, or expected components must skip safely and log once with useful IDs.

## File map

| Area | Files |
|---|---|
| Config family | `config/assets/TwCompanionMovementConfig.java`, `config/assets/TwCompanionMovementConfigCodec.java`, `config/overrides/TwConfigFamily.java`, `api/TameworkConfigFamily.java`, `ui/TwConfigSchemaAdapter.java`, `Tamework.java` |
| Shared calculation | `npc/progression/CompanionMovementSpeedResolver.java`, `npc/progression/CompanionMovementSpeedEffectIdResolver.java` |
| NPC effect | `npc/progression/CompanionMovementSpeedEffectService.java`, `npc/progression/CompanionTraitEffectService.java` |
| Native mount movement | `npc/movement/NativeMountMovementSettingsService.java`, `npc/actions/InteractionMountEffects.java` |
| Refresh lifecycle | `npc/systems/CompanionMovementSpeedSyncSystem.java`, `npc/actions/HeldItemAttachmentInteractionService.java`, `Tamework.java` |
| Assets and docs | `src/main/resources/Server/Entity/Effects/Tw_MovementSpeed_*.json`, `wiki/Modder-Documentation/Config-Reference/TwCompanionMovementConfig-Reference.md`, `wiki/Modder-Documentation/Config-Reference/index.md`, `docs/Config-Discovery.md`, `CHANGELOG.md` |
| Tests | New focused config, resolver, effect-ID, native-settings, and sync-system tests; update relevant existing movement/effect tests |

## Task 1: Add the inheritable, role-scoped movement config family

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/config/assets/TwCompanionMovementConfig.java`
- Create: `src/main/java/com/alechilles/alecstamework/config/assets/TwCompanionMovementConfigCodec.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Modify: `src/main/java/com/alechilles/alecstamework/config/overrides/TwConfigFamily.java`
- Modify: `src/main/java/com/alechilles/alecstamework/api/TameworkConfigFamily.java`
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapter.java`
- Create: `src/test/java/com/alechilles/alecstamework/config/assets/TwCompanionMovementConfigTest.java`
- Create: `src/main/resources/Server/Tamework/CompanionMovement/Default.json`

**Step 1: Write failing config-resolution tests.**

Model the test fixture on `TwDynamicAttachmentsConfigTest`. Cover config selection by `Enabled`, normalized `RoleIds`, highest `Priority`, then normalized asset ID; ordinary parent fallback; omitted scalar inheritance; and replacement (not merging) of `AttachmentModifiers` when a child explicitly supplies the array. Verify a no-match resolves the neutral defaults.

```java
assertEquals(1.15, resolved.baseMoveSpeedMultiplier());
assertEquals(0.50, unresolved.minMoveSpeedMultiplier());
assertTrue(unresolved.attachmentModifiers().isEmpty());
```

**Step 2: Implement `TwCompanionMovementConfig`.**

Follow the established `TwDynamicAttachmentsConfig` asset/fallback pattern rather than inventing a second inheritance model. Include `Enabled`, `Priority`, `RoleIds`, nullable `BaseMoveSpeedMultiplier`, nullable `MinMoveSpeedMultiplier`, nullable `MaxMoveSpeedMultiplier`, and nullable `AttachmentModifiers`. Represent each modifier as an immutable nested value with `Slot`, `Values`, and `Multiplier`. Keep omitted values distinguishable from explicit values until fallback is applied.

Expose a focused `resolveForRole(String roleId)` API returning a normalized immutable resolved record. It must use one matching config only; lower priority configs never contribute individual fields or modifiers.

**Step 3: Register and invalidate the family.**

Register `HytaleAssetStore<TwCompanionMovementConfig>` at `Tamework/CompanionMovement` alongside the dynamic-attachment asset registration. Add `COMPANION_MOVEMENT` to both public/config override family enums and schema handling, including all exhaustive switches. On asset loaded/removed, clear the role-resolution cache and emit the normal experimental config-reload signal so active companions are resynchronized.

**Step 4: Supply a neutral default asset.**

Create `Default.json` with the explicit neutral values below. It makes the fallback behaviour discoverable without imposing a species rule.

```json
{
  "Enabled": true,
  "Priority": -1000,
  "RoleIds": [],
  "BaseMoveSpeedMultiplier": 1.0,
  "MinMoveSpeedMultiplier": 0.5,
  "MaxMoveSpeedMultiplier": 2.0,
  "AttachmentModifiers": []
}
```

**Step 5: Run focused tests.**

Run `./mvnw test -Dtest=TwCompanionMovementConfigTest` from Git Bash. Fix the codec/fallback implementation until it passes.

**Step 6: Commit.**

```bash
git add src/main/java/com/alechilles/alecstamework/config src/main/java/com/alechilles/alecstamework/api/TameworkConfigFamily.java src/main/java/com/alechilles/alecstamework/ui/TwConfigSchemaAdapter.java src/main/java/com/alechilles/alecstamework/Tamework.java src/main/resources/Server/Tamework/CompanionMovement src/test/java/com/alechilles/alecstamework/config/assets/TwCompanionMovementConfigTest.java
git commit -m "Feat: add companion movement config"
```

## Task 2: Implement the pure multiplier and static effect-ID resolution

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedResolver.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedEffectIdResolver.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedResolverTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedEffectIdResolverTest.java`
- Create: `src/main/resources/Server/Entity/Effects/Tw_MovementSpeed_050.json` through `Tw_MovementSpeed_200.json`, excluding `100`

**Step 1: Write failing resolver tests.**

Test the exact formula, multiple matching attachment modifiers, no matching modifier, malformed/non-finite input, clamp boundaries, and nearest-5%-step quantization. Include a regression for the accepted saddle example:

```java
var result = resolver.resolve(config(1.10, 0.50, 2.00, saddle("Yes", 1.10)),
    attachments("Saddle", "Yes"), 1.05);
assertEquals(1.25, result.quantizedMultiplier());
```

Test that `0.50`, `1.05`, and `2.00` resolve to `Tw_MovementSpeed_050`, `Tw_MovementSpeed_105`, and `Tw_MovementSpeed_200`; `1.00` resolves to no managed effect; legacy IDs are recognized only for cleanup.

**Step 2: Implement the pure multiplier resolver.**

Keep it free of ECS, assets, and logging. Its inputs are the resolved config, an effective attachment view, and the progression multiplier. Multiply the base by every matching modifier, then by progression; replace invalid/non-positive intermediate values with neutral `1.0` before the final clamp. Clamp to normalized bounds, quantize using integer hundredths to avoid binary rounding surprises, and return a record containing raw, clamped, and quantized values plus the selected config ID for diagnostics/fingerprints.

Use `CompanionProgressionModifierService.resolveMultiplier(npcRef, store, "MoveSpeedMultiplier", 1.0)` at callers, not a duplicate trait/level/talent calculation.

**Step 3: Implement managed and legacy effect classification.**

`CompanionMovementSpeedEffectIdResolver` owns the supported `0.50`–`2.00` 5%-step range. It must provide:

```java
@Nullable String resolveManagedEffectId(double quantizedMultiplier);
boolean isManagedEffectId(@Nullable String effectId);
boolean isLegacyEffectId(@Nullable String effectId);
```

Only return `null` for exactly neutral `1.00`. Do not make the old `TraitMoveSpeedEffectResolver` the owner of the new family; retain it only where compatibility tests or old callers still require it, then remove its runtime use in Task 3.

**Step 4: Add static EntityEffect assets.**

Generate the thirty non-neutral JSON assets from the existing trait speed effect shape. Each asset ID is `Tw_MovementSpeed_NNN` and sets `ApplicationEffects.HorizontalSpeedMultiplier` to the matching decimal. Do not overwrite or delete the legacy `Tw_Trait_MoveSpeed_*` assets.

**Step 5: Run focused tests and inspect assets.**

```bash
./mvnw test -Dtest=CompanionMovementSpeedResolverTest,CompanionMovementSpeedEffectIdResolverTest
rg '"Id": "Tw_MovementSpeed_|"HorizontalSpeedMultiplier"' src/main/resources/Server/Entity/Effects/Tw_MovementSpeed_*.json
```

**Step 6: Commit.**

```bash
git add src/main/java/com/alechilles/alecstamework/npc/progression src/main/resources/Server/Entity/Effects/Tw_MovementSpeed_*.json src/test/java/com/alechilles/alecstamework/npc/progression
git commit -m "Feat: resolve companion movement speed"
```

## Task 3: Replace progression-only speed effects with owned companion effects

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedEffectService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/CompanionTraitEffectService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/progression/TraitMoveSpeedEffectResolver.java` only if required to leave a compatibility shim
- Create: `src/test/java/com/alechilles/alecstamework/npc/progression/CompanionMovementSpeedEffectServiceTest.java`

**Step 1: Write failing ownership tests.**

Test the service's effect-family selection independently of Hytale bootstrap, including neutral removal, replacement of an older managed ID, removal of legacy trait speed IDs, preservation of unrelated effects, and missing-asset skip behaviour. Keep engine integration narrow by extracting package-private pure helpers for the candidate/remove set where needed.

**Step 2: Implement the narrowly-owned effect service.**

`CompanionMovementSpeedEffectService.apply(...)` must resolve the shared multiplier, calculate the one desired managed effect ID, remove only previous `Tw_MovementSpeed_*` and `Tw_Trait_MoveSpeed_*` effects, then add the desired effect if non-neutral. Preserve existing `EffectControllerComponent` usage and invalidate `NPCEntity`'s cached horizontal-speed multiplier only when the managed state actually changes. Log a given missing asset once, including entity and effect IDs.

**Step 3: Migrate the existing trait effect orchestration.**

Keep `CompanionTraitEffectService` responsible for registered trait effects, but replace its `applyMoveSpeedEffect` implementation with a delegation to `CompanionMovementSpeedEffectService`. This keeps every existing trait, level, talent, breeding, and command call site as an immediate NPC-speed refresh point while preventing the old 0.80–1.30 progression-only effect from being applied.

**Step 4: Run focused tests.**

```bash
./mvnw test -Dtest=CompanionMovementSpeedEffectServiceTest,TraitMoveSpeedEffectResolverTest
```

**Step 5: Commit.**

```bash
git add src/main/java/com/alechilles/alecstamework/npc/progression src/test/java/com/alechilles/alecstamework/npc/progression
git commit -m "Feat: apply companion movement effects"
```

## Task 4: Scale native rider settings from the same resolved multiplier

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/NativeMountMovementSettingsService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/movement/NativeMountMovementSettingsServiceTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java`

**Step 1: Write failing base-settings scaler tests.**

Extract a package-private pure method that copies a `MovementSettings` and scales only `BaseSpeed`. Test that the original object remains unchanged, `BaseSpeed` is multiplied exactly once, non-speed fields survive unchanged, and invalid inputs fall back to neutral. Add a source-level regression asserting `InteractionMountEffects` delegates rider setting application to the new service instead of directly setting default settings.

**Step 2: Implement `NativeMountMovementSettingsService`.**

Give it a small API that accepts the source role ID, rider entity/component context, store, and already-quantized multiplier. It resolves the active role's `MountMovementConfig` (including the existing `"Mount"` fallback), calls `MovementConfig.toPacket()`, makes a defensive `MovementSettings` copy, scales `BaseSpeed`, then performs the established `MovementManager.setDefaultSettings`, `applyDefaultSettings`, and packet `update` sequence. It must not mutate the config asset or cache a rider's settings.

**Step 3: Apply the scaled setting before the native role swap.**

In `InteractionMountEffects.applyNativeMount`, keep the original source `Role` and compute the companion multiplier from that role before requesting `Empty_Role`. Pass the resulting multiplier and original role ID to `NativeMountMovementSettingsService`. This is essential because a native mount changes the NPC's visible role to `Empty_Role`.

**Step 4: Establish mounted source-role recovery for later sync.**

Add a focused helper (in the new movement service or a dedicated package-private resolver) that returns the managed role ID:

- unmounted: current `NPCEntity` role;
- native-mounted: `NPCMountComponent.getOriginalRoleIndex()` mapped through `NPCPlugin.get().getName(index)`.

Before coding, re-check this exact 0.5.7 contract in Hytale source. Recover the rider from the active `Store`/world using stable identity from the mount component; never call `PlayerRef.getComponent(Player)` and never scan `Universe.getPlayers()`.

**Step 5: Run focused tests.**

```bash
./mvnw test -Dtest=NativeMountMovementSettingsServiceTest,InteractionMountEffectsTest
```

**Step 6: Commit.**

```bash
git add src/main/java/com/alechilles/alecstamework/npc/movement src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java src/test/java/com/alechilles/alecstamework/npc/movement src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java
git commit -m "Feat: scale native mount movement"
```

## Task 5: Synchronize lifecycle changes and refresh saddle changes immediately

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/systems/CompanionMovementSpeedSyncSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/HeldItemAttachmentInteractionService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/systems/CompanionMovementSpeedSyncSystemTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/systems/PlayerMovementEffectDelegationGuardTest.java` if its source guard needs the new approved service

**Step 1: Write failing fingerprint/lifecycle tests.**

Follow `CompanionAttachmentSyncSystem`'s `StoreScopedState` pattern. Test a fingerprint changes for source role, effective attachment state, resolved progression multiplier, config revision, native-mounted state, and mounted rider identity. Test that a no-change tick does not schedule writes, attachment changes schedule both NPC effect and rider settings refresh, and a mounted-to-unmounted transition does not reapply rider settings after the base dismount reset.

**Step 2: Implement the sync system.**

Query companion NPCs and attachment state, track a compact per-entity fingerprint in `StoreScopedState`, and use a modest periodic safety sweep (matching the nearby attachment-sync cadence). On change, queue a `CommandBuffer` store callback that:

1. resolves the effective/source role using Task 4's helper;
2. calculates the shared quantized multiplier;
3. applies `CompanionMovementSpeedEffectService` to the NPC;
4. if a native mount is still active, updates the rider through `NativeMountMovementSettingsService`.

The config asset load/removal callback from Task 1 must cause the fingerprint to become stale (revision increment or equivalent), so reloads are picked up without scanning player state.

**Step 3: Add immediate interaction refresh.**

After each successful atomic attachment mutation/exchange in `HeldItemAttachmentInteractionService`, request a same-world refresh through a small explicit entry point owned by `CompanionMovementSpeedSyncSystem` (or a narrowly named refresh service it owns). The entry point must queue/perform the same shared calculation rather than duplicate attachment matching. It must run only after the updated `TameworkAttachmentsComponent` is visible and must use the interaction's current store context, not global player lookup.

**Step 4: Register in correct order.**

Register `CompanionMovementSpeedSyncSystem` in `Tamework.java` after dynamic attachment evaluation and attachment synchronization, so it observes effective attachment changes. Do not add a component registration: fingerprint state is store-scoped, not persisted on entities.

**Step 5: Run focused safety tests and required searches.**

```bash
./mvnw test -Dtest=CompanionMovementSpeedSyncSystemTest,PlayerMovementEffectDelegationGuardTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Refactor any runtime/tick-path match before continuing.

**Step 6: Commit.**

```bash
git add src/main/java/com/alechilles/alecstamework/npc/systems/CompanionMovementSpeedSyncSystem.java src/main/java/com/alechilles/alecstamework/npc/actions/HeldItemAttachmentInteractionService.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/systems src/test/java/com/alechilles/alecstamework/npc/systems/PlayerMovementEffectDelegationGuardTest.java
git commit -m "Feat: sync companion movement speed"
```

## Task 6: Document and validate the shipped feature

**Files:**
- Create: `wiki/Modder-Documentation/Config-Reference/TwCompanionMovementConfig-Reference.md`
- Modify: `wiki/Modder-Documentation/Config-Reference/index.md`
- Modify: `docs/Config-Discovery.md`
- Modify: `CHANGELOG.md`
- Modify: relevant `docs/Interactions.md` wording only if it currently implies all player movement effects are deliberately unsupported
- Create or update an example config under `src/main/resources/Server/Tamework/CompanionMovement/`

**Step 1: Document schema and attachment matching.**

Describe parent fallback, role priority selection, formula/order of operations, `AttachmentModifiers` array replacement, valid range, 5% quantization, and a saddle example. Make clear that `MountMovementConfig` establishes base controls while this config scales speed for both movement modes.

```json
{
  "RoleIds": ["Horse"],
  "BaseMoveSpeedMultiplier": 1.10,
  "AttachmentModifiers": [
    { "Slot": "Saddle", "Values": ["Yes"], "Multiplier": 1.10 }
  ]
}
```

**Step 2: Add a concise player-facing changelog entry.**

State that companion species, equipment, traits, levels, and talents can now influence travel speed on foot and while natively ridden. Do not mention internal effect IDs or controllers.

**Step 3: Run the complete verification suite.**

```bash
./mvnw test
git diff --check
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
./scripts/tools/check-agent-docs.ps1
```

If the repository's configured shell cannot invoke the last script directly, run its PowerShell command only for this provided documentation check; keep all normal repository work in Git Bash. Inspect the packaged resource tree to confirm all thirty non-neutral `Tw_MovementSpeed_*` assets are present and legacy assets remain.

**Step 4: Commit.**

```bash
git add CHANGELOG.md docs/Config-Discovery.md docs/Interactions.md wiki/Modder-Documentation/Config-Reference src/main/resources/Server/Tamework/CompanionMovement
git commit -m "Docs: document companion movement speed"
```

## Final acceptance checklist

- A horse (or any configured species) has its base multiplier on foot and while natively mounted.
- Equipping/removing a matching saddle immediately changes the NPC effect and, if mounted, the rider's `BaseSpeed` multiplier.
- Trait, level growth, and talent `MoveSpeedMultiplier` changes propagate through the same calculation with no duplicate effect.
- The result is clamped and quantized before either applier sees it.
- Native mounts retain their base `MountMovementConfig` behavior, and base dismount reset is not overwritten.
- Non-native ride/glide/flight modes remain unaffected.
- Third-party EntityEffects persist after companion speed refreshes.
- Config reload, spawn/load, and external attachment writes converge via the sync system.
- Full Maven tests, architecture guards, docs checks, and `git diff --check` pass.
