package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthAnchor;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan;
import com.alechilles.alecstamework.npc.breeding.BreedingCapacityHeadroom;
import com.alechilles.alecstamework.npc.breeding.BreedingClaimCapacityScope;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingPlayerCapacityScope;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.BreedingReservationScope;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.actions.BreedingOffspringProgressionService.OwnerSnapshot;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Builds exact nearby, claim, and player capacity input for one immutable litter plan. */
final class BreedingCapacityRequestFactory {
    private final BreedingPopulationTypeService populationTypeService;
    private final BreedingClaimLimitPolicyService claimPolicyService;

    BreedingCapacityRequestFactory() {
        this(new BreedingPopulationTypeService(), new BreedingClaimLimitPolicyService());
    }

    BreedingCapacityRequestFactory(BreedingPopulationTypeService populationTypeService,
                                   BreedingClaimLimitPolicyService claimPolicyService) {
        this.populationTypeService = populationTypeService;
        this.claimPolicyService = claimPolicyService;
    }

    @Nonnull
    BreedingPairingCoordinator.CapacityDecision prepare(
            @Nonnull UUID jobId,
            @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
            @Nonnull BreedingBirthPlan plan,
            @Nonnull BreedingBirthAnchor anchor,
            @Nonnull Store<EntityStore> store,
            @Nullable TwBreedingConfig config,
            @Nullable String parentRoleId,
            @Nonnull OwnerSnapshot parentAOwner,
            @Nonnull OwnerSnapshot parentBOwner,
            @Nullable BreedingReservationScope expectedScope) {
        String worldId = resolveWorldId(store);
        if (worldId == null) {
            return BreedingPairingCoordinator.CapacityDecision.reject("missing-world");
        }
        if (plan.isNaturallyEmpty()) {
            return naturalZero(jobId, worldId, mode, plan, anchor, expectedScope);
        }
        Vector3d center = new Vector3d(anchor.x(), anchor.y(), anchor.z());
        String inheritanceRoleId = plan.children().isEmpty()
                ? parentRoleId
                : plan.children().getFirst().roleId();
        BreedingClaimLimitPolicyService.Decision claimDecision = claimPolicyService.evaluate(
                store,
                center,
                config,
                inheritanceRoleId,
                parentAOwner.ownerId(),
                parentBOwner.ownerId(),
                null,
                null
        );
        if (!claimDecision.allowed()) {
            return BreedingPairingCoordinator.CapacityDecision.reject(claimDecision.reason());
        }
        NearbyBoundary nearby = nearbyBoundary(config, parentRoleId);
        BreedingClaimCapacityScope claimScope = claimScope(claimDecision.claimReservationKey());
        List<BreedingPlayerCapacityScope> playerScopes = playerScopes(claimDecision.playerReservationKeys());
        BreedingReservationScope reservationScope = new BreedingReservationScope(
                nearby.radius(),
                claimScope,
                playerScopes
        );
        if (expectedScope != null && !expectedScope.equals(reservationScope)) {
            return BreedingPairingCoordinator.CapacityDecision.reject("capacity-scope-changed");
        }
        Map<String, Integer> liveNearby = liveNearbyCounts(
                plan,
                store,
                center,
                nearby,
                config
        );
        BreedingCapacityHeadroom headroom = headroom(claimDecision, claimScope, playerScopes);
        return BreedingPairingCoordinator.CapacityDecision.allow(
                new BreedingPopulationAdmissionService.AdmissionRequest(
                        jobId,
                        worldId,
                        mode,
                        plan,
                        anchor,
                        reservationScope,
                        nearby.maxNearby(),
                        liveNearby,
                        headroom
                )
        );
    }

    private BreedingPairingCoordinator.CapacityDecision naturalZero(
            UUID jobId,
            String worldId,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingBirthPlan plan,
            BreedingBirthAnchor anchor,
            @Nullable BreedingReservationScope expectedScope) {
        BreedingReservationScope scope = BreedingReservationScope.unscoped();
        if (expectedScope != null && !expectedScope.equals(scope)) {
            return BreedingPairingCoordinator.CapacityDecision.reject("capacity-scope-changed");
        }
        return BreedingPairingCoordinator.CapacityDecision.allow(
                new BreedingPopulationAdmissionService.AdmissionRequest(
                        jobId,
                        worldId,
                        mode,
                        plan,
                        anchor,
                        scope,
                        0,
                        Map.of(),
                        BreedingCapacityHeadroom.unlimited()
                )
        );
    }

    private Map<String, Integer> liveNearbyCounts(BreedingBirthPlan plan,
                                                   Store<EntityStore> store,
                                                   Vector3d center,
                                                   NearbyBoundary nearby,
                                                   @Nullable TwBreedingConfig config) {
        if (nearby.maxNearby() <= 0) {
            return Map.of();
        }
        Set<String> types = new LinkedHashSet<>();
        for (PlannedChild child : plan.children()) {
            types.add(child.populationType());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : types) {
            counts.put(type, populationTypeService.countNearbyOfType(
                    store,
                    center,
                    nearby.radius(),
                    config,
                    type
            ));
        }
        return counts;
    }

    private NearbyBoundary nearbyBoundary(@Nullable TwBreedingConfig config,
                                          @Nullable String parentRoleId) {
        TwBreedingConfig.PairingSettings pairing = config != null
                ? config.resolvePairing(parentRoleId)
                : null;
        int maxNearby = pairing != null ? pairing.resolveMaxNearbySameType(parentRoleId) : 0;
        if (maxNearby <= 0) {
            return new NearbyBoundary(0, 0.0);
        }
        double radius = pairing.getBreedRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            radius = 10.0;
        }
        return new NearbyBoundary(maxNearby, radius);
    }

    private BreedingCapacityHeadroom headroom(
            BreedingClaimLimitPolicyService.Decision decision,
            @Nullable BreedingClaimCapacityScope claimScope,
            List<BreedingPlayerCapacityScope> playerScopes) {
        if (!decision.capEnforced()) {
            return BreedingCapacityHeadroom.unlimited();
        }
        int remaining = Math.max(0, decision.remainingHeadroom());
        OptionalInt claimHeadroom = claimScope != null ? OptionalInt.of(remaining) : OptionalInt.empty();
        Map<BreedingPlayerCapacityScope, Integer> playerHeadroom = new LinkedHashMap<>();
        for (BreedingPlayerCapacityScope playerScope : playerScopes) {
            playerHeadroom.put(playerScope, remaining);
        }
        return new BreedingCapacityHeadroom(claimHeadroom, playerHeadroom);
    }

    @Nullable
    private BreedingClaimCapacityScope claimScope(
            @Nullable BreedingClaimLimitPolicyService.ClaimReservationKey key) {
        if (key == null || key.providerId() == null || key.providerId().isBlank()
                || key.worldName() == null || key.worldName().isBlank()) {
            return null;
        }
        String claimId = firstNonBlank(key.claimId(), key.ownerType() + ":" + key.ownerId());
        return new BreedingClaimCapacityScope(key.providerId(), key.worldName(), claimId);
    }

    private List<BreedingPlayerCapacityScope> playerScopes(
            List<BreedingClaimLimitPolicyService.PlayerReservationKey> keys) {
        ArrayList<BreedingPlayerCapacityScope> scopes = new ArrayList<>();
        for (BreedingClaimLimitPolicyService.PlayerReservationKey key : keys) {
            if (key == null || key.ownerId() == null) {
                continue;
            }
            scopes.add(key.scope() == TwGlobalConfig.PerPlayerLimitScope.GLOBAL
                    ? BreedingPlayerCapacityScope.global(key.ownerId())
                    : BreedingPlayerCapacityScope.perWorld(key.worldName(), key.ownerId()));
        }
        return List.copyOf(scopes);
    }

    @Nullable
    private String resolveWorldId(Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        return world == null || world.getName() == null || world.getName().isBlank()
                ? null
                : world.getName();
    }

    private static String firstNonBlank(@Nullable String first, @Nonnull String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }

    private record NearbyBoundary(int maxNearby, double radius) {
    }
}
