package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.InteractionEffectContext;
import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionPresetDefinition;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.CustomInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.DropItemEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.InteractionEntry;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModeStep;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ModifyStatsEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.PlaySoundEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.RemoveItemsInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetRoleEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetStateEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetTamedEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SpawnParticlesEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.TameInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.UiMessageEffect;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Tamework interact effects. */
final class TameworkInteractEffects {
    private final ActionTameworkInteract owner;
    private final InteractionInventoryEffects inventoryEffects;
    private final InteractionPresentationEffects presentationEffects;
    private final InteractionStateEffects stateEffects;
    private final InteractionModeCycleEffects modeCycleEffects;
    private final InteractionMountEffects mountEffects;
    private final InteractionHookEffects hookEffects;
    private final InteractionBreedingEffects breedingEffects;
    @Nullable
    private final InteractionExtensionRuntime extensionRuntime;

    TameworkInteractEffects(ActionTameworkInteract owner,
                            @Nullable InteractionExtensionRuntime extensionRuntime) {
        this.owner = owner;
        this.inventoryEffects = new InteractionInventoryEffects(owner);
        this.presentationEffects = new InteractionPresentationEffects(owner);
        this.stateEffects = new InteractionStateEffects();
        this.modeCycleEffects = new InteractionModeCycleEffects(owner, presentationEffects, stateEffects);
        this.mountEffects = new InteractionMountEffects(owner);
        this.hookEffects = new InteractionHookEffects(owner);
        this.breedingEffects = new InteractionBreedingEffects(owner);
        this.extensionRuntime = extensionRuntime;
    }

    boolean applyCustomEffects(@Nullable String interactionConfigId,
                               int interactionIndex,
                               @Nonnull InteractionEntry entry,
                               Effects effects,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player,
                               InteractionContextSnapshot ctx,
                               boolean harvestInteraction) {
        boolean applied = false;
        if (entry instanceof CustomInteraction customInteraction) {
            applied |= applyPresetEffects(
                    interactionConfigId,
                    interactionIndex,
                    customInteraction,
                    npcRef,
                    role,
                    infoProvider,
                    store,
                    player,
                    ctx,
                    harvestInteraction
            );
        }
        if (effects == null) {
            return applied;
        }
        SetRoleEffect setRole = effects.getSetRole();
        if (setRole != null) {
            applied |= applySetRole(setRole, npcRef, role, store, ctx);
        }
        SetTamedEffect setTamed = effects.getSetTamed();
        if (setTamed != null) {
            applied |= stateEffects.applySetTamed(setTamed, npcRef, store);
        }
        SetOwnerEffect setOwner = effects.getSetOwner();
        if (setOwner != null) {
            applied |= stateEffects.applySetOwner(setOwner, npcRef, store, player);
        }
        ModifyStatsEffect modifyStats = effects.getModifyStats();
        if (modifyStats != null) {
            applied |= stateEffects.applyModifyStats(modifyStats, npcRef, store);
        }
        SetStateEffect setState = effects.getSetState();
        if (setState != null) {
            applied |= stateEffects.applySetState(setState, npcRef, role, store);
        }
        RemoveItemsHandEffect removeItemsHand = effects.getRemoveItemsHand();
        if (removeItemsHand != null) {
            applied |= inventoryEffects.applyRemoveItemsHand(removeItemsHand, role, ctx, player);
        }
        AddItemsHandEffect addItemsHand = effects.getAddItemsHand();
        if (addItemsHand != null) {
            applied |= inventoryEffects.applyAddItemsHand(addItemsHand, role, ctx, player);
        }
        RemoveItemsInventoryEffect removeItemsInventory = effects.getRemoveItemsInventory();
        if (removeItemsInventory != null) {
            applied |= inventoryEffects.applyRemoveItemsInventory(removeItemsInventory, role, ctx, player);
        }
        AddItemInventoryEffect addItemInventory = effects.getAddItemInventory();
        if (addItemInventory != null) {
            applied |= inventoryEffects.applyAddItemInventory(addItemInventory, role, ctx, player);
        }
        if (Boolean.TRUE.equals(effects.getMount())) {
            applied |= mountEffects.applyMount(npcRef, role, infoProvider, store);
        }
        PlaySoundEffect playSound = effects.getPlaySound();
        if (playSound != null) {
            applied |= presentationEffects.applyPlaySound(playSound, npcRef, store, player);
        }
        SpawnParticlesEffect spawnParticles = effects.getSpawnParticles();
        if (spawnParticles != null) {
            try {
                applied |= presentationEffects.applySpawnParticles(spawnParticles, npcRef, role, store, player, ctx);
            } catch (RuntimeException | LinkageError ex) {
                logNonFatalEffectFailure("spawnParticles", ex);
            }
        }
        DropItemEffect dropItem = effects.getDropItem();
        if (dropItem != null) {
            applied |= inventoryEffects.applyDropItem(dropItem, npcRef, store, harvestInteraction);
        }
        HookEffect hookEffect = effects.getTriggerNpcHook();
        if (hookEffect != null) {
            applied |= hookEffects.applyTriggerNpcHook(hookEffect, npcRef, store, player);
        }
        FloatingTextEffect floatingText = effects.getShowFloatingText();
        if (floatingText != null) {
            applied |= presentationEffects.applyFloatingText(floatingText, npcRef, store, player);
        }
        UiMessageEffect uiMessage = effects.getShowUiMessage();
        if (uiMessage != null) {
            applied |= presentationEffects.applyUiMessage(uiMessage, player);
        }
        applied |= applyConfiguredCustomEffects(
                interactionConfigId,
                interactionIndex,
                effects.getCustom(),
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                harvestInteraction
        );
        return applied;
    }

    boolean applyStartTaming(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        return stateEffects.applyStartTaming(npcRef, store, player);
    }

    boolean applyTameRoleChange(TameInteraction interaction,
                                Ref<EntityStore> npcRef,
                                Role role,
                                Store<EntityStore> store,
                                InteractionContextSnapshot ctx) {
        if (interaction == null) {
            return false;
        }
        String roleId = resolveRoleId(interaction.getRole(), interaction.getRoleParam(), role, ctx);
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        boolean changed = stateEffects.applySetRole(roleId, npcRef, role, store);
        if (changed) {
            CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store, roleId);
        }
        return changed;
    }

    private boolean applySetRole(SetRoleEffect effect,
                                 Ref<EntityStore> npcRef,
                                 Role role,
                                 Store<EntityStore> store,
                                 InteractionContextSnapshot ctx) {
        if (effect == null) {
            return false;
        }
        String roleId = resolveRoleId(effect.getRole(), effect.getRoleParam(), role, ctx);
        if (roleId == null || roleId.isBlank()) {
            return false;
        }
        return stateEffects.applySetRole(roleId, npcRef, role, store);
    }

    private String resolveRoleId(String roleId, String roleParam, Role role, InteractionContextSnapshot ctx) {
        if (roleParam != null && !roleParam.isBlank()) {
            String resolved = owner.getRoleStringParam(role, ctx, roleParam);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return roleId;
    }

    boolean applyFeeding(Ref<EntityStore> npcRef,
                         Store<EntityStore> store,
                         double healAmount,
                         Player player,
                         InteractionContextSnapshot ctx) {
        if (healAmount > 0) {
            stateEffects.applyHeal(npcRef, store, healAmount);
            presentationEffects.showFeedingCombatText(npcRef, store, player, healAmount);
        }
        String heldItemId = ctx != null ? ctx.activeItemId : null;
        CompanionHappinessService.applyFeedGain(npcRef, store, heldItemId);
        CompanionNeedsService.applyFeedInteractionRefill(npcRef, store, heldItemId);
        return true;
    }

    boolean applyStartHarvest(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        return stateEffects.applyStartHarvest(npcRef, role, store);
    }

    // Mounts the interacting player using the mount helper.
    boolean applyMount(Ref<EntityStore> npcRef,
                       Role role,
                       InfoProvider infoProvider,
                       Store<EntityStore> store) {
        return mountEffects.applyMount(npcRef, role, infoProvider, store);
    }

    // Cycles the NPC's mode using the shared mode-cycle helper.
    boolean applyToggleMode(ModeStep[] cycle,
                            boolean showFloatingText,
                            boolean showUiMessage,
                            Ref<EntityStore> npcRef,
                            Role role,
                            Store<EntityStore> store,
                            Player player) {
        return modeCycleEffects.applyToggleMode(cycle, showFloatingText, showUiMessage, npcRef, role, store, player);
    }

    boolean applyStartBreeding(BreedInteraction interaction,
                               Ref<EntityStore> npcRef,
                               Role role,
                               Store<EntityStore> store) {
        return breedingEffects.applyStartBreeding(interaction, npcRef, role, store);
    }

    private boolean applyPresetEffects(@Nullable String interactionConfigId,
                                       int interactionIndex,
                                       @Nullable CustomInteraction interaction,
                                       Ref<EntityStore> npcRef,
                                       Role role,
                                       InfoProvider infoProvider,
                                       Store<EntityStore> store,
                                       Player player,
                                       InteractionContextSnapshot ctx,
                                       boolean harvestInteraction) {
        if (interaction == null
                || extensionRuntime == null
                || interaction.getPresetId() == null
                || interaction.getPresetId().isBlank()) {
            return false;
        }
        Optional<InteractionPresetDefinition> preset = extensionRuntime.resolvePreset(interaction.getPresetId());
        if (preset.isEmpty()) {
            return false;
        }
        InteractionEffectContext runtimeContext = buildEffectContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                harvestInteraction
        );
        if (runtimeContext == null) {
            return false;
        }
        boolean applied = false;
        for (InteractionEffectSpec effectSpec : preset.get().effects()) {
            applied |= extensionRuntime.applyEffect(effectSpec, runtimeContext);
        }
        return applied;
    }

    private boolean applyConfiguredCustomEffects(@Nullable String interactionConfigId,
                                                 int interactionIndex,
                                                 @Nullable CustomEffect[] customEffects,
                                                 Ref<EntityStore> npcRef,
                                                 Role role,
                                                 InfoProvider infoProvider,
                                                 Store<EntityStore> store,
                                                 Player player,
                                                 InteractionContextSnapshot ctx,
                                                 boolean harvestInteraction) {
        if (extensionRuntime == null || customEffects == null || customEffects.length == 0) {
            return false;
        }
        InteractionEffectContext runtimeContext = buildEffectContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx,
                harvestInteraction
        );
        if (runtimeContext == null) {
            return false;
        }
        boolean applied = false;
        for (CustomEffect customEffect : customEffects) {
            if (customEffect == null) {
                continue;
            }
            InteractionEffectSpec spec = new InteractionEffectSpec(
                    customEffect.getId(),
                    customEffect.getParam(),
                    toValues(customEffect.getValues()),
                    customEffect.getJsonPayload()
            );
            applied |= extensionRuntime.applyEffect(spec, runtimeContext);
        }
        return applied;
    }

    @Nullable
    private InteractionEffectContext buildEffectContext(@Nullable String interactionConfigId,
                                                        int interactionIndex,
                                                        Ref<EntityStore> npcRef,
                                                        Role role,
                                                        InfoProvider infoProvider,
                                                        Store<EntityStore> store,
                                                        Player player,
                                                        InteractionContextSnapshot ctx,
                                                        boolean harvestInteraction) {
        if (npcRef == null || role == null || store == null) {
            return null;
        }
        return new InteractionEffectContext(
                interactionConfigId,
                interactionIndex,
                npcRef,
                role,
                infoProvider,
                store,
                player,
                ctx != null ? ctx.playerId : null,
                ctx != null ? ctx.activeItemId : null,
                harvestInteraction
        );
    }

    private List<String> toValues(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    // Logs and suppresses non-fatal custom effect failures to avoid world-thread crashes.
    private void logNonFatalEffectFailure(String effectName, Throwable error) {
        String message = "TameworkInteract: " + effectName
                + " effect failed; skipping effect to keep interaction flow alive.";
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            if (error == null) {
                instance.getLogger().at(Level.WARNING).log(message);
            } else {
                instance.getLogger().at(Level.WARNING).withCause(error).log(message);
            }
            return;
        }
        owner.logUnsupported(message);
    }

}
