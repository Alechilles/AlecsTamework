package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
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
        double fertilityMultiplier = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                "FertilityMultiplier",
                1.0
        );
        double effectiveHappiness = BreedingEligibilityService.resolveEffectiveHappiness(
                happiness,
                fertilityMultiplier,
                interaction != null ? interaction.getFertilityBonus() : null
        );
        if (!BreedingEligibilityService.isEligible(effectiveHappiness, threshold)) {
            if (breeding.isReady()) {
                breeding.setReady(false);
                store.putComponent(npcRef, breedingType, breeding);
            }
            owner.logDebug(String.format(
                    "TameworkInteract: breeding blocked. baseHappiness=%.2f effectiveHappiness=%.2f threshold=%.2f",
                    happiness,
                    effectiveHappiness,
                    threshold
            ));
            return false;
        }
        breeding.setReady(true);
        breeding.setLastHappinessUpdateMs(now);
        store.putComponent(npcRef, breedingType, breeding);
        return true;
    }

    private double resolveThreshold(BreedInteraction interaction, @Nullable TwBreedingConfig config) {
        double fallback = config != null && config.getHappiness() != null
                ? config.getHappiness().getThreshold()
                : 0.0;
        return BreedingEligibilityService.resolveThreshold(
                interaction != null ? interaction.getMinHappiness() : null,
                fallback
        );
    }
}
