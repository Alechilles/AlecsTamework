package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCaptureSourceEvidence.CapturedItemSource;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureRecoveryService.ReceiptGateway;
import com.alechilles.alecstamework.items.ManagedCoopItemCaptureRecoveryService.ReceiptResolution;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementReceipt;
import com.alechilles.alecstamework.items.ManagedCoopItemReceiptVerifier.Verification;
import com.alechilles.alecstamework.items.ManagedCoopItemReceiptVerifier.VerificationStatus;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.metadata.CapturedNPCMetadata;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Re-resolves and validates captured-item retirement receipts on the owning world thread. */
final class HytaleManagedCoopItemReceiptGateway implements ReceiptGateway {
    private final ManagedCoopItemReceiptVerifier verifier = new ManagedCoopItemReceiptVerifier();

    @Nonnull
    @Override
    public CompletionStage<ReceiptResolution> verify(@Nonnull RetirementReady ready,
                                                     @Nonnull CapturedItemSource source) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(source, "source");
        World world = resolveWorld(ready.authorityKey().worldName());
        if (world == null) {
            return CompletableFuture.completedFuture(
                    ReceiptResolution.waiting("item_receipt_world_unavailable"));
        }
        CompletableFuture<ReceiptResolution> completion = new CompletableFuture<>();
        try {
            world.execute(() -> verifyOnWorldThread(world, ready, source, completion));
        } catch (RuntimeException exception) {
            completion.complete(ReceiptResolution.waiting("item_receipt_world_not_accepting_tasks"));
        }
        return completion;
    }

    private void verifyOnWorldThread(World world,
                                     RetirementReady ready,
                                     CapturedItemSource source,
                                     CompletableFuture<ReceiptResolution> completion) {
        try {
            Store<EntityStore> store = entityStore(world);
            ItemContainer hotbar = resolveHotbar(world, store, source);
            if (hotbar == null) {
                completion.complete(ReceiptResolution.waiting(
                        "item_receipt_player_offline_or_inventory_unavailable"));
                return;
            }
            ItemStack current = hotbar.getItemStack(source.hotbarSlot());
            String rawReceipt = current != null ? current.getFromMetadataOrNull(
                    ManagedCoopItemRetirementReceiptCodec.METADATA_KEY, Codec.STRING) : null;
            Verification check = verifier.verify(
                    ready,
                    source,
                    current != null ? current.getItemId() : null,
                    current != null ? current.getQuantity() : 0,
                    rawReceipt,
                    hasEnvelope(current),
                    hasVanillaCapture(current)
            );
            completion.complete(toResolution(world, ready, source, rawReceipt, check));
        } catch (RuntimeException exception) {
            completion.complete(ReceiptResolution.waiting(
                    "item_receipt_world_read_failed:" + exceptionName(exception)));
        }
    }

    private ReceiptResolution toResolution(World world,
                                           RetirementReady ready,
                                           CapturedItemSource source,
                                           @Nullable String rawReceipt,
                                           Verification check) {
        if (check.status() == VerificationStatus.WAITING) {
            return ReceiptResolution.waiting(check.detail());
        }
        if (check.status() != VerificationStatus.VERIFIED || check.receipt() == null
                || rawReceipt == null) {
            return ReceiptResolution.conflict(
                    check.detail() != null ? check.detail() : "item_receipt_conflict");
        }
        return ReceiptResolution.verified(new ItemRetirementReceipt(
                check.receipt().itemFingerprint(),
                check.receipt().operationId(),
                () -> cleanup(world, ready, source, rawReceipt)
        ));
    }

    @Nonnull
    private CompletionStage<Boolean> cleanup(World world,
                                             RetirementReady ready,
                                             CapturedItemSource source,
                                             String expectedReceipt) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        try {
            world.execute(() -> cleanupOnWorldThread(
                    world, ready, source, expectedReceipt, completion));
        } catch (RuntimeException exception) {
            completion.complete(false);
        }
        return completion;
    }

    private void cleanupOnWorldThread(World world,
                                      RetirementReady ready,
                                      CapturedItemSource source,
                                      String expectedReceipt,
                                      CompletableFuture<Boolean> completion) {
        try {
            ItemContainer hotbar = resolveHotbar(world, entityStore(world), source);
            ItemStack current = hotbar != null ? hotbar.getItemStack(source.hotbarSlot()) : null;
            if (current == null || !source.itemId().equals(current.getItemId())) {
                completion.complete(false);
                return;
            }
            String raw = current.getFromMetadataOrNull(
                    ManagedCoopItemRetirementReceiptCodec.METADATA_KEY, Codec.STRING);
            Verification check = verifier.verify(
                    ready, source, current.getItemId(), current.getQuantity(), raw,
                    hasEnvelope(current), hasVanillaCapture(current));
            if (check.status() != VerificationStatus.VERIFIED
                    || !expectedReceipt.equals(raw)) {
                completion.complete(false);
                return;
            }
            completion.complete(hotbar.replaceItemStackInSlot(
                    source.hotbarSlot(), current, current.withMetadata(null)).succeeded());
        } catch (RuntimeException exception) {
            completion.complete(false);
        }
    }

    @Nullable
    private ItemContainer resolveHotbar(World world,
                                        @Nullable Store<EntityStore> store,
                                        CapturedItemSource source) {
        if (store == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(source.playerUuid());
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        InventoryComponent.Hotbar hotbar = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        return hotbar != null ? hotbar.getInventory() : null;
    }

    private boolean hasEnvelope(@Nullable ItemStack item) {
        return item != null && item.getFromMetadataOrNull(
                ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY, Codec.STRING) != null;
    }

    private boolean hasVanillaCapture(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        try {
            return item.getFromMetadataOrNull(
                    CapturedNPCMetadata.KEY, CapturedNPCMetadata.CODEC) != null;
        } catch (RuntimeException exception) {
            return item.getMetadata() != null
                    && item.getMetadata().containsKey(CapturedNPCMetadata.KEY);
        }
    }

    @Nullable
    private Store<EntityStore> entityStore(World world) {
        if (world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        return store;
    }

    @Nullable
    private World resolveWorld(String worldName) {
        Universe universe = Universe.get();
        return universe != null ? universe.getWorld(worldName) : null;
    }

    private String exceptionName(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }
}
