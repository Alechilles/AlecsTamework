package com.alechilles.alecstamework.vessels.runtime;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.runtime.dispatch.LeaseBoundWorldDispatcher;
import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Restart-safe exact hotbar finalizer for APPLIED generation-one capture bindings. */
public final class HytaleBondedVesselInitialSourceFinalizer
        implements BondedVesselInitialBindingService.SourceFinalizer {
    private final Universe universe;
    private final Gson gson = new Gson();
    private final BondedVesselItemFingerprintCodec fingerprints =
            new BondedVesselItemFingerprintCodec();

    public HytaleBondedVesselInitialSourceFinalizer(@Nonnull Universe universe) {
        this.universe = Objects.requireNonNull(universe, "universe");
    }

    @Nonnull
    @Override
    public CompletionStage<BondedVesselInitialBindingService.SourceFinalization> finalizeSource(
            @Nonnull BondedVesselInitialBindingService.Request request) {
        Source source = decode(request);
        if (source == null) return CompletableFuture.completedFuture(result(
                BondedVesselInitialBindingService.SourceStatus.SOURCE_CHANGED,
                "initial-binding-source-context-invalid"));
        PlayerRef player = universe.getPlayer(source.holder());
        World world = player == null ? null : universe.getWorld(player.getWorldUuid());
        if (player == null || !player.isValid() || world == null || !world.isAlive()) {
            return CompletableFuture.completedFuture(result(
                    BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                    "initial-binding-owner-offline"));
        }
        CompletableFuture<BondedVesselInitialBindingService.SourceFinalization> completion =
                new CompletableFuture<>();
        LeaseBoundWorldDispatcher.execute(world,
                () -> completion.complete(finalizeOnWorld(world, source, request)),
                () -> completion.complete(result(
                        BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                        "initial-binding-world-dispatch-rejected")));
        return completion;
    }

    private BondedVesselInitialBindingService.SourceFinalization finalizeOnWorld(
            World world, Source source, BondedVesselInitialBindingService.Request request) {
        ItemContainer hotbar = hotbar(world, source.holder());
        if (hotbar == null || source.slot() < 0 || source.slot() >= hotbar.getCapacity()) {
            return result(BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                    "initial-binding-hotbar-unavailable");
        }
        ItemStack current = hotbar.getItemStack((short) source.slot());
        if (matchesTarget(current, request)) {
            return result(BondedVesselInitialBindingService.SourceStatus.ALREADY_REPLACED,
                    "initial-binding-source-already-replaced");
        }
        if (current == null || current.isEmpty() || current.getQuantity() != 1
                || !request.sourceItemId().equals(current.getItemId())
                || hasVesselMetadata(current)) {
            return result(BondedVesselInitialBindingService.SourceStatus.SOURCE_CHANGED,
                    "initial-binding-source-changed");
        }
        ItemStack target = new ItemStack(request.targetItemId(), 1, current.getDurability(),
                current.getMaxDurability(), current.getMetadata())
                .withMetadata(TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        Codec.STRING, request.profileId())
                .withMetadata(TameworkMetadataKeys.CAPTURED, Codec.BOOLEAN, true)
                .withMetadata(TameworkMetadataKeys.VESSEL_BINDING_ID,
                        Codec.STRING, request.bindingId().toString().toLowerCase())
                .withMetadata(TameworkMetadataKeys.VESSEL_PROFILE_ID,
                        Codec.STRING, request.profileId())
                .withMetadata(TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG, 1L)
                .withMetadata(TameworkMetadataKeys.VESSEL_CONFIG_ID,
                        Codec.STRING, request.configId())
                .withMetadata(TameworkMetadataKeys.VESSEL_STATE,
                        Codec.STRING, com.alechilles.alecstamework.api.BondedVesselState.STORED.name());
        if (!matchesTarget(target, request)) {
            return result(BondedVesselInitialBindingService.SourceStatus.INDETERMINATE,
                    "initial-binding-target-fingerprint-invalid");
        }
        ItemStackSlotTransaction transaction = hotbar.replaceItemStackInSlot(
                (short) source.slot(), current, target);
        return transaction != null && transaction.succeeded()
                && current.equals(transaction.getSlotBefore())
                && target.equals(transaction.getSlotAfter())
                ? result(BondedVesselInitialBindingService.SourceStatus.REPLACED,
                "initial-binding-source-recovered")
                : result(BondedVesselInitialBindingService.SourceStatus.SOURCE_CHANGED,
                "initial-binding-source-compare-failed");
    }

    @Nullable
    private ItemContainer hotbar(World world, UUID holder) {
        if (world.getEntityStore() == null) return null;
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        Ref<EntityStore> ref = world.getEntityRef(holder);
        if (ref == null || !ref.isValid()
                || InventoryComponent.Hotbar.getComponentType() == null) return null;
        InventoryComponent.Hotbar inventory = store.getComponent(
                ref, InventoryComponent.Hotbar.getComponentType());
        return inventory == null ? null : inventory.getInventory();
    }

    private boolean matchesTarget(
            @Nullable ItemStack stack,
            BondedVesselInitialBindingService.Request request) {
        if (stack == null || stack.isEmpty() || !request.targetItemId().equals(stack.getItemId())) {
            return false;
        }
        try {
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
            BondedVesselItemFingerprintCodec.VesselItemMetadata metadata =
                    new BondedVesselItemFingerprintCodec.VesselItemMetadata(
                            stack.getItemId(), UUID.fromString(binding), profile, generation,
                            config, com.alechilles.alecstamework.api.BondedVesselState.valueOf(state));
            return request.targetFingerprint().equals(fingerprints.fingerprint(metadata));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean hasVesselMetadata(ItemStack stack) {
        return stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_BINDING_ID, Codec.STRING) != null
                || stack.getFromMetadataOrNull(
                TameworkMetadataKeys.VESSEL_GENERATION, Codec.LONG) != null;
    }

    @Nullable
    private Source decode(BondedVesselInitialBindingService.Request request) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> context = gson.fromJson(
                    request.sourceContextJson(), Map.class);
            String holder = (String) context.get("sourceHolderEvidenceId");
            String path = (String) context.get("sourceContainerPath");
            String fingerprint = (String) context.get("sourceItemFingerprint");
            if (holder == null || !holder.startsWith("player:")
                    || !BondedVesselHeldSlotEvidenceFactory.HOTBAR_CONTAINER_PATH.equals(path)
                    || !request.sourceFingerprint().equals(fingerprint)) return null;
            UUID uuid = UUID.fromString(holder.substring("player:".length()));
            int slot = ((Number) context.get("sourceInventorySlot")).intValue();
            return new Source(uuid, slot);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static BondedVesselInitialBindingService.SourceFinalization result(
            BondedVesselInitialBindingService.SourceStatus status, String reason) {
        return new BondedVesselInitialBindingService.SourceFinalization(status, reason);
    }

    private record Source(@Nonnull UUID holder, int slot) { }
}
