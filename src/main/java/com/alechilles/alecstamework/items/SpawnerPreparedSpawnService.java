package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnPopulationAdmissionService;
import com.alechilles.alecstamework.ownership.CompanionSpawnPreparationResult;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.PopulationDenialFeedback;
import com.alechilles.alecstamework.ownership.PreparedCompanionSpawnBatch;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns the filled-spawner release transaction from durable preparation through stale-source cleanup. */
final class SpawnerPreparedSpawnService {
    private final SpawnerSpawnPositionService spawnPositionService;
    private final SpawnerRolePolicyService rolePolicyService;
    private final SpawnerOwnershipPolicyService ownershipPolicyService;
    private final SpawnerItemStackMetadataService metadataService;
    private final SpawnerPlayerInventoryService inventoryService;
    private final SpawnerNpcStateService npcStateService;
    private final SpawnerAttachmentService attachmentService;
    private final SpawnerNpcProgressionMetadataService progressionService;
    private final SpawnerLinkedNpcSyncService linkedNpcSyncService;
    private final SpawnerEffectService effectService;
    @Nullable
    private final CommandLinkedNpcCoopService coopService;
    private final Consumer<String> debugLog;

    SpawnerPreparedSpawnService(
            @Nonnull SpawnerSpawnPositionService spawnPositionService,
            @Nonnull SpawnerRolePolicyService rolePolicyService,
            @Nonnull SpawnerOwnershipPolicyService ownershipPolicyService,
            @Nonnull SpawnerItemStackMetadataService metadataService,
            @Nonnull SpawnerPlayerInventoryService inventoryService,
            @Nonnull SpawnerNpcStateService npcStateService,
            @Nonnull SpawnerAttachmentService attachmentService,
            @Nonnull SpawnerNpcProgressionMetadataService progressionService,
            @Nonnull SpawnerLinkedNpcSyncService linkedNpcSyncService,
            @Nonnull SpawnerEffectService effectService,
            @Nullable CommandLinkedNpcCoopService coopService,
            @Nonnull Consumer<String> debugLog
    ) {
        this.spawnPositionService = spawnPositionService;
        this.rolePolicyService = rolePolicyService;
        this.ownershipPolicyService = ownershipPolicyService;
        this.metadataService = metadataService;
        this.inventoryService = inventoryService;
        this.npcStateService = npcStateService;
        this.attachmentService = attachmentService;
        this.progressionService = progressionService;
        this.linkedNpcSyncService = linkedNpcSyncService;
        this.effectService = effectService;
        this.coopService = coopService;
        this.debugLog = debugLog;
    }

    boolean schedule(
            @Nonnull Player player,
            @Nonnull ItemStack itemStack,
            @Nonnull ItemFeatureConfig config,
            @Nullable Integer hotbarSlot,
            @Nullable String emptyItemIdOverride
    ) {
        ValidatedSpawn context = validate(player, itemStack, config);
        if (context == null) {
            return false;
        }
        CompanionSpawnPopulationAdmissionService admission = resolveAdmissionService();
        if (admission == null) {
            debugLog.accept("spawn denied reason=population-authority-unavailable");
            return false;
        }
        ItemStack finalizedItem = finalizedSourceItem(
                itemStack, config, emptyItemIdOverride
        );
        UUID playerUuid = player.getUuid();
        Integer sourceSlot = resolveSourceHotbarSlot(player, hotbarSlot);
        SpawnerSourceItemTransaction source = new SpawnerSourceItemTransaction(
                inventoryService,
                context.world(),
                playerUuid,
                sourceSlot,
                itemStack,
                Tamework.getInstance() == null ? null : Tamework.getInstance().getLogger(),
                "Spawner spawn"
        );
        CompanionSpawnAdmissionRequest request = new CompanionSpawnAdmissionRequest(
                context.capturedProfileId(),
                context.capturedNpcUuid(),
                CompanionLifecycleState.CAPTURED,
                true,
                context.ownerId(),
                context.ownerId() != null && context.ownerId().equals(player.getUuid())
                        ? OwnerNameUtil.resolve(player)
                        : null,
                context.world().getName(),
                ChunkUtil.chunkCoordinate(context.position().x),
                ChunkUtil.chunkCoordinate(context.position().z),
                OwnerPopulationOperation.RESTORE,
                "spawner_release",
                "spawner-release:" + context.capturedNpcUuid(),
                false,
                CompanionSpawnSourceFinalizationContext.extensionJson(
                        CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM,
                        "spawner-source:" + context.capturedNpcUuid() + ":" + playerUuid
                                + ":" + String.valueOf(sourceSlot),
                        context.capturedNpcUuid(),
                        playerUuid,
                        sourceSlot,
                        sourceFingerprint(itemStack),
                        sourceFingerprint(finalizedItem)
                )
        );
        admission.prepareAsync(request).whenComplete((preparation, failure) -> dispatch(
                context.world(),
                () -> applyPrepared(
                        playerUuid, itemStack, config, finalizedItem, source, context,
                        admission, preparation, failure
                ),
                () -> cancelPrepared(admission, preparation, "spawner-release-world-unavailable")
        ));
        return true;
    }

    private void applyPrepared(
            UUID playerUuid,
            ItemStack sourceItem,
            ItemFeatureConfig config,
            ItemStack finalizedItem,
            SpawnerSourceItemTransaction source,
            ValidatedSpawn context,
            CompanionSpawnPopulationAdmissionService admission,
            @Nullable CompanionSpawnPreparationResult preparation,
            @Nullable Throwable failure
    ) {
        WorldPlayerResolver.ResolvedPlayer resolved =
                WorldPlayerResolver.resolve(context.world(), playerUuid);
        if (failure != null || preparation == null || !preparation.allowed()
                || preparation.preparedBatch() == null) {
            if (resolved != null) {
                sendDenial(resolved.player(), preparation);
            }
            debugLog.accept("spawn denied reason=" + (preparation == null
                    ? "population-prepare-failed"
                    : preparation.reason()));
            return;
        }
        PreparedCompanionSpawnBatch batch = preparation.preparedBatch();
        if (resolved == null) {
            admission.cancelRemainingAsync(batch, "spawner-release-player-unavailable");
            debugLog.accept("spawn denied reason=player-unavailable player=" + playerUuid);
            return;
        }
        CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot =
                linkedNpcSyncService.getCapturedSnapshot(context.capturedNpcUuid());
        CompanionPreparedSpawnService executor = new CompanionPreparedSpawnService(admission);
        executor.spawnAndCommit(
                context.world(),
                resolved.store(),
                context.npcPlugin(),
                context.roleIndex(),
                context.position(),
                context.rotation(),
                batch,
                0,
                callbacks(
                        playerUuid, sourceItem, finalizedItem, source,
                        config, context, capturedSnapshot
                )
        );
    }

    @Nonnull
    private CompanionPreparedSpawnService.Callbacks callbacks(
            @Nonnull UUID playerUuid,
            @Nonnull ItemStack sourceItem,
            @Nonnull ItemStack finalizedItem,
            @Nonnull SpawnerSourceItemTransaction source,
            @Nonnull ItemFeatureConfig config,
            @Nonnull ValidatedSpawn context,
            @Nullable CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot
    ) {
        return new CompanionPreparedSpawnService.Callbacks() {
            @Override
            public boolean finalizeSource(CompanionPreparedSpawnService.SpawnedCompanion live) {
                WorldPlayerResolver.ResolvedPlayer resolved =
                        WorldPlayerResolver.resolve(live.world(), playerUuid);
                if (resolved == null) {
                    return false;
                }
                if (!source.prepare(finalizedItem)) {
                    return false;
                }
                try {
                    linkedNpcSyncService.remapLinkedNpcRecordsAfterRespawn(
                            resolved.player(), context.capturedNpcUuid(), live.plannedNpcUuid()
                    );
                    linkedNpcSyncService.clearCapturedSnapshotIfPresent(context.capturedNpcUuid());
                    if (coopService != null) {
                        coopService.clearCoopSnapshot(context.capturedNpcUuid());
                    }
                    source.commit();
                    return true;
                } catch (RuntimeException | LinkageError failure) {
                    source.compensate();
                    return false;
                }
            }

            @Override
            public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                applyRestoredState(
                        playerUuid, sourceItem, config, context, capturedSnapshot, live
                );
            }

            @Override
            public void onDenied(String reason) {
                debugLog.accept("spawn denied reason=" + reason + " player=" + playerUuid);
            }

            @Override
            public void onDurabilityDegraded(String reason) {
                Tamework plugin = Tamework.getInstance();
                if (plugin != null) {
                    plugin.getLogger().at(Level.WARNING).log(
                            "Spawner release durability degraded after live spawn: " + reason
                    );
                }
            }
        };
    }

    private void applyRestoredState(
            UUID playerUuid,
            ItemStack sourceItem,
            ItemFeatureConfig config,
            ValidatedSpawn context,
            @Nullable CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot capturedSnapshot,
            CompanionPreparedSpawnService.SpawnedCompanion live
    ) {
        WorldPlayerResolver.ResolvedPlayer resolved =
                WorldPlayerResolver.resolve(live.world(), playerUuid);
        if (resolved == null) {
            throw new IllegalStateException("Spawner owner is unavailable after population commit.");
        }
        Player player = resolved.player();
        Ref<EntityStore> ref = live.ref();
        NPCEntity npc = live.npc();
        Store<EntityStore> store = live.store();
        npcStateService.applyMasterTarget(
                config, npc, resolved.ref(), context.ownerId(), live.world()
        );
        attachmentService.applyAttachments(sourceItem, ref, npc, store);
        npcStateService.applyTamed(ref, context.tamed(), live.world());
        npcStateService.applyCapturedName(sourceItem, ref, store);
        progressionService.applyNpcProgressionFromItem(sourceItem, ref, store);
        refreshProgressionAfterRestore(ref, npc, store);
        progressionService.applyNpcHealthFromItem(sourceItem, ref, store);
        linkedNpcSyncService.restoreCommandLinksFromCapturedSnapshot(
                ref, store, context.ownerId(), capturedSnapshot
        );
        effectService.playSpawnEffects(live.world(), ref, config);
        debugLog.accept("spawn success item=" + sourceItem.getItemId()
                + " player=" + player.getUuid() + " spawnedNpc=" + live.plannedNpcUuid());
    }

    @Nonnull
    private static String sourceFingerprint(@Nonnull ItemStack stack) {
        UUID target = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING
        );
        String profile = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING
        );
        return stack.getItemId() + "|" + String.valueOf(target) + "|"
                + String.valueOf(profile) + "|" + Integer.toUnsignedString(stack.hashCode(), 16);
    }

    @Nullable
    private ValidatedSpawn validate(Player player, ItemStack itemStack, ItemFeatureConfig config) {
        if (!config.isSpawnerEnabled()
                || metadataService.isCooldownActive(
                itemStack, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs())
                || !metadataService.isFilledItem(itemStack, config)) {
            return null;
        }
        UUID previousUuid = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING
        );
        if (previousUuid == null) {
            debugLog.accept("spawn denied reason=missing-captured-identity");
            return null;
        }
        String profileId = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMPANION_PROFILE_ID, Codec.STRING
        );
        String roleId = rolePolicyService.resolveSpawnRoleId(itemStack);
        if (roleId == null || roleId.isBlank() || !rolePolicyService.isRoleAllowed(roleId, config)) {
            return null;
        }
        World world = player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        Vector3d position = world == null ? null : spawnPositionService.resolveSpawnPosition(player, config);
        if (store == null || position == null
                || !spawnPositionService.isWithinSpawnDistance(player, position, config)) {
            return null;
        }
        UUID itemOwner = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        UUID sourceOwner = itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, Codec.UUID_STRING
        );
        UUID policyOwner = SpawnerOwnershipPolicyService.resolveSpawnPolicyOwner(
                itemOwner, sourceOwner, config
        );
        if (!ownershipPolicyService.isSpawnAllowed(player.getUuid(), policyOwner, config)) {
            return null;
        }
        UUID ownerId = SpawnerOwnershipPolicyService.resolveSpawnOwner(
                itemOwner, player.getUuid(), config
        );
        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin == null ? -1 : npcPlugin.getIndex(roleId);
        if (npcPlugin == null || roleIndex < 0) {
            return null;
        }
        Rotation3f rotation = spawnPositionService.resolveSpawnRotation(
                store, player.getReference(), position
        );
        boolean tamed = Boolean.TRUE.equals(itemStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TAMED, Codec.BOOLEAN
        ));
        return new ValidatedSpawn(
                world, store, npcPlugin, roleIndex, roleId, position, rotation,
                previousUuid, profileId, ownerId, sourceOwner, tamed
        );
    }

    private ItemStack finalizedSourceItem(
            ItemStack itemStack,
            ItemFeatureConfig config,
            @Nullable String emptyItemIdOverride
    ) {
        ItemStack updated = itemStack;
        String emptyItemId = emptyItemIdOverride != null
                ? emptyItemIdOverride : metadataService.resolveEmptyItemId(itemStack.getItemId());
        if (emptyItemId != null && !emptyItemId.isBlank()) {
            updated = metadataService.swapItemId(updated, emptyItemId);
        }
        updated = metadataService.clearCapturedMetadata(updated);
        return metadataService.applyCooldown(
                updated, TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL, config.getSpawnCooldownMs()
        );
    }

    private static void refreshProgressionAfterRestore(
            Ref<EntityStore> npcRef,
            NPCEntity npc,
            Store<EntityStore> store
    ) {
        CompanionStatModifierService.applyTraitModifiers(npcRef, store);
        CompanionLifeStageService.refreshLifeStage(npcRef, npc, store);
        CompanionLifeStageService.ensureGrowthTickScheduled(npcRef, npc, store);
    }

    private void sendDenial(Player player, @Nullable CompanionSpawnPreparationResult result) {
        CompanionPopulationPreparationResult limiting = result == null ? null : result.limitingDecision();
        boolean sent = limiting != null && PopulationDenialFeedback.sendClaimCap(player, limiting);
        if (!sent) {
            PopulationDenialFeedback.sendOwnerOrUnavailable(
                    player,
                    result == null ? "spawn-population-prepare-failed" : result.reason(),
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

    @Nullable
    private Integer resolveSourceHotbarSlot(Player player, @Nullable Integer explicitSlot) {
        if (explicitSlot != null && explicitSlot >= 0) {
            return explicitSlot;
        }
        byte active = PlayerInventoryAccess.getActiveHotbarSlot(player);
        return active < 0 ? null : (int) active;
    }

    private static void cancelPrepared(
            CompanionSpawnPopulationAdmissionService service,
            @Nullable CompanionSpawnPreparationResult result,
            String reason
    ) {
        if (result != null && result.preparedBatch() != null) {
            service.cancelRemainingAsync(result.preparedBatch(), reason);
        }
    }

    private static void dispatch(World world, Runnable task, Runnable rejected) {
        LeaseBoundWorldDispatcher.execute(world, task, rejected);
    }

    private record ValidatedSpawn(
            World world,
            Store<EntityStore> store,
            NPCPlugin npcPlugin,
            int roleIndex,
            String roleId,
            Vector3d position,
            Rotation3f rotation,
            UUID capturedNpcUuid,
            @Nullable String capturedProfileId,
            @Nullable UUID ownerId,
            @Nullable UUID captureSourceOwnerId,
            boolean tamed
    ) {
    }
}
