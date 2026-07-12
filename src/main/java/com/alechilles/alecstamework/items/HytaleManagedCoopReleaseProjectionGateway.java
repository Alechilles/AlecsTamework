package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.SpawnPlacement;
import com.alechilles.alecstamework.items.ManagedCoopReleaseRecoveryService.ProjectionToken;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Status;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionCommand;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionGateway;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Re-resolves a release world/store and computes placement only after the durable spawn claim. */
final class HytaleManagedCoopReleaseProjectionGateway implements ReleaseProjectionGateway {
    private final ManagedCoopReleaseRuntimeAdapter releases;
    private final CoopResidentReleasePositionService positions;
    private final HytaleManagedCoopRemovalEvidenceReader evidenceReader;
    private final ManagedCoopReleaseSiteValidator siteValidator;
    @Nullable
    private final ManagedCoopReleasePopulationCoordinator populations;
    private final ProjectionTokenCurrentness projectionCurrentness;

    HytaleManagedCoopReleaseProjectionGateway(ManagedCoopReleaseRuntimeAdapter releases) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(), null);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            ManagedCoopReleasePopulationCoordinator populations) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(), populations);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            ManagedCoopResidentIndex residents,
            ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(residents, compositeIndexes), null);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            ManagedCoopResidentIndex residents,
            ManagedCoopCompositeIndexRefreshService compositeIndexes,
            ManagedCoopReleasePopulationCoordinator populations) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(residents, compositeIndexes), populations);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            ManagedCoopResidentIndex residents,
            ManagedCoopCompositeIndexRefreshService compositeIndexes,
            ManagedCoopReleasePopulationCoordinator populations,
            ProjectionTokenCurrentness projectionCurrentness) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(residents, compositeIndexes), populations,
                projectionCurrentness);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions) {
        this(releases, positions, new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(), null);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions,
            HytaleManagedCoopRemovalEvidenceReader evidenceReader,
            ManagedCoopReleaseSiteValidator siteValidator) {
        this(releases, positions, evidenceReader, siteValidator, null);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions,
            HytaleManagedCoopRemovalEvidenceReader evidenceReader,
            ManagedCoopReleaseSiteValidator siteValidator,
            @Nullable ManagedCoopReleasePopulationCoordinator populations) {
        this(releases, positions, evidenceReader, siteValidator, populations, token -> false);
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions,
            HytaleManagedCoopRemovalEvidenceReader evidenceReader,
            ManagedCoopReleaseSiteValidator siteValidator,
            @Nullable ManagedCoopReleasePopulationCoordinator populations,
            ProjectionTokenCurrentness projectionCurrentness) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.siteValidator = Objects.requireNonNull(siteValidator, "siteValidator");
        this.populations = populations;
        this.projectionCurrentness = Objects.requireNonNull(
                projectionCurrentness, "projectionCurrentness");
    }

    @Nonnull
    @Override
    public CompletableFuture<Outcome> project(@Nonnull ReleaseProjectionCommand command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<Outcome> completion = new CompletableFuture<>();
        World world = resolveWorld(command.site().worldName());
        if (world == null) {
            if (populations != null) {
                rollbackBeforePopulation(
                        command, "managed_coop_release_world_unavailable", completion);
            } else {
                completion.completeExceptionally(
                        new IllegalStateException("managed_coop_release_world_unavailable"));
            }
            return completion;
        }
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> projectOnWorldThread(world, command, completion),
                () -> {
                    if (populations != null) {
                        rollbackBeforePopulation(command,
                                "managed_coop_release_world_dispatch_rejected", completion);
                    } else {
                        completion.completeExceptionally(new IllegalStateException(
                                "managed_coop_release_world_dispatch_rejected"));
                    }
                });
        return completion;
    }

    private void projectOnWorldThread(World world,
                                      ReleaseProjectionCommand command,
                                      CompletableFuture<Outcome> completion) {
        try {
            if (world.getEntityStore() == null || world.getEntityStore().getStore() == null) {
                throw new IllegalStateException("managed_coop_release_store_unavailable");
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            store.assertThread();
            if (world.getName() == null
                    || !world.getName().equalsIgnoreCase(command.site().worldName())) {
                throw new IllegalStateException("managed_coop_release_world_identity_mismatch");
            }
            ManagedCoopRemovalEvidence.Result physical = evidenceReader.inspect(
                    world.getChunkStore().getStore(), world,
                    command.site().authorityKey(), command.site().expectedCoopId());
            ManagedCoopReleaseSiteValidator.Validation validation =
                    siteValidator.validate(command.site(), physical);
            if (!validation.allowed()) {
                throw new IllegalStateException(validation.detail());
            }
            SpawnPlacement placement = placement(world, command, validation.currentRotationIndex());
            if (populations != null) {
                preparePopulation(world, command, placement, completion);
                return;
            }
            requireRecoveryCurrent(command);
            CompletableFuture<Outcome> release = releases.release(
                    command.claim(), command.resident(), placement, store);
            if (release == null) {
                throw new IllegalStateException("managed_coop_release_projection_future_missing");
            }
            release.whenComplete((outcome, failure) -> {
                if (failure != null) {
                    completion.completeExceptionally(failure);
                } else if (outcome == null) {
                    completion.completeExceptionally(new IllegalStateException(
                            "managed_coop_release_projection_outcome_missing"));
                } else {
                    completion.complete(outcome);
                }
            });
        } catch (RuntimeException exception) {
            if (populations != null) {
                rollbackBeforePopulation(
                        command,
                        exception.getMessage() != null
                                ? exception.getMessage()
                                : "managed_coop_release_preparation_validation_failed",
                        completion);
            } else {
                completion.completeExceptionally(exception);
            }
        }
    }

    private void preparePopulation(World world,
                                   ReleaseProjectionCommand command,
                                   SpawnPlacement placement,
                                   CompletableFuture<Outcome> completion) {
        CompletableFuture<ManagedCoopReleasePopulationCoordinator.Preparation> preparation;
        try {
            preparation = populations.prepareAsync(
                    command.claim(),
                    command.resident(),
                    world.getName(),
                    ChunkUtil.chunkCoordinate(placement.x()),
                    ChunkUtil.chunkCoordinate(placement.z()));
        } catch (RuntimeException exception) {
            populations.markReadinessDegraded(
                    "managed_coop_population_preparation_start_ambiguous");
            completion.complete(ambiguous(
                    "managed_coop_population_preparation_start_ambiguous"));
            return;
        }
        if (preparation == null) {
            populations.markReadinessDegraded(
                    "managed_coop_population_preparation_future_missing");
            completion.complete(ambiguous(
                    "managed_coop_population_preparation_future_missing"));
            return;
        }
        preparation.whenComplete((result, failure) -> {
            if (failure != null) {
                populations.markReadinessDegraded(
                        "managed_coop_population_preparation_completion_ambiguous");
                completion.complete(ambiguous(
                        "managed_coop_population_preparation_completion_ambiguous"));
                return;
            }
            if (result == null) {
                populations.markReadinessDegraded(
                        "managed_coop_population_preparation_result_missing");
                completion.complete(ambiguous(
                        "managed_coop_population_preparation_result_missing"));
                return;
            }
            if (result.status()
                    == ManagedCoopReleasePopulationCoordinator.PreparationStatus.FAILED) {
                populations.markReadinessDegraded(
                        "managed_coop_population_preparation_failed_ambiguous");
                completion.complete(ambiguous(result.detail()));
                return;
            }
            if (result.status()
                    == ManagedCoopReleasePopulationCoordinator.PreparationStatus.AMBIGUOUS) {
                populations.markReadinessDegraded(
                        "managed_coop_population_preparation_retained_ambiguous");
                completion.complete(ambiguous(result.detail()));
                return;
            }
            if (result.status()
                    == ManagedCoopReleasePopulationCoordinator.PreparationStatus.DENIED) {
                rollbackBeforePopulation(command, result.detail(), completion);
                return;
            }
            if (!result.preparedSuccessfully() || result.prepared() == null) {
                populations.markReadinessDegraded(
                        "managed_coop_population_preparation_inconsistent");
                completion.complete(ambiguous(
                        "managed_coop_population_preparation_inconsistent"));
                return;
            }
            dispatchPrepared(world, command, placement, result.prepared(), completion);
        });
    }

    private void rollbackBeforePopulation(
            ReleaseProjectionCommand command,
            String reason,
            CompletableFuture<Outcome> completion) {
        final CompletableFuture<Boolean> rollback;
        try {
            rollback = populations.rollbackBeforePreparationAsync(
                    command.claim(), reason);
        } catch (RuntimeException exception) {
            populations.markReadinessDegraded(
                    "managed_coop_release_preparation_rollback_start_failed");
            completion.complete(blocked(reason));
            return;
        }
        if (rollback == null) {
            populations.markReadinessDegraded(
                    "managed_coop_release_preparation_rollback_stage_missing");
            completion.complete(blocked(reason));
            return;
        }
        rollback.whenComplete((rolledBack, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(rolledBack)) {
                populations.markReadinessDegraded(
                        "managed_coop_release_preparation_rollback_failed");
            }
            completion.complete(blocked(reason));
        });
    }

    private void dispatchPrepared(
            World world,
            ReleaseProjectionCommand command,
            SpawnPlacement preparedPlacement,
            ManagedCoopReleasePopulationCoordinator.PreparedRelease prepared,
            CompletableFuture<Outcome> completion) {
        LeaseBoundWorldDispatcher.execute(
                world,
                () -> projectPreparedOnWorldThread(
                        world, command, preparedPlacement, prepared, completion),
                () -> cancelThenComplete(
                        prepared,
                        "managed_coop_release_pre_spawn_dispatch_rejected",
                        blocked("managed_coop_release_pre_spawn_dispatch_rejected"),
                        completion));
    }

    private void projectPreparedOnWorldThread(
            World world,
            ReleaseProjectionCommand command,
            SpawnPlacement preparedPlacement,
            ManagedCoopReleasePopulationCoordinator.PreparedRelease prepared,
            CompletableFuture<Outcome> completion) {
        try {
            if (world.getEntityStore() == null || world.getEntityStore().getStore() == null) {
                throw new IllegalStateException("managed_coop_release_store_unavailable");
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            store.assertThread();
            ManagedCoopRemovalEvidence.Result physical = evidenceReader.inspect(
                    world.getChunkStore().getStore(), world,
                    command.site().authorityKey(), command.site().expectedCoopId());
            ManagedCoopReleaseSiteValidator.Validation validation =
                    siteValidator.validate(command.site(), physical);
            if (!validation.allowed()) {
                throw new IllegalStateException(validation.detail());
            }
            SpawnPlacement currentPlacement = placement(
                    world, command, validation.currentRotationIndex());
            int chunkX = ChunkUtil.chunkCoordinate(currentPlacement.x());
            int chunkZ = ChunkUtil.chunkCoordinate(currentPlacement.z());
            if (world.getName() == null
                    || !world.getName().equalsIgnoreCase(
                            prepared.destinationWorldName())
                    || !samePlacement(preparedPlacement, currentPlacement)
                    || chunkX != prepared.destinationChunkX()
                    || chunkZ != prepared.destinationChunkZ()) {
                throw new IllegalStateException(
                        "managed_coop_release_resolved_chunk_changed_after_prepare");
            }
            requireRecoveryCurrent(command);
            CompletableFuture<Outcome> release = releases.release(
                    command.claim(), command.resident(), preparedPlacement, store,
                    prepared, populations);
            if (release == null) {
                throw new IllegalStateException(
                        "managed_coop_release_projection_future_missing");
            }
            release.whenComplete((outcome, failure) -> {
                if (failure != null || outcome == null) {
                    populations.markReadinessDegraded(
                            "managed_coop_release_post_spawn_completion_ambiguous");
                    completion.complete(failure != null
                            ? ambiguous("managed_coop_release_completion_failed")
                            : ambiguous("managed_coop_release_projection_outcome_missing"));
                    return;
                }
                finishPopulationOutcome(prepared, outcome, completion);
            });
        } catch (RuntimeException exception) {
            cancelThenComplete(
                    prepared,
                    "managed_coop_release_pre_spawn_validation_failed",
                    blocked(exception.getMessage() != null
                            ? exception.getMessage()
                            : "managed_coop_release_pre_spawn_validation_failed"),
                    completion);
        }
    }

    private void requireRecoveryCurrent(ReleaseProjectionCommand command) {
        ProjectionToken token = command.recoveryToken();
        if (token != null && !projectionCurrentness.current(token)) {
            throw new IllegalStateException(
                    "persisted_release_projection_evidence_changed_before_spawn");
        }
    }

    @FunctionalInterface
    interface ProjectionTokenCurrentness {
        boolean current(ProjectionToken token);
    }

    private void finishPopulationOutcome(
            ManagedCoopReleasePopulationCoordinator.PreparedRelease prepared,
            Outcome outcome,
            CompletableFuture<Outcome> completion) {
        if (outcome.status() == Status.SPAWN_FAILED || outcome.status() == Status.BLOCKED) {
            cancelThenComplete(prepared,
                    outcome.detail() != null ? outcome.detail()
                            : "managed_coop_release_pre_spawn_failed",
                    outcome,
                    completion);
            return;
        }
        if (outcome.status() == Status.SPAWN_AMBIGUOUS
                || outcome.status() == Status.PERSISTENCE_FAILED) {
            populations.markReadinessDegraded(
                    "managed_coop_release_live_projection_unresolved");
        }
        completion.complete(outcome);
    }

    private void cancelThenComplete(
            ManagedCoopReleasePopulationCoordinator.PreparedRelease prepared,
            String reason,
            Outcome outcome,
            CompletableFuture<Outcome> completion) {
        populations.cancelAsync(prepared, reason).whenComplete((ignored, failure) ->
                completion.complete(outcome));
    }

    private static Outcome blocked(String detail) {
        return new Outcome(Status.BLOCKED, null, false, false, detail);
    }

    private static Outcome ambiguous(String detail) {
        return new Outcome(Status.SPAWN_AMBIGUOUS, null, false, false, detail);
    }

    private static boolean samePlacement(SpawnPlacement expected, SpawnPlacement actual) {
        return Double.compare(expected.x(), actual.x()) == 0
                && Double.compare(expected.y(), actual.y()) == 0
                && Double.compare(expected.z(), actual.z()) == 0
                && Float.compare(expected.pitch(), actual.pitch()) == 0
                && Float.compare(expected.yaw(), actual.yaw()) == 0
                && Float.compare(expected.roll(), actual.roll()) == 0;
    }

    @Nonnull
    private SpawnPlacement placement(World world,
                                     ReleaseProjectionCommand command,
                                     int currentRotationIndex) {
        String roleId = command.resident().roleId();
        NPCPlugin plugin = NPCPlugin.get();
        if (roleId == null || roleId.isBlank() || plugin == null) {
            throw new IllegalStateException("managed_coop_release_role_unavailable");
        }
        int roleIndex = plugin.getIndex(roleId);
        Builder<Role> role = roleIndex >= 0 ? plugin.tryGetCachedValidRole(roleIndex) : null;
        if (role == null) {
            throw new IllegalStateException("managed_coop_release_role_builder_unavailable");
        }
        var site = command.site();
        Vector3d position = positions.resolveSpawnPosition(
                world,
                role,
                new Vector3i(site.blockX(), site.blockY(), site.blockZ()),
                currentRotationIndex,
                site.offsetX(), site.offsetY(), site.offsetZ());
        return new SpawnPlacement(
                position.x, position.y, position.z,
                0.0f, 0.0f, 0.0f);
    }

    private World resolveWorld(String worldName) {
        Universe universe = Universe.get();
        return universe != null ? universe.getWorld(worldName) : null;
    }
}
