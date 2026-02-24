package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nullable;

/**
 * Handles breeding interaction state changes without coupling to broader interaction orchestration.
 */
final class InteractionBreedingEffects {
    private final ActionTameworkInteract owner;

    InteractionBreedingEffects(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    boolean applyStartBreeding(@Nullable BreedInteraction interaction,
                               @Nullable Ref<EntityStore> npcRef,
                               @Nullable Role role,
                               @Nullable Store<EntityStore> store) {
        if (interaction == null || npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            owner.logDebug("TameworkInteract: breeding component type unavailable.");
            return false;
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            owner.logDebug("TameworkInteract: no breeding component found for NPC.");
            return false;
        }
        long now = System.currentTimeMillis();
        if (breeding.isCooldownActive(now)) {
            return false;
        }
        String roleId = resolveRoleId(npcRef, role, store);
        TwBreedingConfig config = roleId != null ? TwBreedingConfig.resolveForRole(roleId) : null;
        if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                && config != null
                && config.getId() != null
                && !config.getId().isBlank()) {
            breeding.setConfigId(config.getId());
        }
        double threshold = resolveThreshold(interaction, config);
        if (breeding.getHappiness() < threshold) {
            return false;
        }
        breeding.setReady(true);
        breeding.setLastHappinessUpdateMs(now);
        store.putComponent(npcRef, breedingType, breeding);
        return true;
    }

    private double resolveThreshold(BreedInteraction interaction, @Nullable TwBreedingConfig config) {
        if (interaction != null && interaction.getMinHappiness() != null) {
            return interaction.getMinHappiness();
        }
        if (config != null && config.getHappiness() != null) {
            return config.getHappiness().getThreshold();
        }
        return 0.0;
    }

    @Nullable
    private String resolveRoleId(Ref<EntityStore> npcRef, Role role, Store<EntityStore> store) {
        if (role != null && role.getRoleName() != null && !role.getRoleName().isBlank()) {
            return role.getRoleName();
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return null;
        }
        String roleName = npc.getRoleName();
        if (roleName != null && !roleName.isBlank()) {
            return roleName;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex < 0) {
            return null;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        String resolved = npcPlugin.getName(roleIndex);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }
}
