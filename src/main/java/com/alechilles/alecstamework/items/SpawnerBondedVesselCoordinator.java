package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.ownership.CompanionPopulationCommitResult;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselInitialBindingService;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselInteractionDispatcher;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselSpawnerBridge;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Owns bonded-vessel dispatch, projection validation, and generation-one binding. */
final class SpawnerBondedVesselCoordinator {
    private final HytaleLogger logger;
    private final SpawnerSpawnPositionService spawnPosition;
    private final SpawnerCaptureAttemptRuntimeCoordinator captureAttempts;
    private final Consumer<String> debugLog;
    @Nullable
    private volatile BondedVesselSpawnerBridge bridge;

    SpawnerBondedVesselCoordinator(
            HytaleLogger logger,
            SpawnerSpawnPositionService spawnPosition,
            SpawnerCaptureAttemptRuntimeCoordinator captureAttempts,
            Consumer<String> debugLog) {
        this.logger = logger;
        this.spawnPosition = spawnPosition;
        this.captureAttempts = captureAttempts;
        this.debugLog = debugLog;
    }

    void install(@Nullable BondedVesselSpawnerBridge bridge) {
        this.bridge = bridge;
    }

    boolean canUse(@Nullable ItemStack itemStack) {
        return bridge != null && hasUsableProjection(itemStack);
    }

    @Nullable
    InitialBindingAuthority prepareInitialBinding(@Nullable ItemStack itemStack) {
        BondedVesselSpawnerBridge current = bridge;
        return current != null && current.canBindSource(itemStack)
                ? new InitialBindingAuthority(current) : null;
    }

    boolean toggle(
            @Nullable Player player,
            @Nullable ItemStack itemStack,
            @Nullable ItemFeatureConfig config,
            @Nullable Integer hotbarSlot) {
        BondedVesselSpawnerBridge current = bridge;
        Integer exactSlot = resolveSourceHotbarSlot(player, hotbarSlot);
        if (current == null || player == null || itemStack == null || config == null
                || exactSlot == null || !hasUsableProjection(itemStack)) {
            debugLog.accept("bonded vessel denied reason=runtime-or-source-unavailable");
            return false;
        }
        PopulationAdmissionLocation destination = null;
        Vector3d position = spawnPosition.resolveSpawnPosition(player, config);
        World world = player.getWorld();
        if (position != null && world != null) {
            destination = new PopulationAdmissionLocation(
                    world.getName(),
                    com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(position.x),
                    com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(position.z));
        }
        current.toggle(player.getUuid(), exactSlot, itemStack.getItemId(), destination)
                .whenComplete((result, failure) -> {
                    if (failure != null || result == null
                            || result.status() != BondedVesselInteractionDispatcher.Status.COMMITTED) {
                        debugLog.accept("bonded vessel transition did not commit reason="
                                + (failure != null ? "runtime-failure"
                                : result == null ? "missing-result" : result.reason()));
                    }
                });
        return true;
    }

    void bindInitialCapture(
            InitialBindingAuthority authority,
            SpawnerSourceItemTransaction sourceItem,
            ItemStack original,
            @Nullable ItemStack captured,
            @Nullable String profileId,
            World world,
            UUID ownerUuid,
            @Nullable Integer sourceSlot,
            @Nullable CompanionPopulationCommitResult result,
            @Nullable UUID populationOperationId,
            @Nullable UUID captureAttemptId) {
        BondedVesselSpawnerBridge current = authority.bridge();
        long revision = result == null || result.ownerCommit() == null
                || result.ownerCommit().persistenceResult() == null
                ? -1L : result.ownerCommit().persistenceResult().revision();
        if (current == null || captured == null || profileId == null || sourceSlot == null
                || revision < 0L) {
            logger.at(Level.SEVERE).log(
                    "Bonded capture committed its canonical profile but could not prepare "
                            + "generation-one source finalization (profile=" + profileId + ").");
            return;
        }
        BondedVesselSpawnerBridge.InitialCapturePlan plan = current.prepareInitialCapture(
                ownerUuid, sourceSlot, original, captured, profileId, revision,
                populationOperationId).orElse(null);
        if (plan == null) {
            logger.at(Level.SEVERE).log(
                    "Bonded capture committed but its revision-pinned vessel config was unavailable "
                            + "(profile=" + profileId + ").");
            return;
        }
        current.bind(plan, (expected, replacement) -> {
            CompletableFuture<Boolean> completion = new CompletableFuture<>();
            try {
                world.execute(() -> completion.complete(sourceItem.prepare(replacement)));
            } catch (RuntimeException | LinkageError failure) {
                completion.completeExceptionally(failure);
            }
            return completion;
        }).whenComplete((binding, failure) -> {
            if (failure == null && binding != null
                    && binding.status() == BondedVesselInitialBindingService.Status.COMMITTED) {
                sourceItem.commit();
                captureAttempts.commit(captureAttemptId);
                debugLog.accept("bonded capture committed binding=" + binding.bindingId()
                        + " profile=" + binding.profileId());
                return;
            }
            if (binding != null
                    && (binding.status() == BondedVesselInitialBindingService.Status.DENIED
                    || binding.status() == BondedVesselInitialBindingService.Status.QUARANTINED)) {
                captureAttempts.quarantine(captureAttemptId, binding.reason());
            }
            logger.at(Level.SEVERE).log(
                    "Bonded capture generation-one finalization remains pending or quarantined "
                            + "(profile=" + profileId + ", reason="
                            + (failure != null ? "runtime-failure"
                            : binding == null ? "missing-result" : binding.reason()) + ").");
        });
    }

    static boolean hasUsableProjection(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty() || itemStack.getQuantity() != 1) return false;
        String bindingId = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_BINDING_ID, Codec.STRING);
        String profileId = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_PROFILE_ID, Codec.STRING);
        Long generation = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG);
        String configId = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_CONFIG_ID, Codec.STRING);
        String state = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_STATE, Codec.STRING);
        return bindingId != null && profileId != null && generation != null && generation > 0L
                && configId != null && (BondedVesselState.STORED.name().equals(state)
                || BondedVesselState.ACTIVE.name().equals(state));
    }

    @Nullable
    private static Integer resolveSourceHotbarSlot(
            @Nullable Player player, @Nullable Integer explicitSlot) {
        if (explicitSlot != null && explicitSlot >= 0) return explicitSlot;
        if (player == null) return null;
        byte activeSlot = PlayerInventoryAccess.getActiveHotbarSlot(player);
        return activeSlot < 0 ? null : (int) activeSlot;
    }

    record InitialBindingAuthority(BondedVesselSpawnerBridge bridge) {
        InitialBindingAuthority {
            java.util.Objects.requireNonNull(bridge, "bridge");
        }
    }
}
