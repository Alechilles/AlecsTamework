package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseRuntimeAdapter.SpawnPlacement;
import com.alechilles.alecstamework.items.ManagedCoopReleaseSpawnOrchestrator.Outcome;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionCommand;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseProjectionGateway;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

/** Re-resolves a release world/store and computes placement only after the durable spawn claim. */
final class HytaleManagedCoopReleaseProjectionGateway implements ReleaseProjectionGateway {
    private final ManagedCoopReleaseRuntimeAdapter releases;
    private final CoopResidentReleasePositionService positions;
    private final HytaleManagedCoopRemovalEvidenceReader evidenceReader;
    private final ManagedCoopReleaseSiteValidator siteValidator;

    HytaleManagedCoopReleaseProjectionGateway(ManagedCoopReleaseRuntimeAdapter releases) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator());
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            ManagedCoopResidentIndex residents,
            ManagedCoopCompositeIndexRefreshService compositeIndexes) {
        this(releases, new CoopResidentReleasePositionService(),
                new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator(residents, compositeIndexes));
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions) {
        this(releases, positions, new HytaleManagedCoopRemovalEvidenceReader(),
                new ManagedCoopReleaseSiteValidator());
    }

    HytaleManagedCoopReleaseProjectionGateway(
            ManagedCoopReleaseRuntimeAdapter releases,
            CoopResidentReleasePositionService positions,
            HytaleManagedCoopRemovalEvidenceReader evidenceReader,
            ManagedCoopReleaseSiteValidator siteValidator) {
        this.releases = Objects.requireNonNull(releases, "releases");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.evidenceReader = Objects.requireNonNull(evidenceReader, "evidenceReader");
        this.siteValidator = Objects.requireNonNull(siteValidator, "siteValidator");
    }

    @Nonnull
    @Override
    public CompletableFuture<Outcome> project(@Nonnull ReleaseProjectionCommand command) {
        Objects.requireNonNull(command, "command");
        CompletableFuture<Outcome> completion = new CompletableFuture<>();
        World world = resolveWorld(command.site().worldName());
        if (world == null) {
            completion.completeExceptionally(
                    new IllegalStateException("managed_coop_release_world_unavailable"));
            return completion;
        }
        try {
            world.execute(() -> projectOnWorldThread(world, command, completion));
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
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
            completion.completeExceptionally(exception);
        }
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
