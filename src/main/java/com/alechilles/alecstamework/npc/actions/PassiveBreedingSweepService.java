package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public PassiveBreedingSweepService() {
        this.offspringService = new BreedingOffspringService(new BreedingPartnerService());
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

        List<RoleSnapshot> roleSnapshots = new ArrayList<>();
        List<SweepCandidate> sweepCandidates = new ArrayList<>();
        store.forEachChunk(
                Query.and(npcType, transformType, breedingType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) ->
                        collectCandidates(chunk, npcType, transformType, breedingType, store, roleSnapshots, sweepCandidates)
        );
        if (sweepCandidates.isEmpty()) {
            return;
        }

        for (SweepCandidate candidate : sweepCandidates) {
            TameworkBreedingComponent breeding = store.getComponent(candidate.ref(), breedingType);
            if (breeding == null) {
                continue;
            }
            boolean shouldBeReady = resolveShouldBeReady(candidate, breeding, store);
            if (breeding.isReady() != shouldBeReady) {
                breeding.setReady(shouldBeReady);
                store.putComponent(candidate.ref(), breedingType, breeding);
            }
            if (!shouldBeReady || breeding.isCooldownActive(nowMs)) {
                continue;
            }
            if (isOvercrowded(candidate, roleSnapshots)) {
                continue;
            }
            offspringService.tryCompletePairing(candidate.ref(), store, breeding, candidate.config());
        }
    }

    private static void collectCandidates(@Nonnull ArchetypeChunk<EntityStore> chunk,
                                          @Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                          @Nonnull ComponentType<EntityStore, TransformComponent> transformType,
                                          @Nonnull ComponentType<EntityStore, TameworkBreedingComponent> breedingType,
                                          @Nonnull Store<EntityStore> store,
                                          @Nonnull List<RoleSnapshot> roleSnapshots,
                                          @Nonnull List<SweepCandidate> sweepCandidates) {
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

            roleSnapshots.add(new RoleSnapshot(ref, roleId, new Vector3d(transform.getPosition())));
            TwBreedingConfig config = BreedingConfigResolver.resolveConfig(ref, store, breeding);
            if (config == null || !config.isEnabled() || !config.getPassiveBreeding().isEnabled()) {
                continue;
            }
            sweepCandidates.add(new SweepCandidate(ref, npc, roleId, new Vector3d(transform.getPosition()), config));
        }
    }

    private static boolean resolveShouldBeReady(@Nonnull SweepCandidate candidate,
                                                @Nonnull TameworkBreedingComponent breeding,
                                                @Nonnull Store<EntityStore> store) {
        return passesHappinessThreshold(candidate, breeding, store)
                && passesEligibilityGates(candidate, store);
    }

    private static boolean passesHappinessThreshold(@Nonnull SweepCandidate candidate,
                                                    @Nonnull TameworkBreedingComponent breeding,
                                                    @Nonnull Store<EntityStore> store) {
        double happiness = CompanionHappinessService.resolveCurrentValue(candidate.ref(), store, breeding.getHappiness());
        double threshold = BreedingEligibilityService.resolveThreshold(null, candidate.config().getHappiness().getThreshold());
        double effectiveHappiness = BreedingEligibilityService.resolveEffectiveHappiness(happiness, 1.0, null);
        return BreedingEligibilityService.isEligible(effectiveHappiness, threshold);
    }

    private static boolean passesEligibilityGates(@Nonnull SweepCandidate candidate,
                                                  @Nonnull Store<EntityStore> store) {
        TwBreedingConfig.EligibilitySettings eligibility = candidate.config().getEligibility();
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

    private static boolean isOvercrowded(@Nonnull SweepCandidate candidate,
                                         @Nonnull List<RoleSnapshot> snapshots) {
        TwBreedingConfig.PairingSettings pairing = candidate.config().getPairing();
        if (pairing == null) {
            return false;
        }
        int maxNearbySameType = Math.max(0, pairing.getMaxNearbySameType());
        if (maxNearbySameType <= 0) {
            return false;
        }
        double radius = sanitizeRadius(pairing.getBreedRadius());
        double radiusSq = radius * radius;
        int nearbyCount = 0;
        int sourceIndex = candidate.ref().getIndex();
        for (RoleSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.ref() == null || !snapshot.ref().isValid()) {
                continue;
            }
            if (snapshot.ref().getIndex() == sourceIndex) {
                continue;
            }
            if (!equalsIgnoreCase(candidate.roleId(), snapshot.roleId())) {
                continue;
            }
            double distanceSq = new Vector3d(snapshot.position()).subtract(candidate.position()).squaredLength();
            if (!Double.isFinite(distanceSq) || distanceSq > radiusSq) {
                continue;
            }
            nearbyCount++;
            if (nearbyCount >= maxNearbySameType) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsIgnoreCase(@Nullable String left, @Nullable String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private static double sanitizeRadius(double radius) {
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return 10.0;
        }
        return radius;
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

    private record RoleSnapshot(Ref<EntityStore> ref, String roleId, Vector3d position) {
    }

    private record SweepCandidate(Ref<EntityStore> ref,
                                  NPCEntity npc,
                                  String roleId,
                                  Vector3d position,
                                  TwBreedingConfig config) {
    }
}
