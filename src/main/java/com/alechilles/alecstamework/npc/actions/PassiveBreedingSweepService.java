package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Performs coarse passive breeding sweeps and refreshes breeding readiness from current eligibility.
 *
 * <p>The sweep updates the persisted ready flag, then attempts pair matching through the shared
 * offspring service when a companion is ready and not on cooldown.
 */
public final class PassiveBreedingSweepService {
    private final BreedingOffspringService offspringService;
    private final BreedingPopulationTypeService populationTypeService;

    public PassiveBreedingSweepService() {
        this.offspringService = new BreedingOffspringService(new BreedingPartnerService());
        this.populationTypeService = new BreedingPopulationTypeService();
    }

    public void runSweep(@Nullable Store<EntityStore> store, long nowMs) {
        if (store == null) {
            return;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TransformComponent> transformType = TransformComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (npcType == null || transformType == null || breedingType == null) {
            return;
        }

        List<PassiveBreedingBirthReservation> birthReservations = new ArrayList<>();
        Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer> claimReservations =
                new HashMap<>();
        Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer> ownerReservations =
                new HashMap<>();
        store.forEachChunk(
                Query.and(npcType, transformType, breedingType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        processChunk(
                                chunk,
                                commandBuffer,
                                npcType,
                                transformType,
                                breedingType,
                                store,
                                nowMs,
                                birthReservations,
                                claimReservations,
                                ownerReservations
                        )
        );
    }

    private void processChunk(@Nonnull ArchetypeChunk<EntityStore> chunk,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer,
                              @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                              @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                              @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
                              @Nonnull Store<EntityStore> store,
                              long nowMs,
                              @Nonnull List<PassiveBreedingBirthReservation> birthReservations,
                              @Nonnull Map<BreedingClaimLimitPolicyService.ClaimReservationKey, Integer>
                                      claimReservations,
                              @Nonnull Map<BreedingClaimLimitPolicyService.PlayerReservationKey, Integer>
                                      ownerReservations) {
        int size = chunk.size();
        for (int i = 0; i < size; i++) {
            Ref<EntityStore> ref = chunk.getReferenceTo(i);
            NPCEntity npc = chunk.getComponent(i, npcType);
            TransformComponent transform = chunk.getComponent(i, transformType);
            TameworkBreedingComponent breeding = chunk.getComponent(i, breedingType);
            if (ref == null || !ref.isValid() || npc == null || transform == null || breeding == null) {
                continue;
            }
            String roleId = resolveRoleId(npc);
            if (roleId == null || roleId.isBlank()) {
                continue;
            }
            TwBreedingConfig config = BreedingConfigResolver.resolveConfig(ref, store, breeding);
            if (config == null
                    || !config.isEnabled()
                    || !TameworkRuntimeSettings.passiveBreedingEnabled(config.resolvePassiveBreeding(roleId).isEnabled())) {
                continue;
            }

            PassiveBreedingSweepCandidate candidate = new PassiveBreedingSweepCandidate(
                    ref,
                    npc,
                    roleId,
                    new Vector3d(transform.getPosition()),
                    config
            );
            boolean cooldownActive = breeding.isCooldownActive(nowMs);
            boolean shouldBeReady = !cooldownActive && resolveShouldBeReady(candidate, breeding, store);
            if (breeding.isReady() != shouldBeReady) {
                breeding.setReady(shouldBeReady);
                commandBuffer.putComponent(ref, breedingType, breeding);
            }
            if (!breeding.isEnabled() || !shouldBeReady || cooldownActive) {
                continue;
            }
            if (isOvercrowded(candidate, store, birthReservations)) {
                continue;
            }
            boolean paired = offspringService.tryCompletePairing(
                    ref,
                    store,
                    breeding,
                    config,
                    claimReservations,
                    ownerReservations,
                    commandBuffer
            );
            if (paired) {
                reserveNearbyBirth(candidate, birthReservations);
            }
        }
    }

    private void reserveNearbyBirth(
            @Nonnull PassiveBreedingSweepCandidate candidate,
            @Nonnull List<PassiveBreedingBirthReservation> reservations
    ) {
        String typeKey = populationTypeService.resolveTypeKey(
                candidate.roleId(), candidate.config()
        );
        if (typeKey != null && !typeKey.isBlank()) {
            reservations.add(new PassiveBreedingBirthReservation(
                    typeKey, new Vector3d(candidate.position())
            ));
        }
    }

    private static boolean resolveShouldBeReady(@Nonnull PassiveBreedingSweepCandidate candidate,
                                                @Nonnull TameworkBreedingComponent breeding,
                                                @Nonnull Store<EntityStore> store) {
        return breeding.isEnabled()
                && passesHappinessThreshold(candidate, breeding, store)
                && passesEligibilityGates(candidate, store);
    }

    private static boolean passesHappinessThreshold(@Nonnull PassiveBreedingSweepCandidate candidate,
                                                    @Nonnull TameworkBreedingComponent breeding,
                                                    @Nonnull Store<EntityStore> store) {
        double happiness = CompanionHappinessService.resolveCurrentValue(candidate.ref(), store, breeding.getHappiness());
        double threshold = BreedingEligibilityService.resolveThreshold(
                null,
                TameworkRuntimeSettings.breedingHappinessThreshold(
                        candidate.config().resolveHappiness(candidate.roleId()).getThreshold(),
                        TwHappinessConfig.isEnabledForRole(candidate.roleId())
                )
        );
        double effectiveHappiness = BreedingEligibilityService.resolveEffectiveHappiness(happiness, 1.0, null);
        return BreedingEligibilityService.isEligible(effectiveHappiness, threshold);
    }

    private static boolean passesEligibilityGates(@Nonnull PassiveBreedingSweepCandidate candidate,
                                                  @Nonnull Store<EntityStore> store) {
        TwBreedingConfig.EligibilitySettings eligibility = candidate.config().resolveEligibility(candidate.roleId());
        if (eligibility == null) {
            return true;
        }
        if (eligibility.isRequireTamed() && !TamedStateResolver.isTamed(candidate.ref(), store)) {
            return false;
        }
        if (eligibility.isRequireAdult() && !CompanionLifeStageService.isAdult(candidate.ref(), store, candidate.roleId())) {
            return false;
        }
        String currentState = resolveCurrentStateName(candidate.npc().getRole());
        return !BreedingEligibilityService.isBlockedByState(
                currentState,
                eligibility.isRequireNotSleeping(),
                eligibility.isRequireNotInCombat()
        );
    }

    private boolean isOvercrowded(
            @Nonnull PassiveBreedingSweepCandidate candidate,
            @Nonnull Store<EntityStore> store,
            @Nonnull List<PassiveBreedingBirthReservation> reservations
    ) {
        TwBreedingConfig.PairingSettings pairing =
                candidate.config().resolvePairing(candidate.roleId());
        if (pairing == null) {
            return false;
        }
        int maxNearby = pairing.resolveMaxNearbySameType(candidate.roleId());
        String typeKey = populationTypeService.resolveTypeKey(
                candidate.roleId(), candidate.config()
        );
        if (maxNearby <= 0 || typeKey == null || typeKey.isBlank()) {
            return false;
        }
        double radius = sanitizeRadius(pairing.getBreedRadius());
        int existing = populationTypeService.countNearbyOfType(
                store, candidate.position(), radius, candidate.config(), typeKey
        );
        int pending = countNearbyReservations(
                typeKey, candidate.position(), radius, reservations
        );
        return existing + pending >= maxNearby;
    }

    private static int countNearbyReservations(
            @Nonnull String typeKey,
            @Nonnull Vector3d center,
            double radius,
            @Nonnull List<PassiveBreedingBirthReservation> reservations
    ) {
        double radiusSq = radius * radius;
        int count = 0;
        for (PassiveBreedingBirthReservation reservation : reservations) {
            if (reservation == null
                    || !typeKey.equalsIgnoreCase(reservation.typeKey())) {
                continue;
            }
            double distanceSq = new Vector3d(reservation.position())
                    .sub(center)
                    .lengthSquared();
            if (Double.isFinite(distanceSq) && distanceSq <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private static double sanitizeRadius(double radius) {
        return !Double.isFinite(radius) || radius <= 0.0 ? 10.0 : radius;
    }

    @Nullable
    private static String resolveRoleId(@Nullable NPCEntity npc) {
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
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            return null;
        }
        String resolved = plugin.getName(roleIndex);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    @Nullable
    private static String resolveCurrentStateName(@Nullable Role role) {
        if (role == null || role.getStateSupport() == null || role.getStateSupport().getStateHelper() == null) {
            return null;
        }
        StateSupport stateSupport = role.getStateSupport();
        int currentState = stateSupport.getStateIndex();
        if (currentState == StateSupport.NO_STATE) {
            return null;
        }
        String stateName = stateSupport.getStateHelper().getStateName(currentState);
        if (stateName == null || stateName.isBlank()) {
            return null;
        }
        return stateName.toLowerCase(Locale.ROOT);
    }

}

record PassiveBreedingSweepCandidate(Ref<EntityStore> ref,
                                     NPCEntity npc,
                                     String roleId,
                                     Vector3d position,
                                     TwBreedingConfig config) {
}

record PassiveBreedingBirthReservation(String typeKey, Vector3d position) {
}
