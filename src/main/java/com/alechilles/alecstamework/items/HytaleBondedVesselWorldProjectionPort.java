package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.ownership.BondedVesselUnifiedPopulationPort;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselOperationRecord;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselMutationAuthority;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import it.unimi.dsi.fastutil.Pair;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/** Lease-bound Hytale spawn/store projection authority for bonded vessels. */
public final class HytaleBondedVesselWorldProjectionPort
        implements ProductionBondedVesselMutationAuthority.WorldProjectionPort {
    private final OwnerPopulationRuntime ownerRuntime;
    private final BondedVesselUnifiedPopulationPort populations;
    private final SpawnerNpcProgressionMetadataService progression =
            new SpawnerNpcProgressionMetadataService();

    public HytaleBondedVesselWorldProjectionPort(
            @Nonnull OwnerPopulationRuntime ownerRuntime,
            @Nonnull BondedVesselUnifiedPopulationPort populations) {
        this.ownerRuntime = Objects.requireNonNull(ownerRuntime, "ownerRuntime");
        this.populations = Objects.requireNonNull(populations, "populations");
    }

    @Nonnull
    @Override
    public CompletionStage<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> apply(
            @Nonnull ProductionBondedVesselMutationAuthority.WorldMutationRequest request) {
        Objects.requireNonNull(request, "request");
        BondedVesselOperationRecord.Action action =
                request.populationRequest().operation().action();
        return switch (action) {
            case SUMMON -> summon(request);
            case STORE -> removeLive(request, true);
            case MARK_DEAD, MARK_LOST, RELEASE -> removeLive(request, false);
            case INITIAL_BIND, REPAIR, REISSUE -> CompletableFuture.completedFuture(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.APPLIED,
                    "bonded-vessel-world-projection-not-required", null, null,
                    request.populationRequest().binding().itemEvidenceJson()));
        };
    }

    @Nonnull
    @Override
    public ProductionBondedVesselMutationAuthority.WorldReadiness readiness() {
        boolean dispatch = Universe.get() != null;
        boolean role = NPCPlugin.get() != null
                && UUIDComponent.getComponentType() != null
                && TameworkProjectionIdentityComponent.getComponentType() != null;
        boolean recovery = ownerRuntime.loadedNpcIdentityIndex().isInitializationComplete();
        boolean ready = dispatch && role && recovery;
        return new ProductionBondedVesselMutationAuthority.WorldReadiness(
                dispatch, role, recovery, ready ? "bonded-vessel-world-ready"
                : !dispatch ? "bonded-vessel-universe-unavailable"
                : !role ? "bonded-vessel-role-projection-unavailable"
                : "bonded-vessel-world-evidence-recovery-incomplete");
    }

    private CompletionStage<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> summon(
            ProductionBondedVesselMutationAuthority.WorldMutationRequest request) {
        SourceContext source = sourceContext(request.populationRequest().operation());
        PopulationAdmissionLocation destination = source.destination();
        if (destination == null) {
            return completedDenied("bonded-vessel-summon-destination-missing");
        }
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(destination.worldName());
        if (world == null || !world.isAlive()) {
            return completedIndeterminate("bonded-vessel-summon-world-unavailable");
        }
        CompletableFuture<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> completion =
                new CompletableFuture<>();
        long chunkIndex = ChunkUtil.indexChunk(destination.chunkX(), destination.chunkZ());
        world.getChunkAsync(chunkIndex).whenComplete((chunk, failure) ->
                LeaseBoundWorldDispatcher.execute(world,
                        () -> summonOnWorld(request, source, destination, world, chunk,
                                failure, completion),
                        () -> completion.complete(receipt(
                                ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                                "bonded-vessel-summon-world-dispatch-rejected", null, null,
                                request.populationRequest().binding().itemEvidenceJson()))));
        return completion;
    }

    private void summonOnWorld(
            ProductionBondedVesselMutationAuthority.WorldMutationRequest request,
            SourceContext source,
            PopulationAdmissionLocation destination,
            World world,
            @Nullable WorldChunk chunk,
            @Nullable Throwable loadFailure,
            CompletableFuture<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> completion) {
        var population = request.populationRequest();
        UUID plannedNpcUuid = BondedVesselUnifiedPopulationPort.plannedNpcUuid(
                population.operation());
        BondedVesselBindingRecord.PhysicalLocation location =
                new BondedVesselBindingRecord.PhysicalLocation(destination.worldName(),
                        destination.chunkX(), destination.chunkZ());
        if (loadFailure != null || chunk == null || world.getEntityStore() == null) {
            completion.complete(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                    "bonded-vessel-summon-chunk-unavailable", null, null,
                    population.binding().itemEvidenceJson()));
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> existing = world.getEntityRef(plannedNpcUuid);
        if (exactProjection(existing, store, population.operation(), population.binding(),
                plannedNpcUuid)) {
            completion.complete(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.ALREADY_APPLIED,
                    "bonded-vessel-live-projection-already-present", plannedNpcUuid,
                    location, population.binding().itemEvidenceJson()));
            return;
        }
        NPCPlugin plugin = NPCPlugin.get();
        int roleIndex = plugin == null ? -1 : plugin.getIndex(population.profile().roleId());
        if (plugin == null || roleIndex < 0) {
            completion.complete(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.TERMINAL_DENIED,
                    "bonded-vessel-role-unavailable", null, null,
                    population.binding().itemEvidenceJson()));
            return;
        }
        int local = ChunkUtil.SIZE / 2;
        Vector3d position = new Vector3d(
                ChunkUtil.minBlock(destination.chunkX()) + local + 0.5D,
                chunk.getHeight(local, local) + 1.0D,
                ChunkUtil.minBlock(destination.chunkZ()) + local + 0.5D);
        Pair<Ref<EntityStore>, NPCEntity> spawned;
        try {
            TameworkProjectionIdentityComponent marker = marker(population.operation(),
                    population.binding());
            spawned = plugin.spawnEntity(store, roleIndex, position, new Rotation3f(), null,
                    (npc, holder, callbackStore) -> {
                        OwnerComponentMutationService.WriteResult write =
                                populations.writeSpawnHolder(
                                        request.claimedPopulation(), holder);
                        if (!write.applied()) throw new IllegalStateException(write.reason());
                        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                                TameworkProjectionIdentityComponent.getComponentType();
                        if (markerType == null) {
                            throw new IllegalStateException(
                                    "bonded-vessel-projection-marker-unavailable");
                        }
                        holder.putComponent(markerType, marker);
                    }, null);
        } catch (RuntimeException | LinkageError failure) {
            spawned = null;
        }
        if (spawned == null || !exactProjection(spawned.first(), store,
                population.operation(), population.binding(), plannedNpcUuid)) {
            if (spawned != null && spawned.second() != null) spawned.second().setToDespawn();
            completion.complete(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                    "bonded-vessel-summon-projection-failed", null, null,
                    population.binding().itemEvidenceJson()));
            return;
        }
        ItemStack vessel = heldVessel(world, source, population.binding(),
                population.operation().priorGeneration());
        restoreState(vessel, spawned.first(), spawned.second(), store, world);
        completion.complete(receipt(
                ProductionBondedVesselMutationAuthority.WorldMutationStatus.APPLIED,
                "bonded-vessel-live-projection-spawned", plannedNpcUuid, location,
                population.binding().itemEvidenceJson()));
    }

    private CompletionStage<ProductionBondedVesselMutationAuthority.WorldMutationReceipt>
    removeLive(ProductionBondedVesselMutationAuthority.WorldMutationRequest request,
               boolean snapshotItem) {
        var population = request.populationRequest();
        UUID npcUuid = population.binding().activeNpcUuid();
        BondedVesselBindingRecord.PhysicalLocation location =
                population.binding().activeLocation();
        if (npcUuid == null || location == null) {
            return CompletableFuture.completedFuture(receipt(
                    request.populationRequest().operation().action()
                            == BondedVesselOperationRecord.Action.STORE
                            ? ProductionBondedVesselMutationAuthority.WorldMutationStatus.TERMINAL_DENIED
                            : ProductionBondedVesselMutationAuthority.WorldMutationStatus.ALREADY_APPLIED,
                    "bonded-vessel-live-projection-absent", null, null,
                    population.binding().itemEvidenceJson()));
        }
        Universe universe = Universe.get();
        World world = universe == null ? null : universe.getWorld(location.worldName());
        if (world == null || !world.isAlive()) {
            return completedIndeterminate("bonded-vessel-live-world-unavailable");
        }
        CompletableFuture<ProductionBondedVesselMutationAuthority.WorldMutationReceipt> completion =
                new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(world, () -> {
            Store<EntityStore> store = world.getEntityStore() == null
                    ? null : world.getEntityStore().getStore();
            Ref<EntityStore> ref = store == null ? null : world.getEntityRef(npcUuid);
            if (!exactProjection(ref, store, population.operation(), population.binding(), npcUuid)) {
                completion.complete(receipt(
                        ProductionBondedVesselMutationAuthority.WorldMutationStatus.QUARANTINED,
                        "bonded-vessel-live-projection-identity-mismatch", npcUuid,
                        location, population.binding().itemEvidenceJson()));
                return;
            }
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                completion.complete(receipt(
                        ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                        "bonded-vessel-live-npc-unavailable", npcUuid, location,
                        population.binding().itemEvidenceJson()));
                return;
            }
            if (snapshotItem && !snapshotIntoHeldVessel(world, sourceContext(
                    population.operation()), population.binding(), ref, store, npcUuid)) {
                completion.complete(receipt(
                        ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                        "bonded-vessel-store-snapshot-failed", npcUuid, location,
                        population.binding().itemEvidenceJson()));
                return;
            }
            npc.setToDespawn();
            completion.complete(receipt(
                    ProductionBondedVesselMutationAuthority.WorldMutationStatus.APPLIED,
                    "bonded-vessel-live-projection-removed", null, null,
                    population.binding().itemEvidenceJson()));
        }, () -> completion.complete(receipt(
                ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                "bonded-vessel-remove-world-dispatch-rejected", npcUuid, location,
                population.binding().itemEvidenceJson())));
        return completion;
    }

    private boolean snapshotIntoHeldVessel(
            World world, SourceContext source, BondedVesselBindingRecord binding,
            Ref<EntityStore> npcRef, Store<EntityStore> store, UUID npcUuid) {
        ItemContainer hotbar = hotbar(world, source.actorUuid());
        if (hotbar == null || source.inventorySlot() < 0
                || source.inventorySlot() >= hotbar.getCapacity()) return false;
        ItemStack current = hotbar.getItemStack((short) source.inventorySlot());
        if (!matchesVessel(current, binding, binding.generation())) return false;
        ItemStack snapshot = progression.applyNpcProgressionMetadata(current, npcRef, store)
                .withMetadata(TameworkMetadataKeys.TARGET_UUID, Codec.UUID_STRING, npcUuid);
        ItemStackSlotTransaction transaction = hotbar.replaceItemStackInSlot(
                (short) source.inventorySlot(), current, snapshot);
        return transaction != null && transaction.succeeded()
                && snapshot.equals(transaction.getSlotAfter());
    }

    @Nullable
    private ItemStack heldVessel(World world, SourceContext source,
                                 BondedVesselBindingRecord binding, long generation) {
        ItemContainer hotbar = hotbar(world, source.actorUuid());
        if (hotbar == null || source.inventorySlot() < 0
                || source.inventorySlot() >= hotbar.getCapacity()) return null;
        ItemStack stack = hotbar.getItemStack((short) source.inventorySlot());
        return matchesVessel(stack, binding, generation) ? stack : null;
    }

    @Nullable
    private ItemContainer hotbar(World world, @Nullable UUID actorUuid) {
        if (actorUuid == null || world.getEntityStore() == null) return null;
        Ref<EntityStore> playerRef = world.getEntityRef(actorUuid);
        Store<EntityStore> store = world.getEntityStore().getStore();
        if (playerRef == null || !playerRef.isValid()
                || InventoryComponent.Hotbar.getComponentType() == null) return null;
        InventoryComponent.Hotbar inventory = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        return inventory == null ? null : inventory.getInventory();
    }

    private static boolean matchesVessel(@Nullable ItemStack stack,
                                         BondedVesselBindingRecord binding, long generation) {
        if (stack == null || stack.isEmpty()) return false;
        String bindingId = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_BINDING_ID, Codec.STRING);
        String profileId = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_PROFILE_ID, Codec.STRING);
        Long observedGeneration = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG);
        return binding.bindingId().equals(bindingId)
                && binding.profileId().equals(profileId)
                && observedGeneration != null && observedGeneration == generation;
    }

    private void restoreState(@Nullable ItemStack vessel, Ref<EntityStore> ref,
                              NPCEntity npc, Store<EntityStore> store, World world) {
        if (vessel == null) return;
        try {
            SpawnerNpcStateService state = new SpawnerNpcStateService();
            state.applyTamed(ref, Boolean.TRUE.equals(vessel.getFromMetadataOrNull(
                    TameworkMetadataKeys.TAMED, Codec.BOOLEAN)), world);
            state.applyCapturedName(vessel, ref, store);
            Tamework plugin = Tamework.getInstance();
            if (plugin != null) new SpawnerAttachmentService(plugin.getLogger())
                    .applyAttachments(vessel, ref, npc, store);
            progression.applyNpcProgressionFromItem(vessel, ref, store);
            CompanionStatModifierService.applyTraitModifiers(ref, store);
            CompanionLifeStageService.refreshLifeStage(ref, npc, store);
            CompanionLifeStageService.ensureGrowthTickScheduled(ref, npc, store);
            progression.applyNpcHealthFromItem(vessel, ref, store);
        } catch (RuntimeException | LinkageError ignored) {
            // Identity and population authority remain valid; reconciliation may reapply cosmetics.
        }
    }

    private static boolean exactProjection(
            @Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store,
            BondedVesselOperationRecord operation, BondedVesselBindingRecord binding,
            UUID npcUuid) {
        if (ref == null || !ref.isValid() || store == null) return false;
        try {
            UUIDComponent uuid = store.getComponent(ref, UUIDComponent.getComponentType());
            TameworkProjectionIdentityComponent marker = store.getComponent(ref,
                    TameworkProjectionIdentityComponent.getComponentType());
            return uuid != null && npcUuid.equals(uuid.getUuid()) && marker != null
                    && binding.profileId().equals(marker.getProfileId())
                    && TameworkProjectionIdentityComponent.KIND_BONDED_VESSEL.equals(
                            marker.getProjectionKind())
                    && binding.bindingId().equals(marker.getSlotKey())
                    && marker.getGeneration() == (operation.action()
                            == BondedVesselOperationRecord.Action.SUMMON
                            ? operation.candidateGeneration() : operation.priorGeneration());
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private static TameworkProjectionIdentityComponent marker(
            BondedVesselOperationRecord operation, BondedVesselBindingRecord binding) {
        return new TameworkProjectionIdentityComponent(
                binding.profileId(), operation.operationId(),
                TameworkProjectionIdentityComponent.KIND_BONDED_VESSEL,
                binding.bindingId(), binding.activeNpcUuid(), operation.candidateGeneration());
    }

    private static SourceContext sourceContext(BondedVesselOperationRecord operation) {
        try {
            JsonObject json = JsonParser.parseString(operation.sourceContextJson()).getAsJsonObject();
            String holder = json.get("sourceHolderEvidenceId").getAsString();
            UUID actor = holder.startsWith("player:")
                    ? UUID.fromString(holder.substring("player:".length())) : null;
            PopulationAdmissionLocation destination = json.has("destinationWorld")
                    && !json.get("destinationWorld").isJsonNull()
                    ? new PopulationAdmissionLocation(
                            json.get("destinationWorld").getAsString(),
                            json.get("destinationChunkX").getAsInt(),
                            json.get("destinationChunkZ").getAsInt()) : null;
            return new SourceContext(actor, json.get("sourceInventorySlot").getAsInt(),
                    destination);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("bonded-vessel-source-context-invalid", invalid);
        }
    }

    private static ProductionBondedVesselMutationAuthority.WorldMutationReceipt receipt(
            ProductionBondedVesselMutationAuthority.WorldMutationStatus status, String reason,
            @Nullable UUID npcUuid,
            @Nullable BondedVesselBindingRecord.PhysicalLocation location,
            @Nullable String evidence) {
        return new ProductionBondedVesselMutationAuthority.WorldMutationReceipt(
                status, reason, npcUuid, location, evidence);
    }

    private static CompletionStage<ProductionBondedVesselMutationAuthority.WorldMutationReceipt>
    completedDenied(String reason) {
        return CompletableFuture.completedFuture(receipt(
                ProductionBondedVesselMutationAuthority.WorldMutationStatus.TERMINAL_DENIED,
                reason, null, null, null));
    }

    private static CompletionStage<ProductionBondedVesselMutationAuthority.WorldMutationReceipt>
    completedIndeterminate(String reason) {
        return CompletableFuture.completedFuture(receipt(
                ProductionBondedVesselMutationAuthority.WorldMutationStatus.INDETERMINATE,
                reason, null, null, null));
    }

    private record SourceContext(@Nullable UUID actorUuid, int inventorySlot,
                                 @Nullable PopulationAdmissionLocation destination) {
    }
}
