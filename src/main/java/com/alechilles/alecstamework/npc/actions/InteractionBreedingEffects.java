package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        if (config != null && config.getEligibility().isRequireTamed() && !TamedStateResolver.isTamed(npcRef, store)) {
            return false;
        }
        if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                && config != null
                && config.getId() != null
                && !config.getId().isBlank()) {
            breeding.setConfigId(config.getId());
        }
        double happiness = CompanionHappinessService.resolveCurrentValue(npcRef, store, breeding.getHappiness());
        if (Math.abs(breeding.getHappiness() - happiness) > 0.000001) {
            breeding.setHappiness(happiness);
        }
        double threshold = resolveThreshold(interaction, config);
        if (happiness < threshold) {
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
}
