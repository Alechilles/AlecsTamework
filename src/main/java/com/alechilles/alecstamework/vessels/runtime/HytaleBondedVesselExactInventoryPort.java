package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.api.BondedVesselSourceItemEvidence;
import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.sqlite.BondedVesselBindingRecord;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselItemFingerprintCodec.VesselItemMetadata;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactCasRequest;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactCasResult;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactCasStatus;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactInventoryPort;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactItemRead;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ExactReadStatus;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.HeldSlotLocator;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshot;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.HeldSlotSnapshotStatus;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.PortReadiness;
import com.alechilles.alecstamework.vessels.runtime.ProductionBondedVesselEvidenceAuthority.ReplacementProjection;
import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Exact online-player hotbar authority for bonded vessels.
 *
 * <p>Hytale does not expose a monotonic container revision. A vessel's durable binding generation
 * is the equivalent restart-safe fence: every accepted transition increments it, and the complete
 * canonical metadata fingerprint is compared at the exact holder/path/slot before Hytale performs
 * its atomic metadata CAS. Vessel stacks must have quantity and asset max-stack equal to one so
 * Hytale's quantity-insensitive stackability comparison cannot hide an ambiguous source.</p>
 */
public final class HytaleBondedVesselExactInventoryPort implements ExactInventoryPort {
    private static final String HOTBAR = BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH;
    private final Universe universe;
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();
    private final BondedVesselHeldSlotEvidenceFactory evidenceFactory =
            new BondedVesselHeldSlotEvidenceFactory(fingerprints);
    private final Gson gson = new Gson();

    public HytaleBondedVesselExactInventoryPort(@Nonnull Universe universe) {
        this.universe = Objects.requireNonNull(universe, "universe");
    }

    @Nonnull
    @Override
    public CompletionStage<HeldSlotSnapshot> snapshotHeldSlot(@Nonnull HeldSlotLocator locator) {
        Objects.requireNonNull(locator, "locator");
        if (!canonical(locator.actorUuid(), locator.holderEvidenceId(), locator.containerPath())) {
            return CompletableFuture.completedFuture(snapshot(
                    HeldSlotSnapshotStatus.AMBIGUOUS, "held-slot-locator-not-canonical",
                    locator.inventorySlot(), -1L, null));
        }
        return dispatch(locator.actorUuid(), locator.inventorySlot(), (stack, slot) -> {
            if (stack == null || stack.isEmpty()) {
                return snapshot(HeldSlotSnapshotStatus.NOT_FOUND, "held-slot-empty",
                        slot, -1L, null);
            }
            MetadataRead metadata = readMetadata(stack);
            if (metadata.status() == MetadataStatus.ABSENT) {
                return snapshot(HeldSlotSnapshotStatus.FOUND, "held-slot-not-bonded",
                        slot, 0L, null);
            }
            if (metadata.status() != MetadataStatus.EXACT || !singleVessel(stack)) {
                return snapshot(HeldSlotSnapshotStatus.AMBIGUOUS,
                        "held-slot-vessel-metadata-ambiguous", slot, -1L, null);
            }
            return snapshot(HeldSlotSnapshotStatus.FOUND, "held-slot-snapshot-exact",
                    slot, metadata.metadata().generation(), metadata.metadata());
        }, snapshot(HeldSlotSnapshotStatus.UNAVAILABLE, "held-slot-player-unavailable",
                locator.inventorySlot(), -1L, null));
    }

    @Nonnull
    @Override
    public CompletionStage<ExactItemRead> readExact(
            @Nullable UUID actorUuid,
            @Nonnull BondedVesselSourceItemEvidence expected
    ) {
        Objects.requireNonNull(expected, "expected");
        UUID holder = holderUuid(expected.holderEvidenceId());
        if (holder == null || !HOTBAR.equals(expected.containerPath())
                || actorUuid != null && !actorUuid.equals(holder)) {
            return CompletableFuture.completedFuture(read(ExactReadStatus.AMBIGUOUS,
                    "exact-source-locator-not-canonical", expected, null));
        }
        return dispatch(holder, expected.inventorySlot(), (stack, slot) -> {
            if (stack == null || stack.isEmpty()) {
                return read(ExactReadStatus.NOT_FOUND, "exact-source-item-not-found",
                        expected, null);
            }
            MetadataRead metadata = readMetadata(stack);
            if (metadata.status() != MetadataStatus.EXACT || !singleVessel(stack)) {
                return read(ExactReadStatus.INCOMPLETE,
                        "exact-source-vessel-metadata-incomplete", expected, null);
            }
            BondedVesselSourceItemEvidence observed = evidenceFactory.create(
                    holder, slot, metadata.metadata().generation(), metadata.metadata());
            return read(ExactReadStatus.FOUND, "exact-source-item-read", observed,
                    metadata.metadata());
        }, ExactItemRead.unavailable(expected));
    }

    @Nonnull
    @Override
    public CompletionStage<ExactCasResult> compareAndSet(@Nonnull ExactCasRequest request) {
        Objects.requireNonNull(request, "request");
        BondedVesselSourceItemEvidence expected = request.expected();
        UUID holder = holderUuid(expected.holderEvidenceId());
        if (holder == null || !HOTBAR.equals(expected.containerPath())) {
            return CompletableFuture.completedFuture(cas(ExactCasStatus.AMBIGUOUS,
                    "exact-cas-locator-not-canonical", null));
        }
        return dispatch(holder, expected.inventorySlot(), (stack, slot) ->
                compareAndSetOnWorldThread(holder, slot, stack, request),
                cas(ExactCasStatus.UNAVAILABLE, "exact-cas-player-unavailable", null));
    }

    @Nonnull
    @Override
    public PortReadiness readiness() {
        try {
            boolean ready = universe.getPlayers() != null
                    && InventoryComponent.Hotbar.getComponentType() != null;
            return ready ? new PortReadiness(true, true, true, false,
                    "exact-held-slot-generation-cas-ready")
                    : PortReadiness.unavailable("exact-held-slot-runtime-unavailable");
        } catch (RuntimeException | LinkageError failure) {
            return PortReadiness.unavailable("exact-held-slot-runtime-unavailable");
        }
    }

    private ExactCasResult compareAndSetOnWorldThread(
            UUID holder,
            int slot,
            @Nullable ItemStack current,
            ExactCasRequest request
    ) {
        if (current == null || current.isEmpty()) {
            return cas(ExactCasStatus.SOURCE_CHANGED, "exact-cas-source-missing", null);
        }
        MetadataRead currentMetadata = readMetadata(current);
        if (currentMetadata.status() != MetadataStatus.EXACT || !singleVessel(current)) {
            return cas(ExactCasStatus.AMBIGUOUS, "exact-cas-source-ambiguous", null);
        }
        BondedVesselSourceItemEvidence observed = evidenceFactory.create(holder, slot,
                currentMetadata.metadata().generation(), currentMetadata.metadata());
        ReplacementProjection replacement = request.replacement();
        if (matches(currentMetadata.metadata(), replacement)) {
            return cas(ExactCasStatus.ALREADY_REPLACED, "exact-cas-already-replaced",
                    evidenceJson(observed));
        }
        if (!observed.equals(request.expected())) {
            return cas(ExactCasStatus.SOURCE_CHANGED, "exact-cas-source-changed",
                    evidenceJson(observed));
        }
        ItemStack target = replacementStack(current, replacement);
        MetadataRead targetMetadataRead = readMetadata(target);
        if (targetMetadataRead.status() != MetadataStatus.EXACT
                || !fingerprints.fingerprint(targetMetadataRead.metadata()).equals(
                        replacement.fingerprint())) {
            return cas(ExactCasStatus.UNAVAILABLE,
                    "exact-cas-target-fingerprint-invalid", null);
        }
        if (!singleVessel(target)) {
            return cas(ExactCasStatus.UNAVAILABLE, "exact-cas-target-must-be-nonstackable", null);
        }
        ItemContainer hotbar = currentContainer(holder);
        if (hotbar == null) {
            return cas(ExactCasStatus.UNAVAILABLE, "exact-cas-hotbar-unavailable", null);
        }
        ItemStackSlotTransaction transaction = hotbar.replaceItemStackInSlot(
                (short) slot, current, target);
        if (transaction == null || !transaction.succeeded()
                || !current.equals(transaction.getSlotBefore())
                || !target.equals(transaction.getSlotAfter())) {
            return cas(ExactCasStatus.SOURCE_CHANGED, "exact-cas-compare-failed", null);
        }
        VesselItemMetadata targetMetadata = targetMetadataRead.metadata();
        BondedVesselSourceItemEvidence finalized = evidenceFactory.create(
                holder, slot, targetMetadata.generation(), targetMetadata);
        return cas(ExactCasStatus.REPLACED, "exact-cas-replaced", evidenceJson(finalized));
    }

    @Nullable
    private ItemContainer currentContainer(UUID holder) {
        PlayerRef player = universe.getPlayer(holder);
        if (player == null || !player.isValid()) return null;
        World world = universe.getWorld(player.getWorldUuid());
        if (world == null || world.getEntityStore() == null) return null;
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        Ref<EntityStore> reference = world.getEntityRef(holder);
        if (reference == null || !reference.isValid()) return null;
        InventoryComponent.Hotbar hotbar = store.getComponent(
                reference, InventoryComponent.Hotbar.getComponentType());
        return hotbar == null ? null : hotbar.getInventory();
    }

    private <T> CompletionStage<T> dispatch(
            UUID holder,
            int slot,
            SlotReader<T> reader,
            T unavailable
    ) {
        PlayerRef player = universe.getPlayer(holder);
        World world = player == null ? null : universe.getWorld(player.getWorldUuid());
        if (player == null || !player.isValid() || world == null || !world.isAlive()) {
            return CompletableFuture.completedFuture(unavailable);
        }
        CompletableFuture<T> completion = new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(world, () -> {
            try {
                ItemContainer hotbar = currentContainer(holder);
                ItemStack stack = hotbar == null || slot >= hotbar.getCapacity()
                        ? null : hotbar.getItemStack((short) slot);
                completion.complete(hotbar == null ? unavailable : reader.read(stack, slot));
            } catch (RuntimeException | LinkageError failure) {
                completion.complete(unavailable);
            }
        }, () -> completion.complete(unavailable));
        return completion;
    }

    private MetadataRead readMetadata(ItemStack stack) {
        String binding = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_BINDING_ID, Codec.STRING);
        String profile = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_PROFILE_ID, Codec.STRING);
        Long generation = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG);
        String config = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_CONFIG_ID, Codec.STRING);
        String state = stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_STATE, Codec.STRING);
        if (binding == null && profile == null && generation == null && config == null && state == null) {
            return new MetadataRead(MetadataStatus.ABSENT, null);
        }
        try {
            VesselItemMetadata metadata = new VesselItemMetadata(
                    stack.getItemId(), UUID.fromString(binding), profile, generation, config,
                    BondedVesselState.valueOf(state));
            return new MetadataRead(MetadataStatus.EXACT, metadata);
        } catch (RuntimeException failure) {
            return new MetadataRead(MetadataStatus.INVALID, null);
        }
    }

    private ItemStack replacementStack(ItemStack source, ReplacementProjection replacement) {
        ItemStack target = replacement.itemId().equals(source.getItemId())
                ? source
                : new ItemStack(replacement.itemId(), 1, source.getDurability(),
                        source.getMaxDurability(), source.getMetadata());
        target = target
                .withMetadata(TameworkMetadataKeys.VESSEL_BINDING_ID,
                        Codec.STRING, replacement.bindingId().toString().toLowerCase())
                .withMetadata(TameworkMetadataKeys.VESSEL_PROFILE_ID,
                        Codec.STRING, replacement.profileId())
                .withMetadata(TameworkMetadataKeys.VESSEL_GENERATION,
                        Codec.LONG, replacement.generation())
                .withMetadata(TameworkMetadataKeys.VESSEL_CONFIG_ID,
                        Codec.STRING, replacement.configId())
                .withMetadata(TameworkMetadataKeys.VESSEL_STATE,
                        Codec.STRING, replacement.state().name());
        return target;
    }

    private boolean singleVessel(ItemStack stack) {
        Item item = stack.getItem();
        return stack.getQuantity() == 1 && item != null && item.getMaxStack() == 1;
    }

    private boolean matches(VesselItemMetadata current, ReplacementProjection replacement) {
        return current.itemId().equals(replacement.itemId())
                && current.bindingId().equals(replacement.bindingId())
                && current.profileId().equals(replacement.profileId())
                && current.generation() == replacement.generation()
                && current.configId().equals(replacement.configId())
                && current.state().name().equals(replacement.state().name())
                && fingerprints.fingerprint(current).equals(replacement.fingerprint());
    }

    private String evidenceJson(BondedVesselSourceItemEvidence evidence) {
        return gson.toJson(evidence);
    }

    private static boolean canonical(UUID actor, String holder, String path) {
        return BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(actor).equals(holder)
                && HOTBAR.equals(path);
    }

    @Nullable
    private static UUID holderUuid(String holder) {
        if (holder == null || !holder.startsWith("player:")) return null;
        try {
            UUID uuid = UUID.fromString(holder.substring("player:".length()));
            return BondedVesselHeldSlotEvidenceFactory.holderEvidenceId(uuid).equals(holder)
                    ? uuid : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static HeldSlotSnapshot snapshot(HeldSlotSnapshotStatus status, String reason,
                                             int slot, long revision,
                                             @Nullable VesselItemMetadata metadata) {
        return new HeldSlotSnapshot(status, reason, slot, revision, metadata);
    }

    private static ExactItemRead read(ExactReadStatus status, String reason,
                                      BondedVesselSourceItemEvidence evidence,
                                      @Nullable VesselItemMetadata metadata) {
        return new ExactItemRead(status, reason, evidence,
                metadata == null ? null : metadata.bindingId(),
                metadata == null ? null : metadata.profileId(),
                metadata == null ? -1L : metadata.generation());
    }

    private static ExactCasResult cas(ExactCasStatus status, String reason,
                                      @Nullable String evidenceJson) {
        return new ExactCasResult(status, reason, evidenceJson);
    }

    private enum MetadataStatus { ABSENT, EXACT, INVALID }

    private record MetadataRead(MetadataStatus status, @Nullable VesselItemMetadata metadata) {
    }

    @FunctionalInterface
    private interface SlotReader<T> {
        T read(@Nullable ItemStack stack, int slot);
    }
}
