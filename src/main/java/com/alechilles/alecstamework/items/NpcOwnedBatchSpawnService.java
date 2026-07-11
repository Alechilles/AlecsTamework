package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ownership.CompanionPopulationBatchMode;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.PopulationDenialFeedback;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Prepares a capacity-clamped command batch before spawning any owned NPC. */
final class NpcOwnedBatchSpawnService {
    private static final double SPAWN_RING_RADIUS_STEP = 0.9;
    private static final int SPAWN_RING_SIZE = 6;

    private final NpcSpawnCommandService owner;
    private final SpawnerSpawnPositionService spawnPositionService;

    NpcOwnedBatchSpawnService(
            @Nonnull NpcSpawnCommandService owner,
            @Nonnull SpawnerSpawnPositionService spawnPositionService
    ) {
        this.owner = owner;
        this.spawnPositionService = spawnPositionService;
    }

    void schedule(
            @Nonnull Player player,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull World world,
            @Nonnull String roleId,
            int quantity,
            @Nullable Map<String, String> attachmentOverrides,
            @Nonnull Consumer<NpcSpawnCommandService.SpawnBatchResult> completion
    ) {
        if (quantity <= 0) {
            completion.accept(NpcSpawnCommandService.SpawnBatchResult.failure(
                    "Quantity must be greater than zero."
            ));
            return;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin == null ? -1 : npcPlugin.getIndex(roleId);
        UUID ownerId = player.getUuid();
        Vector3d base = spawnPositionService.resolveSpawnPosition(player, null);
        CompanionSpawnPopulationAdmissionService admission = resolveAdmissionService();
        if (npcPlugin == null || roleIndex < 0 || ownerId == null || base == null || admission == null) {
            completion.accept(NpcSpawnCommandService.SpawnBatchResult.failure(
                    failureMessage(npcPlugin, roleIndex, ownerId, base, admission, roleId)
            ));
            return;
        }
        NpcSpawnCommandService.BatchTracker tracker = owner.newBatchTracker(
                quantity, player, completion
        );
        List<Vector3d> positions = positions(base, quantity);
        List<CompanionSpawnAdmissionRequest> requests = requests(
                player, world, ownerId, positions
        );
        admission.prepareBatchAsync(requests, CompanionPopulationBatchMode.UP_TO)
                .whenComplete((preparation, failure) -> dispatch(
                         world,
                         () -> applyPrepared(
                                 ownerId, world, npcPlugin, roleIndex,
                                 attachmentOverrides, positions, admission, preparation, failure, tracker
                        ),
                        () -> cancelPrepared(admission, preparation, tracker)
                ));
    }

    private void applyPrepared(
            UUID playerUuid,
            World world,
            NPCPlugin npcPlugin,
            int roleIndex,
            @Nullable Map<String, String> attachments,
            List<Vector3d> positions,
            CompanionSpawnPopulationAdmissionService admission,
            @Nullable CompanionSpawnPreparationResult preparation,
            @Nullable Throwable failure,
            NpcSpawnCommandService.BatchTracker tracker
    ) {
        WorldPlayerResolver.ResolvedPlayer player =
                WorldPlayerResolver.resolve(world, playerUuid);
        if (failure != null || preparation == null || !preparation.allowed()
                || preparation.preparedBatch() == null) {
            if (player != null) {
                sendDenial(player.player(), preparation);
            }
            tracker.stop(preparation == null
                    ? "Population admission failed."
                    : "Population admission denied: " + preparation.reason() + ".");
            tracker.seal();
            return;
        }
        PreparedCompanionSpawnBatch batch = preparation.preparedBatch();
        if (player == null) {
            admission.cancelRemainingAsync(batch, "command-spawn-player-unavailable");
            tracker.stop("Player became unavailable before spawn.");
            tracker.seal();
            return;
        }
        Store<EntityStore> store = player.store();
        Ref<EntityStore> playerRef = player.ref();
        if (batch.spawns().size() < preparation.requestedCount()) {
            tracker.stop("Population capacity limited the requested quantity.");
        }
        CompanionPreparedSpawnService executor = new CompanionPreparedSpawnService(admission);
        for (int index = 0; index < batch.spawns().size(); index++) {
            Vector3d position = positions.get(index);
            Rotation3f rotation = spawnPositionService.resolveSpawnRotation(
                    store, playerRef, position
            );
            tracker.register(null);
            boolean spawned = executor.spawnAndCommit(
                    world,
                    store,
                    npcPlugin,
                    roleIndex,
                    position,
                    rotation,
                    batch,
                    index,
                    callbacks(playerUuid, world, attachments, tracker)
            );
            if (!spawned) {
                admission.cancelRemainingAsync(batch, "command-spawn-batch-stopped");
                tracker.stop("Spawn failed before completing the admitted quantity.");
                break;
            }
        }
        tracker.seal();
    }

    @Nonnull
    private CompanionPreparedSpawnService.Callbacks callbacks(
            UUID playerUuid,
            World world,
            @Nullable Map<String, String> attachmentOverrides,
            NpcSpawnCommandService.BatchTracker tracker
    ) {
        return new CompanionPreparedSpawnService.Callbacks() {
            private boolean completed;

            @Override
            public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(world, playerUuid);
                if (resolved == null) {
                    throw new IllegalStateException(
                            "Command spawn owner is unavailable after population commit."
                    );
                }
                Store<EntityStore> liveStore = live.store();
                NpcSpawnCommandService.AttachmentResolution resolution =
                        owner.resolveAttachmentOverrides(
                                live.ref(), liveStore, attachmentOverrides
                        );
                owner.applyPostAdmissionState(
                        liveStore, world, resolved.ref(), live.ref(), live.npc(), resolution
                );
                boolean linked = owner.linkHeldCommandItem(
                        tracker.autoLink(), resolved.player(), liveStore, live.ref(), live.npc()
                );
                completed = true;
                tracker.applied(linked, resolution);
            }

            @Override
            public void onDenied(String reason) {
                completed = true;
                tracker.denied("Population admission denied: " + reason + ".");
            }

            @Override
            public void onDurabilityDegraded(String reason) {
                tracker.durabilityDegraded(reason);
            }

            @Override
            public void onTerminal() {
                if (!completed) {
                    completed = true;
                    tracker.denied("Post-spawn continuation failed.");
                }
            }
        };
    }

    @Nonnull
    private static List<CompanionSpawnAdmissionRequest> requests(
            Player player,
            World world,
            UUID ownerId,
            List<Vector3d> positions
    ) {
        String batchId = UUID.randomUUID().toString();
        String ownerName = OwnerNameUtil.resolve(player);
        List<CompanionSpawnAdmissionRequest> requests = new ArrayList<>(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            Vector3d position = positions.get(index);
            requests.add(new CompanionSpawnAdmissionRequest(
                    null, null, null, false, ownerId, ownerName, world.getName(),
                    ChunkUtil.chunkCoordinate(position.x), ChunkUtil.chunkCoordinate(position.z),
                    OwnerPopulationOperation.NEW_OWNERSHIP, "command_spawn",
                    "command-spawn:" + batchId + ":" + index, false
            ));
        }
        return List.copyOf(requests);
    }

    @Nonnull
    private static List<Vector3d> positions(Vector3d base, int quantity) {
        List<Vector3d> positions = new ArrayList<>(quantity);
        for (int index = 0; index < quantity; index++) {
            positions.add(offsetSpawnPosition(base, index));
        }
        return List.copyOf(positions);
    }

    private static Vector3d offsetSpawnPosition(Vector3d base, int spawnIndex) {
        if (spawnIndex <= 0) {
            return new Vector3d(base);
        }
        int ring = (spawnIndex - 1) / SPAWN_RING_SIZE + 1;
        int ringSlot = (spawnIndex - 1) % SPAWN_RING_SIZE;
        double radius = ring * SPAWN_RING_RADIUS_STEP;
        double angle = (Math.PI * 2.0 * ringSlot) / SPAWN_RING_SIZE;
        return new Vector3d(
                base.x + (Math.cos(angle) * radius), base.y,
                base.z + (Math.sin(angle) * radius)
        );
    }

    private static void sendDenial(Player player, @Nullable CompanionSpawnPreparationResult result) {
        CompanionPopulationPreparationResult limiting = result == null ? null : result.limitingDecision();
        boolean sent = limiting != null && PopulationDenialFeedback.sendClaimCap(player, limiting);
        if (!sent) {
            PopulationDenialFeedback.sendOwnerOrUnavailable(
                    player,
                    result == null ? "command-spawn-population-prepare-failed" : result.reason(),
                    limiting == null ? null : limiting.ownerDecision()
            );
        }
    }

    @Nullable
    private static CompanionSpawnPopulationAdmissionService resolveAdmissionService() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null || plugin.getOwnerPopulationRuntime() == null
                ? null
                : plugin.getOwnerPopulationRuntime().companionSpawnAdmissionService();
    }

    private static String failureMessage(NPCPlugin npcPlugin, int roleIndex, UUID ownerId,
                                         Vector3d base,
                                         CompanionSpawnPopulationAdmissionService admission,
                                         String roleId) {
        if (npcPlugin == null) return "NPC plugin not available.";
        if (roleIndex < 0) return "Unknown role '" + roleId + "'.";
        if (ownerId == null) return "Player UUID not available.";
        if (base == null) return "Unable to resolve a spawn position.";
        return admission == null ? "Population admission service not available." : "Spawn unavailable.";
    }

    private static void cancelPrepared(
            CompanionSpawnPopulationAdmissionService admission,
            @Nullable CompanionSpawnPreparationResult preparation,
            NpcSpawnCommandService.BatchTracker tracker
    ) {
        if (preparation != null && preparation.preparedBatch() != null) {
            admission.cancelRemainingAsync(preparation.preparedBatch(), "command-spawn-world-unavailable");
        }
        tracker.stop("World became unavailable before spawn.");
        tracker.seal();
    }

    private static void dispatch(World world, Runnable task, Runnable rejected) {
        try {
            if (world.isAlive()) world.execute(task); else rejected.run();
        } catch (RuntimeException | LinkageError failure) {
            rejected.run();
        }
    }
}
