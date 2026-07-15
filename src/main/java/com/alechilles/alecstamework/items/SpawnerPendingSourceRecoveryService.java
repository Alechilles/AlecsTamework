package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Finalizes exact filled-item sources left behind by a committed pre-restart spawn. */
final class SpawnerPendingSourceRecoveryService {
    private final ItemFeatureRegistry registry;
    private final SpawnerItemStackMetadataService metadata;
    private final SpawnerLinkedNpcSyncService linkedNpcs;
    @Nullable
    private final CommandLinkedNpcCoopService coops;
    private final HytaleLogger logger;
    private final Set<UUID> recoveringPlayers = ConcurrentHashMap.newKeySet();

    SpawnerPendingSourceRecoveryService(
            @Nonnull ItemFeatureRegistry registry,
            @Nonnull SpawnerItemStackMetadataService metadata,
            @Nonnull SpawnerLinkedNpcSyncService linkedNpcs,
            @Nullable CommandLinkedNpcCoopService coops,
            @Nonnull HytaleLogger logger
    ) {
        this.registry = registry;
        this.metadata = metadata;
        this.linkedNpcs = linkedNpcs;
        this.coops = coops;
        this.logger = logger;
    }

    void recoverAfterWorldJoin(@Nullable World world, @Nullable UUID playerUuid) {
        Tamework plugin = Tamework.getInstance();
        TameworkPersistenceRuntime persistence = plugin == null
                ? null : plugin.getPersistenceRuntime();
        OwnerPopulationRuntime populations = plugin == null
                ? null : plugin.getOwnerPopulationRuntime();
        if (world == null || playerUuid == null || persistence == null || populations == null
                || !persistence.getHealthService().isHealthy()
                || !recoveringPlayers.add(playerUuid)) {
            return;
        }
        CompletableFuture.supplyAsync(
                () -> loadOperations(persistence.getCompanionPopulationRepository()),
                CompletableFuture.delayedExecutor(1L, TimeUnit.SECONDS)
        ).whenComplete((operations, failure) -> {
            if (failure != null || operations == null) {
                recoveringPlayers.remove(playerUuid);
                return;
            }
            LeaseBoundWorldDispatcher.execute(
                    world,
                    () -> recoverOnWorld(
                            world, playerUuid, operations, persistence, populations
                    ),
                    () -> recoveringPlayers.remove(playerUuid)
            );
        });
    }

    private void recoverOnWorld(
            World world,
            UUID playerUuid,
            List<CompanionPopulationOperationRecord> operations,
            TameworkPersistenceRuntime persistence,
            OwnerPopulationRuntime populations
    ) {
        WorldPlayerResolver.ResolvedPlayer resolved = WorldPlayerResolver.resolve(world, playerUuid);
        if (resolved == null) {
            recoveringPlayers.remove(playerUuid);
            return;
        }
        List<CompletableFuture<Boolean>> completions = new ArrayList<>();
        for (CompanionPopulationOperationRecord operation : operations) {
            CompletableFuture<Boolean> completion = recoverOne(
                    resolved.player(), operation, persistence.getCompanionPopulationRepository()
            );
            if (completion != null) {
                completions.add(completion);
            }
        }
        if (completions.isEmpty()) {
            recoveringPlayers.remove(playerUuid);
            return;
        }
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    recoveringPlayers.remove(playerUuid);
                    if (failure != null || completions.stream().anyMatch(future -> !successful(future))) {
                        logger.at(Level.WARNING).log(
                                "Pending captured-item source recovery did not finish durably."
                        );
                        return;
                    }
                    populations.restartReconciliationAfterExternalRepair()
                            .whenComplete((progress, restartFailure) -> {
                                if (restartFailure != null) {
                                    logger.at(Level.WARNING).log(
                                            "Population reconciliation restart failed after captured-item source recovery."
                                    );
                                }
                            });
                });
    }

    @Nullable
    private CompletableFuture<Boolean> recoverOne(
            Player player,
            CompanionPopulationOperationRecord operation,
            CompanionPopulationRepository repository
    ) {
        CompanionSpawnSourceFinalizationContext.Descriptor descriptor = descriptor(operation);
        if (descriptor == null || descriptor.playerUuid() == null
                || !descriptor.playerUuid().equals(player.getUuid())) {
            return null;
        }
        ItemContainer hotbar = hotbar(player);
        Integer sourceSlot = resolveSourceSlot(hotbar, descriptor);
        if (sourceSlot == null) {
            return null;
        }
        ItemStack sourceItem = hotbar.getItemStack(sourceSlot.shortValue());
        ItemStack replacement = replacement(sourceItem);
        UUID plannedNpcUuid = plannedNpcUuid(operation.targetContextJson());
        if (replacement == null || plannedNpcUuid == null
                || !hasExpectedLiveTarget(player, plannedNpcUuid)) {
            return null;
        }
        SpawnerSourceItemTransaction source = new SpawnerSourceItemTransaction(
                new SpawnerPlayerInventoryService(),
                player,
                descriptor.hotbarSlot(),
                sourceItem,
                logger,
                "Pending spawner source recovery"
        );
        if (!source.prepare(replacement)) {
            return null;
        }
        try {
            linkedNpcs.remapLinkedNpcRecordsAfterRespawn(
                    player, descriptor.sourceNpcUuid(), plannedNpcUuid
            );
            linkedNpcs.clearCapturedSnapshotIfPresent(descriptor.sourceNpcUuid());
            if (coops != null) {
                coops.clearCoopSnapshot(descriptor.sourceNpcUuid());
            }
            source.commit();
        } catch (RuntimeException | LinkageError failure) {
            source.compensate();
            return null;
        }
        PersistenceWriteQueue.WriteSubmission<Boolean> close =
                repository.completeSourceFinalizationAsync(operation.operationId());
        return close.completion().thenApply(outcome -> {
            boolean committed = outcome.isCommitted() && Boolean.TRUE.equals(outcome.value());
            if (committed) {
                logger.at(Level.INFO).log(
                        "Recovered pending captured-item source for companion "
                                + descriptor.sourceNpcUuid() + "."
                );
            }
            return committed;
        });
    }

    @Nullable
    private CompanionSpawnSourceFinalizationContext.Descriptor descriptor(
            CompanionPopulationOperationRecord operation
    ) {
        if (operation == null
                || operation.state() != CompanionPopulationOperationRecord.State.APPLIED) {
            return null;
        }
        try {
            CompanionSpawnSourceFinalizationContext.Descriptor descriptor =
                    CompanionSpawnSourceFinalizationContext.descriptor(
                            operation.targetContextJson()
                    );
            return descriptor != null
                    && descriptor.kind() == CompanionSpawnSourceFinalizationContext.Kind.SPAWNER_ITEM
                    ? descriptor : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Nullable
    private Integer resolveSourceSlot(
            @Nullable ItemContainer hotbar,
            CompanionSpawnSourceFinalizationContext.Descriptor descriptor
    ) {
        String fingerprint = descriptor.expectedFingerprint();
        if (hotbar == null || fingerprint == null) {
            return null;
        }
        return SpawnerSourceSlotResolver.resolveMatching(
                hotbar.getCapacity(),
                slot -> hotbar.getItemStack((short) slot),
                stack -> stack != null && !stack.isEmpty()
                        && fingerprint.equals(SpawnerSourceFingerprint.of(stack)),
                descriptor.hotbarSlot()
        );
    }

    @Nullable
    private ItemStack replacement(@Nullable ItemStack source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        ItemFeatureConfig config = registry.get(source.getItemId());
        String emptyItemId = metadata.resolveEmptyItemId(source.getItemId());
        if (config == null || !config.isSpawnerEnabled()
                || emptyItemId == null || emptyItemId.isBlank()) {
            return null;
        }
        ItemStack empty = metadata.swapItemId(source, emptyItemId);
        empty = metadata.clearCapturedMetadata(empty);
        return metadata.applyCooldown(
                empty,
                TameworkMetadataKeys.SPAWN_COOLDOWN_UNTIL,
                config.getSpawnCooldownMs()
        );
    }

    @Nullable
    private static ItemContainer hotbar(Player player) {
        Inventory inventory = player == null ? null : player.getInventory();
        return inventory == null ? null : inventory.getHotbar();
    }

    @Nullable
    private static UUID plannedNpcUuid(@Nullable String contextJson) {
        try {
            JsonElement parsed = JsonParser.parseString(contextJson);
            JsonObject object = parsed != null && parsed.isJsonObject()
                    ? parsed.getAsJsonObject() : null;
            JsonElement value = object == null ? null : object.get("plannedNpcUuid");
            return value == null || value.isJsonNull()
                    ? null : UUID.fromString(value.getAsString());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean successful(CompletableFuture<Boolean> future) {
        try {
            return Boolean.TRUE.equals(future.join());
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean hasExpectedLiveTarget(Player player, UUID plannedNpcUuid) {
        World world = player == null ? null : player.getWorld();
        Store<EntityStore> store = world == null || world.getEntityStore() == null
                ? null : world.getEntityStore().getStore();
        Ref<EntityStore> ref = world == null ? null : world.getEntityRef(plannedNpcUuid);
        if (store == null || ref == null || !ref.isValid()
                || TameworkOwnerComponent.getComponentType() == null) {
            return false;
        }
        TameworkOwnerComponent owner = store.getComponent(
                ref, TameworkOwnerComponent.getComponentType()
        );
        return owner != null && player.getUuid().equals(owner.getOwnerId());
    }

    private static List<CompanionPopulationOperationRecord> loadOperations(
            CompanionPopulationRepository repository
    ) {
        try {
            return repository.loadNonterminalOperations();
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }
}
