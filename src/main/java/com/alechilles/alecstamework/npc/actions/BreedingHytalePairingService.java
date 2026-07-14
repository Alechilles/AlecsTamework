package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.AppliedCooldownFingerprint;
import com.alechilles.alecstamework.npc.breeding.BreedingPairingCoordinator;
import com.alechilles.alecstamework.npc.breeding.BreedingPopulationAdmissionService;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Gathers live Hytale parent state and submits one guarded pairing admission transaction. */
final class BreedingHytalePairingService {
    private final BreedingPartnerService partnerService;
    private final BreedingPairingCoordinator coordinator;
    private final BreedingFertilityOffspringService fertilityService =
            new BreedingFertilityOffspringService();
    private final BreedingOffspringRoleResolver roleResolver =
            new BreedingOffspringRoleResolver();
    private final BreedingPopulationTypeService populationTypeService =
            new BreedingPopulationTypeService();
    private final BreedingCapacityRequestFactory capacityFactory =
            new BreedingCapacityRequestFactory();
    private final BreedingPairEffectsService pairEffectsService =
            new BreedingPairEffectsService();
    private final BreedingJobPlanSnapshotMapper planSnapshotMapper;
    private final BreedingParentPreparationService parentPreparation;
    private final BreedingPairingAttemptSelector attemptSelector;
    private final BreedingPairingPopulationPreparationService populationPreparation;

    BreedingHytalePairingService(
            @Nonnull BreedingPartnerService partnerService,
            @Nonnull BreedingPairingCoordinator coordinator) {
        this(partnerService, coordinator, TameworkBreedingServices.shared());
    }

    BreedingHytalePairingService(
            @Nonnull BreedingPartnerService partnerService,
            @Nonnull BreedingPairingCoordinator coordinator,
            @Nonnull TameworkBreedingServices services) {
        this(
                partnerService,
                coordinator,
                services,
                new BreedingParentPreparationService(),
                new BreedingPairingAttemptSelector(),
                new BreedingJobPlanSnapshotMapper()
        );
    }

    BreedingHytalePairingService(
            @Nonnull BreedingPartnerService partnerService,
            @Nonnull BreedingPairingCoordinator coordinator,
            @Nonnull TameworkBreedingServices services,
            @Nonnull BreedingParentPreparationService parentPreparation,
            @Nonnull BreedingPairingAttemptSelector attemptSelector,
            @Nonnull BreedingJobPlanSnapshotMapper planSnapshotMapper) {
        this.partnerService = Objects.requireNonNull(partnerService, "partnerService");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.parentPreparation = Objects.requireNonNull(
                parentPreparation, "parentPreparation"
        );
        this.attemptSelector = Objects.requireNonNull(attemptSelector, "attemptSelector");
        this.planSnapshotMapper = Objects.requireNonNull(
                planSnapshotMapper, "planSnapshotMapper"
        );
        TameworkBreedingServices requiredServices = Objects.requireNonNull(
                services, "services"
        );
        BreedingPreparedPairingHandoffService handoff =
                new BreedingPreparedPairingHandoffService(
                        requiredServices,
                        this::logWarning,
                        this::logInfo
                );
        this.populationPreparation = new BreedingPairingPopulationPreparationService(
                coordinator,
                requiredServices,
                parentPreparation,
                planSnapshotMapper,
                handoff,
                this::logWarning
        );
    }

    boolean tryPassive(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                BreedingReadinessPolicy.passive(now),
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE,
                null
        );
    }

    boolean tryPassive(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull BreedingPopulationSweepContext populationContext) {
        long now = store != null ? BreedingTimeService.resolveCurrentTimeMs(store) : 0L;
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                BreedingReadinessPolicy.passive(now),
                BreedingPopulationAdmissionService.BreedingMode.PASSIVE,
                populationContext
        );
    }

    boolean tryManual(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull UUID playerUuid) {
        return tryPair(
                sourceRef,
                store,
                sourceBreeding,
                config,
                BreedingReadinessPolicy.manual(playerUuid, ManualBreedingClock.nowMs()),
                BreedingPopulationAdmissionService.BreedingMode.MANUAL,
                null
        );
    }

    private boolean tryPair(
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> store,
            @Nullable TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            @Nonnull BreedingReadinessPolicy readinessPolicy,
            @Nonnull BreedingPopulationAdmissionService.BreedingMode mode,
            @Nullable BreedingPopulationSweepContext populationContext) {
        if (sourceRef == null || !sourceRef.isValid() || store == null || sourceBreeding == null) {
            return false;
        }
        BreedingPreparedParents prepared = prepareParents(
                sourceRef, store, sourceBreeding, config, readinessPolicy
        );
        if (prepared == null) {
            return false;
        }
        com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService
                populationService = resolvePopulationService();
        if (populationService == null) {
            logWarning("Breeding pairing blocked because shared population authority is unavailable.");
            return false;
        }
        BreedingPairingAttemptSelector.Selection selection;
        try {
            selection = attemptSelector.selectDetailed(prepared, populationService);
        } catch (RuntimeException | LinkageError failure) {
            logWarning("Breeding pairing blocked because durable pair replay lookup failed: "
                    + replayContext(prepared)
                    + " reason=" + failure.getClass().getSimpleName() + ".");
            return false;
        }
        BreedingPairingAttempt attempt = selection.attempt();
        if (attempt == null) {
            logWarning("Breeding pairing blocked by restart replay safety: "
                    + replayContext(prepared) + " reason=" + selection.reason() + ".");
            return false;
        }
        BreedingPairingCoordinator.PairingRequest request = request(
                prepared, store, config, mode, attempt
        );
        BreedingPairingCoordinator.PairingResult reserved = coordinator.reserve(request);
        if (!reserved.reserved() || reserved.job().isEmpty()) {
            logInfo("Breeding pairing admission rejected: status=" + reserved.status()
                    + " reason=" + reserved.reason());
            return false;
        }
        return populationPreparation.begin(
                prepared,
                request,
                reserved.job().orElseThrow(),
                attempt,
                populationService,
                populationContext,
                config,
                store
        );
    }

    @Nonnull
    private static String replayContext(@Nonnull BreedingPreparedParents parents) {
        return "source=" + parents.sourceIdentity().entityUuid()
                + " sourceProfile=" + parents.sourceIdentity().profileId()
                + " partner=" + parents.partnerIdentity().entityUuid()
                + " partnerProfile=" + parents.partnerIdentity().profileId()
                + " world=" + parents.worldId();
    }

    @Nullable
    private BreedingPreparedParents prepareParents(
            Ref<EntityStore> sourceRef,
            Store<EntityStore> store,
            TameworkBreedingComponent sourceBreeding,
            @Nullable TwBreedingConfig config,
            BreedingReadinessPolicy readinessPolicy) {
        BreedingPartnerService.PartnerCandidate partner = partnerService.findNearestPartner(
                sourceRef,
                store,
                sourceBreeding,
                config,
                readinessPolicy
        );
        if (partner == null || partner.ref == null || !partner.ref.isValid()) {
            return null;
        }
        NPCEntity sourceNpc = store.getComponent(sourceRef, NPCEntity.getComponentType());
        NPCEntity partnerNpc = store.getComponent(partner.ref, NPCEntity.getComponentType());
        TameworkBreedingComponent partnerBreeding = parentPreparation.breedingComponent(
                partner.ref, store
        );
        if (sourceNpc == null || sourceNpc.getUuid() == null
                || partnerNpc == null || partnerNpc.getUuid() == null
                || !BreedingOffspringService.acceptsPartnerReadiness(
                        readinessPolicy, partnerBreeding
                )) {
            return null;
        }
        try {
            BreedingPreparedParents prepared = parentPreparation.prepare(
                    sourceRef,
                    sourceNpc,
                    sourceBreeding,
                    partner.ref,
                    partnerNpc,
                    partnerBreeding,
                    store,
                    config
            );
            if (prepared == null) {
                logInfo("Breeding pairing did not reach admission: source="
                        + sourceNpc.getUuid()
                        + " partner="
                        + partnerNpc.getUuid()
                        + " reason="
                        + parentPreparation.preparationIssue(
                                sourceRef,
                                sourceNpc,
                                sourceBreeding,
                                partner.ref,
                                partnerNpc,
                                partnerBreeding,
                                store
                        ));
            }
            return prepared;
        } catch (RuntimeException | LinkageError failure) {
            logWarning("Breeding pairing blocked because canonical parent identity conflicted.");
            return null;
        }
    }

    private BreedingPairingCoordinator.PairingRequest request(
            BreedingPreparedParents prepared,
            Store<EntityStore> store,
            @Nullable TwBreedingConfig config,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingPairingAttempt attempt) {
        BreedingFertilityOffspringService.FertilityMultipliers fertility =
                fertilityService.resolveMultipliers(
                        prepared.sourceRef(), prepared.partnerRef(), store
                );
        AppliedCooldownFingerprint sourceFingerprint = attempt.replay()
                ? parentPreparation.persistedFingerprint(prepared.sourceSnapshot())
                : prepared.sourceFingerprint();
        AppliedCooldownFingerprint partnerFingerprint = attempt.replay()
                ? parentPreparation.persistedFingerprint(prepared.partnerSnapshot())
                : prepared.partnerFingerprint();
        return new BreedingPairingCoordinator.PairingRequest(
                store,
                prepared.worldId(),
                mode,
                prepared.sourceIdentity(),
                prepared.partnerIdentity(),
                fertility.parentAMultiplier(),
                fertility.parentBMultiplier(),
                index -> plannedChild(prepared, config),
                (jobId, plan) -> capacityDecision(
                        prepared, store, config, mode, attempt, jobId, plan
                ),
                prepared.sourceSnapshot(),
                prepared.partnerSnapshot(),
                sourceFingerprint,
                partnerFingerprint,
                prepared.anchor(),
                job -> attempt.replay()
                        ? pairEffectsService.resume(effectContext(prepared, store))
                        : pairEffectsService.apply(effectContext(prepared, store)),
                job -> pairEffectsService.rollback(effectContext(prepared, store), job),
                (jobId, freshPlan) -> resolvePlan(attempt, freshPlan),
                attempt.jobId()
        );
    }

    private BreedingPairingCoordinator.CapacityDecision capacityDecision(
            BreedingPreparedParents prepared,
            Store<EntityStore> store,
            @Nullable TwBreedingConfig config,
            BreedingPopulationAdmissionService.BreedingMode mode,
            BreedingPairingAttempt attempt,
            UUID jobId,
            com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan plan) {
        BreedingPairingCoordinator.CapacityDecision decision = capacityFactory.prepare(
                jobId,
                mode,
                plan,
                prepared.anchor(),
                store,
                config,
                prepared.sourceRoleId(),
                prepared.sourceOwner(),
                prepared.partnerOwner(),
                null
        );
        return decision.allowed()
                ? BreedingPairingCoordinator.CapacityDecision.allow(
                        decision.request(),
                        planSnapshotMapper.outstandingChildren(
                                plan, attempt.replayState()
                        )
                )
                : decision;
    }

    private com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan resolvePlan(
            BreedingPairingAttempt attempt,
            java.util.function.Supplier<
                    com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan> freshPlan) {
        if (attempt.replayState().birthPlan() == null) {
            return freshPlan.get();
        }
        com.alechilles.alecstamework.npc.breeding.BreedingBirthPlan restored =
                planSnapshotMapper.restore(attempt.replayState().birthPlan());
        if (restored == null) {
            throw new IllegalStateException("Durable breeding birth plan cannot be restored");
        }
        return restored;
    }

    @Nullable
    private PlannedChild plannedChild(
            BreedingPreparedParents prepared,
            @Nullable TwBreedingConfig config) {
        NPCPlugin npcPlugin = NPCPlugin.get();
        BreedingOffspringRoleResolver.OffspringRoleSelection role =
                roleResolver.selectOffspringRole(
                        prepared.sourceRoleId(),
                        prepared.partnerRoleId(),
                        config,
                        npcPlugin,
                        Math.random(),
                        Math.random(),
                        Math.random(),
                        Math.random()
                );
        if (role == null || npcPlugin == null || npcPlugin.getIndex(role.roleId()) < 0) {
            return null;
        }
        String populationType = populationTypeService.resolveTypeKey(role.roleId(), config);
        if (populationType == null) {
            return null;
        }
        return new PlannedChild(
                role.roleId(),
                role.adultRoleId(),
                role.gender() != null ? role.gender().toConfigValue() : null,
                lifecycleKey(role.lifecycleFamily()),
                populationType
        );
    }

    private BreedingPairEffectsService.EffectContext effectContext(
            BreedingPreparedParents prepared,
            Store<EntityStore> store) {
        return new BreedingPairEffectsService.EffectContext(
                prepared.sourceRef(),
                prepared.sourceNpc(),
                prepared.sourceBreeding(),
                prepared.partnerRef(),
                prepared.partnerNpc(),
                prepared.partnerBreeding(),
                prepared.sourceCooldown(),
                prepared.partnerCooldown(),
                prepared.sourceOwner(),
                prepared.partnerOwner(),
                prepared.nowMs(),
                prepared.happinessUpdatedAtMs(),
                store,
                null
        );
    }

    @Nullable
    private String lifecycleKey(@Nullable TwBreedingConfig.RoleFamily family) {
        if (family == null) {
            return null;
        }
        String id = family.getId();
        String line = family.getSelectedLineId();
        if (id == null || id.isBlank()) {
            return line;
        }
        return line == null || line.isBlank() ? id : id + ":" + line;
    }

    @Nullable
    private com.alechilles.alecstamework.ownership.BreedingPopulationAdmissionService
            resolvePopulationService() {
        Tamework plugin = Tamework.getInstance();
        OwnerPopulationRuntime runtime = plugin == null
                ? null
                : plugin.getOwnerPopulationRuntime();
        return runtime == null ? null : runtime.breedingAdmissionService();
    }

    private void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null && plugin.isDebugBreedingEnabled()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

    private void logWarning(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null) {
            plugin.getLogger().at(Level.WARNING).log(message);
        }
    }
}
