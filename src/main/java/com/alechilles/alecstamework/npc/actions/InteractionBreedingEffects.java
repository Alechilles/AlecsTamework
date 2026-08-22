package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
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
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Handles breeding interaction state changes without coupling to broader interaction orchestration.
 */
final class InteractionBreedingEffects {
    private static final int DEFAULT_MANUAL_SELECTION_SECONDS = 120;

    private final ActionTameworkInteract owner;
    private final BreedingOffspringService offspringService;

    InteractionBreedingEffects(ActionTameworkInteract owner) {
        this(owner, new BreedingClaimLimitPolicyService());
    }

    InteractionBreedingEffects(
            ActionTameworkInteract owner,
            BreedingClaimLimitPolicyService claimLimitPolicy
    ) {
        this.owner = owner;
        Tamework plugin = Tamework.getInstance();
        BreedingPairAdmissionRegistry admissionRegistry = plugin == null
                ? new BreedingPairAdmissionRegistry()
                : plugin.getBreedingPairAdmissionRegistry();
        this.offspringService = new BreedingOffspringService(
                new BreedingPartnerService(), admissionRegistry, claimLimitPolicy
        );
    }

    BreedingInteractionOutcome applyStartBreeding(
            @Nullable BreedInteraction interaction,
            @Nullable Ref<EntityStore> npcRef,
            @Nullable Role role,
            @Nullable Store<EntityStore> store,
            @Nullable Player player
    ) {
        if (interaction == null || npcRef == null || !npcRef.isValid() || store == null) {
            return finish(player, BreedingInteractionOutcome.unavailable());
        }
        UUID playerUuid = player != null ? player.getUuid() : null;
        if (playerUuid == null) {
            owner.logDebug("TameworkInteract: breeding blocked. No interacting player UUID resolved.");
            return finish(player, BreedingInteractionOutcome.unavailable());
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(npcRef, store);
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            owner.logDebug("TameworkInteract: breeding component type unavailable.");
            return finish(player, BreedingInteractionOutcome.unavailable());
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            owner.logDebug("TameworkInteract: no breeding component found for NPC.");
            return finish(player, BreedingInteractionOutcome.unavailable());
        }
        long breedingNowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        if (breeding.isCooldownActive(breedingNowMs)) {
            breeding.clearManualBreedingReady();
            store.putComponent(npcRef, breedingType, breeding);
            return finish(player, BreedingInteractionOutcome.cooldown());
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        BreedingInteractionOutcome eligibilityFailure = eligibilityFailure(
                config, role, npcRef, store, roleId);
        if (eligibilityFailure != null) {
            return finish(player, eligibilityFailure);
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
            if (breeding.isReady() || breeding.getManualBreedingUntilMs() != 0L) {
                breeding.setReady(false);
                breeding.clearManualBreedingReady();
                store.putComponent(npcRef, breedingType, breeding);
            }
            owner.logDebug(String.format(
                    "TameworkInteract: breeding blocked. baseHappiness=%.2f effectiveHappiness=%.2f threshold=%.2f",
                    happiness,
                    effectiveHappiness,
                    threshold
            ));
            return finish(player, BreedingInteractionOutcome.lowHappiness(threshold));
        }
        long manualSelectionUntilMs = ManualBreedingClock.nowMs() + (long) resolveManualSelectionSeconds(interaction) * 1000L;
        breeding.markManualBreedingReady(playerUuid, manualSelectionUntilMs);
        breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
        store.putComponent(npcRef, breedingType, breeding);
        BreedingInteractionOutcome outcome = offspringService.tryCompleteManualPairing(
                npcRef, store, breeding, config, playerUuid);
        if (outcome.completedPair()) {
            owner.logDebug("TameworkInteract: breeding pair matched and offspring spawn queued.");
        } else if (outcome.status() == BreedingInteractionOutcome.Status.WAITING_FOR_MATE) {
            owner.logDebug("TameworkInteract: manual breeding selection marked; waiting for second selected partner.");
        } else {
            owner.logDebug("TameworkInteract: breeding blocked. reason="
                    + outcome.status().name().toLowerCase());
        }
        return finish(player, outcome);
    }

    private int resolveManualSelectionSeconds(@Nullable BreedInteraction interaction) {
        Integer configured = interaction != null ? interaction.getManualSelectionSeconds() : null;
        if (configured == null || configured <= 0) {
            return DEFAULT_MANUAL_SELECTION_SECONDS;
        }
        return configured;
    }

    @Nullable
    private BreedingInteractionOutcome eligibilityFailure(
            @Nullable TwBreedingConfig config,
            @Nullable Role role,
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            @Nullable String roleId
    ) {
        if (config == null) {
            return null;
        }
        TwBreedingConfig.EligibilitySettings eligibility = config.resolveEligibility(roleId);
        if (eligibility.isRequireTamed() && !TamedStateResolver.isTamed(npcRef, store)) {
            owner.logDebug("TameworkInteract: breeding blocked. NPC is not tamed.");
            return BreedingInteractionOutcome.notTamed();
        }
        if (eligibility.isRequireAdult()) {
            if (!CompanionLifeStageService.isAdult(npcRef, store, roleId)) {
                owner.logDebug("TameworkInteract: breeding blocked. NPC is not adult. role=" + roleId);
                return BreedingInteractionOutcome.notAdult();
            }
        }
        String currentStateName = resolveCurrentStateName(role);
        if (BreedingEligibilityService.isBlockedByState(
                currentStateName,
                eligibility.isRequireNotSleeping(),
                eligibility.isRequireNotInCombat()
        )) {
            owner.logDebug("TameworkInteract: breeding blocked by state gate. state=" + currentStateName);
            return BreedingInteractionOutcome.stateBlocked();
        }
        return null;
    }

    private BreedingInteractionOutcome finish(
            @Nullable Player player,
            BreedingInteractionOutcome outcome
    ) {
        if (player == null || outcome == null) {
            return outcome == null ? BreedingInteractionOutcome.unavailable() : outcome;
        }
        BreedingInteractionOutcome.Feedback feedback = outcome.feedback();
        InteractionUiMessageService ui = new InteractionUiMessageService();
        if (outcome.warning()) {
            ui.showWarningKey(player, feedback.key(), feedback.arguments());
        } else {
            ui.showSuccessKey(player, feedback.key(), feedback.arguments());
        }
        return outcome;
    }

    @Nullable
    private String resolveCurrentStateName(@Nullable Role role) {
        StateSupport stateSupport = NpcSupportAccess.state(role, null, null);
        if (role == null || stateSupport == null || stateSupport.getStateHelper() == null) {
            return null;
        }
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
                fallback,
                TameworkRuntimeSettings.breedingHappinessRequirementEnabled(TwHappinessConfig.isEnabledForRole(roleId))
        );
    }
}
