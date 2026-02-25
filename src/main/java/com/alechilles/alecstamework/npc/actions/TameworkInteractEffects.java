package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemInventoryEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.AddItemsHandEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.DropItemEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FloatingTextEffect;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
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
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

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

    TameworkInteractEffects(ActionTameworkInteract owner) {
        this.owner = owner;
        this.inventoryEffects = new InteractionInventoryEffects(owner);
        this.presentationEffects = new InteractionPresentationEffects();
        this.stateEffects = new InteractionStateEffects();
        this.modeCycleEffects = new InteractionModeCycleEffects(owner, presentationEffects, stateEffects);
        this.mountEffects = new InteractionMountEffects(owner);
        this.hookEffects = new InteractionHookEffects(owner);
        this.breedingEffects = new InteractionBreedingEffects(owner);
    }

    boolean applyCustomEffects(Effects effects,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player,
                               InteractionContextSnapshot ctx,
                               boolean harvestInteraction) {
        if (effects == null) {
            return false;
        }
        boolean applied = false;
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
            applied |= presentationEffects.applySpawnParticles(spawnParticles, npcRef, store, player);
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
        return stateEffects.applySetRole(roleId, npcRef, role, store);
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

    boolean applyFeeding(Ref<EntityStore> npcRef, Store<EntityStore> store, double healAmount, Player player) {
        if (healAmount > 0) {
            stateEffects.applyHeal(npcRef, store, healAmount);
            presentationEffects.showFeedingCombatText(npcRef, store, player, healAmount);
        }
        CompanionHappinessService.applyFeedGain(npcRef, store);
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

}
