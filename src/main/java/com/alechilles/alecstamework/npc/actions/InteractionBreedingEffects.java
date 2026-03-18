package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.BreedInteraction;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import javax.annotation.Nullable;

/**
 * Handles breeding interaction state changes without coupling to broader interaction orchestration.
 */
final class InteractionBreedingEffects {
    private final ActionTameworkInteract owner;
    private final BreedingOffspringService offspringService;

    InteractionBreedingEffects(ActionTameworkInteract owner) {
        this.owner = owner;
        this.offspringService = new BreedingOffspringService(new BreedingPartnerService());
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
        if (!breeding.isEnabled()) {
            owner.logDebug("TameworkInteract: breeding blocked. NPC breeding toggle is disabled.");
            if (breeding.isReady()) {
                breeding.setReady(false);
                store.putComponent(npcRef, breedingType, breeding);
            }
            return false;
        }
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        if (breeding.isCooldownActive(now)) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        if (!passesEligibilityGates(config, role, npcRef, store, roleId)) {
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
        double threshold = resolveThreshold(interaction, config, roleId);
        double effectiveHappiness = BreedingEligibilityService.resolveEffectiveHappiness(
                happiness,
                1.0,
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
        if (offspringService.tryCompletePairing(npcRef, store, breeding, config)) {
            owner.logDebug("TameworkInteract: breeding pair matched and offspring spawn queued.");
        } else {
            owner.logDebug("TameworkInteract: breeding ready; waiting for nearby partner.");
        }
        return true;
    }

    private boolean passesEligibilityGates(@Nullable TwBreedingConfig config,
                                           @Nullable Role role,
                                           Ref<EntityStore> npcRef,
                                           Store<EntityStore> store,
                                           @Nullable String roleId) {
        if (config == null) {
            return true;
        }
        TwBreedingConfig.EligibilitySettings eligibility = config.resolveEligibility(roleId);
        if (eligibility.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, store)) {
            owner.logDebug("TameworkInteract: breeding blocked. NPC is not tamed.");
            return false;
        }
        if (eligibility.isRequireAdult()) {
            if (!CompanionLifeStageService.isAdult(npcRef, store, roleId)) {
                owner.logDebug("TameworkInteract: breeding blocked. NPC is not adult. role=" + roleId);
                return false;
            }
        }
        String currentStateName = resolveCurrentStateName(role);
        if (BreedingEligibilityService.isBlockedByState(
                currentStateName,
                eligibility.isRequireNotSleeping(),
                eligibility.isRequireNotInCombat()
        )) {
            owner.logDebug("TameworkInteract: breeding blocked by state gate. state=" + currentStateName);
            return false;
        }
        return true;
    }

    @Nullable
    private String resolveCurrentStateName(@Nullable Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return null;
        }
        StateSupport stateSupport = role.getStateSupport();
        int currentState = stateSupport.getStateIndex();
        if (currentState == StateSupport.NO_STATE) {
            return null;
        }
        return stateSupport.getStateHelper().getStateName(currentState);
    }

    private double resolveThreshold(BreedInteraction interaction,
                                    @Nullable TwBreedingConfig config,
                                    @Nullable String roleId) {
        double fallback = config != null && config.resolveHappiness(roleId) != null
                ? config.resolveHappiness(roleId).getThreshold()
                : 0.0;
        return BreedingEligibilityService.resolveThreshold(
                interaction != null ? interaction.getMinHappiness() : null,
                fallback
        );
    }
}
