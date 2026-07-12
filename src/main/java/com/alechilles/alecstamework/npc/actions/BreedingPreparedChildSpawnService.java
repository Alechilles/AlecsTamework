package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.breeding.BreedingAttemptIdentity;
import com.alechilles.alecstamework.npc.breeding.BreedingBirthJob;
import com.alechilles.alecstamework.npc.breeding.BreedingJobExecutionService;
import com.alechilles.alecstamework.npc.breeding.BreedingPreparedPopulationRegistry;
import com.alechilles.alecstamework.npc.breeding.PlannedChild;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.ownership.BreedingChildProjectionMarker;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.PreparedBreedingPopulationBatch;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Materializes one journal-reserved child and owns the pre-add versus ambiguous
 * post-add terminality boundary.
 */
final class BreedingPreparedChildSpawnService {
    private final BreedingPreparedPopulationRegistry preparedPopulation;
    private final BreedingOffspringSpawnService spawnService;
    private final BreedingOffspringPostSpawnService postSpawnService;
    private final BreedingSpawnCompletionGuard completionGuard;

    BreedingPreparedChildSpawnService(
            BreedingPreparedPopulationRegistry preparedPopulation,
            BreedingOffspringSpawnService spawnService,
            BreedingOffspringPostSpawnService postSpawnService) {
        this.preparedPopulation = preparedPopulation;
        this.spawnService = spawnService;
        this.postSpawnService = postSpawnService;
        this.completionGuard = new BreedingSpawnCompletionGuard();
    }

    @Nonnull
    BreedingJobExecutionService.ChildSpawnResult spawn(
            @Nonnull BreedingBirthJob job,
            @Nonnull PlannedChild child,
            int childIndex,
            @Nonnull BreedingHytaleJobRuntime.Context context) {
        int unitIndex = preparedPopulation.unitIndexForActiveOrdinal(job.jobId(), childIndex);
        PreparedBreedingPopulationBatch.ReservedChild reserved = unitIndex < 0
                ? null
                : preparedPopulation.child(job.jobId(), unitIndex).orElse(null);
        if (reserved == null) {
            return BreedingJobExecutionService.ChildSpawnResult.FAILED;
        }
        return spawnReserved(job, child, childIndex, unitIndex, reserved, context);
    }

    private BreedingJobExecutionService.ChildSpawnResult spawnReserved(
            BreedingBirthJob job,
            PlannedChild child,
            int childIndex,
            int unitIndex,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            BreedingHytaleJobRuntime.Context context) {
        boolean claimed = false;
        boolean spawnStarted = false;
        BreedingPreparedSpawnResult spawn = null;
        try {
            claimed = preparedPopulation.claimForSpawn(job.jobId(), unitIndex);
            if (!claimed) {
                return BreedingJobExecutionService.ChildSpawnResult.FAILED;
            }
            RoleResolution role = resolveRole(child);
            if (!role.valid()) {
                preparedPopulation.cancelUnit(job.jobId(), unitIndex, "breeding-child-role-invalid");
                return BreedingJobExecutionService.ChildSpawnResult.FAILED;
            }
            spawnStarted = true;
            spawn = spawnPrepared(job, childIndex, unitIndex, reserved, context, role);
            if (!markMaterialized(job, unitIndex, spawn)) {
                return BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
            }
            return finishPreparedSpawn(job, child, unitIndex, reserved, context, spawn);
        } catch (RuntimeException | LinkageError failure) {
            return terminalizeSpawnException(job, unitIndex, claimed, spawnStarted, spawn);
        }
    }

    private RoleResolution resolveRole(PlannedChild child) {
        NPCPlugin plugin = NPCPlugin.get();
        int roleIndex = plugin != null ? plugin.getIndex(child.roleId()) : -1;
        return new RoleResolution(plugin, roleIndex);
    }

    private boolean markMaterialized(
            BreedingBirthJob job,
            int unitIndex,
            BreedingPreparedSpawnResult spawn) {
        if (spawn.spawned() == null
                || preparedPopulation.markMaterialized(job.jobId(), unitIndex)) {
            return true;
        }
        preparedPopulation.retainAmbiguous(
                job.jobId(), unitIndex, "breeding-materialized-state-conflict"
        );
        return false;
    }

    private BreedingJobExecutionService.ChildSpawnResult terminalizeSpawnException(
            BreedingBirthJob job,
            int unitIndex,
            boolean claimed,
            boolean spawnStarted,
            @Nullable BreedingPreparedSpawnResult spawn) {
        if (!claimed) {
            return BreedingJobExecutionService.ChildSpawnResult.FAILED;
        }
        boolean absenceProven = spawnStarted
                && spawn != null
                && spawn.spawned() == null
                && !spawn.outcomeAmbiguous();
        if (!spawnStarted || absenceProven) {
            preparedPopulation.cancelUnit(
                    job.jobId(), unitIndex, "breeding-child-pre-add-exception"
            );
            return BreedingJobExecutionService.ChildSpawnResult.FAILED;
        }
        preparedPopulation.retainAmbiguous(
                job.jobId(), unitIndex, "breeding-child-spawn-exception-ambiguous"
        );
        return BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
    }

    private BreedingPreparedSpawnResult spawnPrepared(
            BreedingBirthJob job,
            int childIndex,
            int unitIndex,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            BreedingHytaleJobRuntime.Context context,
            RoleResolution role) {
        return spawnService.spawnPreparedWithFallback(
                role.plugin(),
                context.store(),
                role.roleIndex(),
                spawnPosition(job, childIndex),
                spawnRotation(context),
                preparedPopulation.destination(job.jobId(), unitIndex),
                reserved.plannedNpcUuid(),
                BreedingChildProjectionMarker.create(
                        BreedingAttemptIdentity.attemptKey(job.jobId()),
                        reserved.childKey(),
                        reserved.profileId(),
                        reserved.plannedNpcUuid()
                ),
                (npc, holder, spawnStore) -> prepareSpawnHolder(
                        job, unitIndex, reserved, npc, holder
                )
        );
    }

    private BreedingJobExecutionService.ChildSpawnResult finishPreparedSpawn(
            BreedingBirthJob job,
            PlannedChild child,
            int unitIndex,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            BreedingHytaleJobRuntime.Context context,
            BreedingPreparedSpawnResult spawn) {
        if (spawn.spawned() == null) {
            return terminalizeMissingSpawn(job, unitIndex, spawn);
        }
        Ref<EntityStore> childRef = spawn.spawned().first();
        NPCEntity childNpc = spawn.spawned().second();
        boolean completed = completionGuard.complete(
                () -> initializeSpawnedChild(
                        job, child, unitIndex, reserved, childRef, childNpc, context
                ),
                exception -> retainPostAddFailure(job, unitIndex, childNpc, exception)
        );
        return completed
                ? BreedingJobExecutionService.ChildSpawnResult.SPAWNED
                : BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
    }

    private BreedingJobExecutionService.ChildSpawnResult terminalizeMissingSpawn(
            BreedingBirthJob job,
            int unitIndex,
            BreedingPreparedSpawnResult spawn) {
        if (spawn.outcomeAmbiguous()) {
            preparedPopulation.retainAmbiguous(
                    job.jobId(), unitIndex, "breeding_spawn_outcome_ambiguous"
            );
            return BreedingJobExecutionService.ChildSpawnResult.AMBIGUOUS;
        }
        preparedPopulation.cancelUnit(
                job.jobId(),
                unitIndex,
                spawn.reason() == null ? "breeding-child-spawn-failed" : spawn.reason()
        );
        return BreedingJobExecutionService.ChildSpawnResult.FAILED;
    }

    private void retainPostAddFailure(
            BreedingBirthJob job,
            int unitIndex,
            NPCEntity childNpc,
            Throwable failure) {
        preparedPopulation.retainAmbiguous(
                job.jobId(), unitIndex, "breeding-post-add-follow-up-ambiguous"
        );
        logSpawnFollowUpFailure(job, childNpc, failure);
    }

    @Nullable
    private String prepareSpawnHolder(
            BreedingBirthJob job,
            int unitIndex,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            NPCEntity npc,
            com.hypixel.hytale.component.Holder<EntityStore> holder) {
        installReservedLegacyUuid(npc, reserved);
        OwnerComponentMutationService.WriteResult write =
                preparedPopulation.writeSpawnHolder(job.jobId(), unitIndex, holder);
        if (write.applied()) {
            return null;
        }
        if (write.safeToCancel()) {
            preparedPopulation.cancelUnit(job.jobId(), unitIndex, write.reason());
        } else {
            preparedPopulation.retainAmbiguous(job.jobId(), unitIndex, write.reason());
        }
        return write.reason();
    }

    /** Keeps the legacy NPC identity aligned with the reserved UUID before world insertion. */
    static void installReservedLegacyUuid(
            @Nonnull NPCEntity npc,
            @Nonnull PreparedBreedingPopulationBatch.ReservedChild reserved) {
        npc.setLegacyUUID(reserved.plannedNpcUuid());
    }

    private void initializeSpawnedChild(
            BreedingBirthJob job,
            PlannedChild child,
            int unitIndex,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            Ref<EntityStore> childRef,
            NPCEntity childNpc,
            BreedingHytaleJobRuntime.Context context) {
        BreedingOffspringPostSpawnService.Request request = postSpawnRequest(
                job, child, reserved, childRef, childNpc, context
        );
        postSpawnService.finish(
                request,
                () -> preparedPopulation.commitSpawn(job.jobId(), unitIndex),
                reason -> logPopulationFollowUp(job, reserved, reason),
                () -> { }
        );
    }

    private BreedingOffspringPostSpawnService.Request postSpawnRequest(
            BreedingBirthJob job,
            PlannedChild child,
            PreparedBreedingPopulationBatch.ReservedChild reserved,
            Ref<EntityStore> childRef,
            NPCEntity childNpc,
            BreedingHytaleJobRuntime.Context context) {
        return new BreedingOffspringPostSpawnService.Request(
                childRef,
                childNpc,
                context.firstRef(),
                context.secondRef(),
                context.store(),
                context.config(),
                child.roleId(),
                reserved.plannedNpcUuid(),
                configId(context.config()),
                reservedOwner(reserved),
                BreedingOffspringProgressionService.OwnerSnapshot.empty(),
                context.firstTamed(),
                context.secondTamed(),
                child.adultRoleId(),
                TwBreedingConfig.Gender.fromConfigValue(child.gender()),
                lifecycleFamily(context.config(), child),
                CompanionLifeStageService.LifecycleFamilyResolution.PLANNED_SELECTION_ONLY,
                resolution -> logInfo(
                        "Breeding child cooldown resolved: job=" + job.jobId()
                                + " durationMs=" + resolution.durationMs() + "."
                ),
                physicsReset -> logInfo(
                        "Breeding spawn success: job=" + job.jobId()
                                + " child=" + reserved.plannedNpcUuid()
                                + " role=" + child.roleId()
                                + " physicsReset={" + physicsReset + "}."
                )
        );
    }

    static BreedingOffspringProgressionService.OwnerSnapshot reservedOwner(
            PreparedBreedingPopulationBatch.ReservedChild child) {
        return new BreedingOffspringProgressionService.OwnerSnapshot(
                child.ownerId(), child.ownerName()
        );
    }

    private Vector3d spawnPosition(BreedingBirthJob job, int childIndex) {
        double offsetX = childIndex == 0
                ? 0.0
                : ThreadLocalRandom.current().nextDouble(-0.55, 0.55);
        double offsetZ = childIndex == 0
                ? 0.0
                : ThreadLocalRandom.current().nextDouble(-0.55, 0.55);
        return new Vector3d(
                job.anchor().x() + offsetX,
                job.anchor().y(),
                job.anchor().z() + offsetZ
        );
    }

    private Rotation3f spawnRotation(BreedingHytaleJobRuntime.Context context) {
        TransformComponent first = transform(context.firstRef(), context);
        TransformComponent second = transform(context.secondRef(), context);
        if (first != null && second != null) {
            Vector3d delta = new Vector3d(second.getPosition()).sub(first.getPosition());
            if (delta.lengthSquared() > 0.00001) {
                return Rotation3f.lookAt(delta);
            }
        }
        TransformComponent fallback = first != null ? first : second;
        return fallback != null ? new Rotation3f(fallback.getRotation()) : new Rotation3f();
    }

    @Nullable
    static TwBreedingConfig.RoleFamily lifecycleFamily(
            @Nullable TwBreedingConfig config,
            PlannedChild child) {
        if (config == null
                || child.lifecycleFamily() == null
                || child.lifecycleFamily().isBlank()) {
            return null;
        }
        return plannedLifecycleFamily(config, child);
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily plannedLifecycleFamily(
            TwBreedingConfig config,
            PlannedChild child) {
        String key = child.lifecycleFamily();
        int separator = key.indexOf(':');
        String familyId = (separator < 0 ? key : key.substring(0, separator)).trim();
        String lineId = separator < 0 ? null : key.substring(separator + 1).trim();
        TwBreedingConfig.RoleFamily family = findLifecycleFamily(
                config.resolveOffspringLifecycle(child.roleId()), familyId
        );
        if (family == null) {
            family = findLifecycleFamily(config.getOffspringLifecycle(), familyId);
        }
        if (family == null || lineId == null || lineId.isBlank()) {
            return family;
        }
        for (TwBreedingConfig.RoleLine line : family.getLines()) {
            if (line != null && lineId.equalsIgnoreCase(line.getId())) {
                return family.copyForLine(line);
            }
        }
        return null;
    }

    @Nullable
    private static TwBreedingConfig.RoleFamily findLifecycleFamily(
            TwBreedingConfig.OffspringLifecycleSettings settings,
            String familyId) {
        if (familyId.isBlank()) {
            return null;
        }
        for (TwBreedingConfig.RoleFamily family : settings.getFamilies()) {
            if (family != null && familyId.equalsIgnoreCase(family.getId())) {
                return family;
            }
        }
        return null;
    }

    @Nullable
    private TransformComponent transform(
            Ref<EntityStore> ref,
            BreedingHytaleJobRuntime.Context context) {
        return ref != null && ref.isValid()
                ? context.store().getComponent(ref, TransformComponent.getComponentType())
                : null;
    }

    @Nullable
    private String configId(@Nullable TwBreedingConfig config) {
        return config != null ? config.getId() : null;
    }

    private void logInfo(String message) {
        Tamework plugin = Tamework.getInstance();
        if (plugin != null && plugin.getLogger() != null && plugin.isDebugBreedingEnabled()) {
            plugin.getLogger().at(Level.INFO).log(message);
        }
    }

    private void logSpawnFollowUpFailure(
            BreedingBirthJob job,
            NPCEntity childNpc,
            Throwable failure) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                "Breeding child was created but follow-up initialization failed: job="
                        + job.jobId() + " child=" + childNpc.getUuid()
                        + ". Birth remains committed to prevent a duplicate litter."
        );
    }

    private void logPopulationFollowUp(
            BreedingBirthJob job,
            PreparedBreedingPopulationBatch.ReservedChild child,
            String reason) {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null || plugin.getLogger() == null) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(
                "Breeding child follow-up requires reconciliation: job=" + job.jobId()
                        + " childKey=" + child.childKey()
                        + " plannedUuid=" + child.plannedNpcUuid()
                        + " reason=" + reason + "."
        );
    }

    private record RoleResolution(@Nullable NPCPlugin plugin, int roleIndex) {
        private boolean valid() {
            return plugin != null && roleIndex >= 0;
        }
    }
}
