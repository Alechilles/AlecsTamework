package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.internal.ManagedBatchAdmissionAuthority;
import com.alechilles.alecstamework.companion.population.domain.ManagedBatchSettlement;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Reconciles planned children on the world thread, then settles one litter. */
final class BreedingLitterWorldExecutor {
    private final BreedingOffspringSpawnService spawns =
            new BreedingOffspringSpawnService(
                    new BreedingOffspringRoleResolver()
            );
    private final BreedingClaimLimitPolicyService claimPolicy =
            new BreedingClaimLimitPolicyService();
    private final BreedingPopulationTypeService populationTypes =
            new BreedingPopulationTypeService();
    private final BreedingNearbyPopulationAllowance nearby =
            new BreedingNearbyPopulationAllowance();
    private final BreedingCooldownService cooldowns =
            new BreedingCooldownService();
    private final BreedingPairingEffectsService effects =
            new BreedingPairingEffectsService(
                    new BreedingParticleOffsetResolver()
            );
    private final BreedingOffspringPostSpawnService postSpawn =
            new BreedingOffspringPostSpawnService(
                    new BreedingOffspringProgressionService(),
                    cooldowns,
                    effects
            );
    private final BreedingSpawnCompletionGuard completion =
            new BreedingSpawnCompletionGuard();

    @Nonnull
    CompletionStage<BreedingLitterLiveResult> execute(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull BreedingLitterOperation litter,
            @Nonnull OperationEnvelope operation,
            @Nonnull ManagedBatchAdmissionAuthority admissions
    ) {
        try {
            store.assertThread();
            Map<Integer, UUID> receipts = findExisting(
                    world, store, litter
            );
            if (receipts.size() < litter.requestedCount()) {
                PopulationAdmissionDecision claim =
                        admissions.claimManagedBatch(
                                litter.admissionToken()
                        );
                if (!claim.accepted() && operation.attemptCount() == 0) {
                    return BreedingLitterLiveResult.retryable(
                            "breeding_litter_claim_unavailable", null
                    ).completed();
                }
                spawnMissing(world, store, litter, receipts);
            }
            return settle(litter, admissions, receipts)
                    .thenApply(result -> {
                        if (result.status()
                                == ManagedBatchSettlement.Status.UNAVAILABLE) {
                            return BreedingLitterLiveResult.retryable(
                                    result.reason(), null
                            );
                        }
                        scheduleCompanionXp(
                                litter.worldName(),
                                litter.parentA().uuid(),
                                litter.parentB().uuid(),
                                !receipts.isEmpty()
                        );
                        return BreedingLitterLiveResult.confirmed(
                                result.reason(), result.actualChildIds()
                        );
                    });
        } catch (RuntimeException | LinkageError failure) {
            return BreedingLitterLiveResult.retryable(
                    "breeding_litter_world_failed", failure
            ).completed();
        }
    }

    private Map<Integer, UUID> findExisting(
            World world,
            Store<EntityStore> store,
            BreedingLitterOperation litter
    ) {
        LinkedHashMap<Integer, UUID> receipts = new LinkedHashMap<>();
        for (int ordinal = 0; ordinal < litter.children().size(); ordinal++) {
            BreedingLitterOperation.ChildPlan child =
                    litter.children().get(ordinal);
            Ref<EntityStore> ref = world.getEntityRef(child.uuid());
            if (ref == null || !ref.isValid()) {
                continue;
            }
            NPCEntity npc = store.getComponent(
                    ref, NPCEntity.getComponentType()
            );
            ComponentType<EntityStore, UUIDComponent> uuidType =
                    UUIDComponent.getComponentType();
            UUIDComponent uuid = uuidType == null
                    ? null : store.getComponent(ref, uuidType);
            if (npc == null || uuid == null
                    || !child.uuid().equals(uuid.getUuid())
                    || !roleMatches(npc, child.roleId())) {
                throw new IllegalStateException(
                        "breeding_litter_child_identity_conflict"
                );
            }
            receipts.put(ordinal, child.uuid());
        }
        return receipts;
    }

    private void spawnMissing(
            World world,
            Store<EntityStore> store,
            BreedingLitterOperation litter,
            Map<Integer, UUID> receipts
    ) {
        ParentRefs parents = parents(world, store, litter);
        if (parents == null) {
            throw new IllegalStateException(
                    "breeding_litter_parents_unavailable"
            );
        }
        TwBreedingConfig config = config(litter.breedingConfigId());
        if (litter.breedingConfigId() != null && config == null) {
            throw new IllegalStateException(
                    "breeding_litter_config_unavailable"
            );
        }
        int allowed = allowedCount(store, litter, config);
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null) {
            throw new IllegalStateException(
                    "breeding_litter_npc_plugin_unavailable"
            );
        }
        for (int ordinal = 0; ordinal < allowed; ordinal++) {
            if (receipts.containsKey(ordinal)) {
                continue;
            }
            BreedingLitterOperation.ChildPlan plan =
                    litter.children().get(ordinal);
            int roleIndex = plugin.getIndex(plan.roleId());
            if (roleIndex < 0) {
                continue;
            }
            Pair<Ref<EntityStore>, NPCEntity> spawned =
                    spawns.spawnWithFallback(
                            plugin,
                            store,
                            roleIndex,
                            offset(litter, ordinal),
                            rotation(litter),
                            plan.uuid()
                    );
            if (spawned != null && finishChild(
                    store, parents, litter, config, plan, spawned
            )) {
                receipts.put(ordinal, plan.uuid());
            }
        }
    }

    private int allowedCount(
            Store<EntityStore> store,
            BreedingLitterOperation litter,
            @Nullable TwBreedingConfig config
    ) {
        BreedingLitterOperation.ChildPlan first = litter.children().getFirst();
        BreedingClaimLimitPolicyService.Decision claim = claimPolicy.evaluate(
                store,
                position(litter),
                config,
                first.roleId(),
                litter.parentA().ownerId(),
                litter.parentB().ownerId()
        );
        if (!claim.allowed()) {
            return 0;
        }
        int allowed = claim.capEnforced()
                ? Math.min(
                        litter.requestedCount(),
                        claim.remainingHeadroom()
                ) : litter.requestedCount();
        if (config == null) {
            return Math.max(0, allowed);
        }
        TwBreedingConfig.PairingSettings pairing =
                config.resolvePairing(litter.parentA().roleId());
        if (pairing == null) {
            return Math.max(0, allowed);
        }
        int max = pairing.resolveMaxNearbySameType(
                litter.parentA().roleId()
        );
        String type = populationTypes.resolveTypeKey(
                first.roleId(), config
        );
        if (max <= 0 || type == null || type.isBlank()) {
            return Math.max(0, allowed);
        }
        double radius = pairing.getBreedRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            radius = 10.0;
        }
        int existing = populationTypes.countNearbyOfType(
                store, position(litter), radius, config, type
        );
        return nearby.limit(allowed, existing, max);
    }

    private boolean finishChild(
            Store<EntityStore> store,
            ParentRefs parents,
            BreedingLitterOperation litter,
            @Nullable TwBreedingConfig config,
            BreedingLitterOperation.ChildPlan plan,
            Pair<Ref<EntityStore>, NPCEntity> child
    ) {
        TwBreedingConfig.Gender gender =
                TwBreedingConfig.Gender.fromConfigValue(plan.gender());
        TwBreedingConfig.RoleFamily family = family(config, plan);
        return completion.complete(
                () -> postSpawn.finish(
                        new BreedingOffspringPostSpawnService.Request(
                                child.first(),
                                child.second(),
                                parents.parentA(),
                                parents.parentB(),
                                store,
                                config,
                                plan.roleId(),
                                plan.uuid(),
                                litter.breedingConfigId(),
                                owner(litter.parentA()),
                                owner(litter.parentB()),
                                litter.parentA().tamed(),
                                litter.parentB().tamed(),
                                plan.adultRoleId(),
                                gender,
                                family,
                                CompanionLifeStageService
                                        .LifecycleFamilyResolution
                                        .PLANNED_SELECTION_ONLY,
                                ignored -> { },
                                ignored -> { }
                        )
                ),
                failure -> child.second().setToDespawn()
        );
    }

    private CompletionStage<ManagedBatchSettlement> settle(
            BreedingLitterOperation litter,
            ManagedBatchAdmissionAuthority admissions,
            Map<Integer, UUID> receipts
    ) {
        try {
            CompletionStage<ManagedBatchSettlement> stage =
                    admissions.settleManagedBatch(
                            litter.admissionToken(),
                            receipts.keySet(),
                            receipts
                    );
            return stage == null
                    ? CompletableFuture.completedFuture(unavailable(
                            litter, "breeding_litter_settlement_missing"
                    ))
                    : stage.exceptionally(failure -> unavailable(
                            litter, "breeding_litter_settlement_failed"
                    ));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(unavailable(
                    litter, "breeding_litter_settlement_failed"
            ));
        }
    }

    private static ManagedBatchSettlement unavailable(
            BreedingLitterOperation litter,
            String reason
    ) {
        return new ManagedBatchSettlement(
                ManagedBatchSettlement.Status.UNAVAILABLE,
                reason,
                litter.requestedCount(),
                java.util.Set.of(),
                Map.of()
        );
    }

    @Nullable
    private static ParentRefs parents(
            World world,
            Store<EntityStore> store,
            BreedingLitterOperation litter
    ) {
        Ref<EntityStore> a = world.getEntityRef(litter.parentA().uuid());
        Ref<EntityStore> b = world.getEntityRef(litter.parentB().uuid());
        return live(a, store) && live(b, store)
                ? new ParentRefs(a, b) : null;
    }

    private static boolean live(
            @Nullable Ref<EntityStore> ref,
            Store<EntityStore> store
    ) {
        return ref != null && ref.isValid()
                && store.getComponent(
                        ref, NPCEntity.getComponentType()
                ) != null;
    }

    private static boolean roleMatches(NPCEntity npc, String roleId) {
        String actual = npc.getRoleName();
        if (actual == null || actual.isBlank()) {
            NPCPlugin plugin = NPCPlugin.get();
            actual = plugin == null
                    ? null : plugin.getName(npc.getRoleIndex());
        }
        return actual != null && actual.equalsIgnoreCase(roleId);
    }

    @Nullable
    private static TwBreedingConfig config(@Nullable String id) {
        return id == null ? null : TwBreedingConfig.resolveById(id);
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily family(
            @Nullable TwBreedingConfig config,
            BreedingLitterOperation.ChildPlan plan
    ) {
        if (config == null || plan.lifecycleFamilyId() == null) {
            return null;
        }
        TwBreedingConfig.RoleFamily family =
                config.resolveLifecycleFamilyForRole(plan.roleId());
        if (family == null || family.getId() == null
                || !family.getId().equalsIgnoreCase(
                        plan.lifecycleFamilyId()
                )) {
            family = config.resolveLifecycleFamilyForRole(
                    plan.adultRoleId()
            );
        }
        if (family == null || plan.lifecycleLineId() == null) {
            return family;
        }
        for (TwBreedingConfig.RoleLine line : family.getLines()) {
            if (line != null && line.getId() != null
                    && line.getId().equalsIgnoreCase(
                            plan.lifecycleLineId()
                    )) {
                return family.copyForLine(line);
            }
        }
        return family;
    }

    private static BreedingOffspringProgressionService.OwnerSnapshot owner(
            BreedingLitterOperation.Parent parent
    ) {
        return new BreedingOffspringProgressionService.OwnerSnapshot(
                parent.ownerId(), parent.ownerName()
        );
    }

    private static Vector3d position(BreedingLitterOperation litter) {
        return new Vector3d(
                litter.spawnX(), litter.spawnY(), litter.spawnZ()
        );
    }

    private static Vector3d offset(
            BreedingLitterOperation litter,
            int ordinal
    ) {
        double angle = ordinal * (Math.PI * 2.0 / Math.max(
                1, litter.requestedCount()
        ));
        return position(litter).add(
                Math.cos(angle) * 0.35,
                0.0,
                Math.sin(angle) * 0.35
        );
    }

    private static Rotation3f rotation(BreedingLitterOperation litter) {
        return new Rotation3f(
                litter.spawnYaw(),
                litter.spawnPitch(),
                litter.spawnRoll()
        );
    }

    private static void scheduleCompanionXp(
            String worldName,
            UUID parentA,
            UUID parentB,
            boolean hasChildren
    ) {
        if (!hasChildren) {
            return;
        }
        World world = Universe.get().getWorld(worldName);
        if (world == null) {
            return;
        }
        world.execute(() -> {
            World current = Universe.get().getWorld(worldName);
            if (current == null || current != world
                    || current.getEntityStore() == null) {
                return;
            }
            Store<EntityStore> store = current.getEntityStore().getStore();
            Ref<EntityStore> a = current.getEntityRef(parentA);
            Ref<EntityStore> b = current.getEntityRef(parentB);
            if (live(a, store)) {
                CompanionLevelingService.awardBreedingXp(a, store);
            }
            if (live(b, store)) {
                CompanionLevelingService.awardBreedingXp(b, store);
            }
        });
    }

    private record ParentRefs(
            Ref<EntityStore> parentA,
            Ref<EntityStore> parentB
    ) {
    }
}
