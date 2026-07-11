package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.RetirementReady;
import com.alechilles.alecstamework.items.ManagedCoopCapturedItemEnvelopeCodec.Envelope;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.FeedbackSink;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementAction;
import com.alechilles.alecstamework.items.ManagedCoopItemIntakeHandler.ItemRetirementReceipt;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * World-thread inventory/feedback session for one managed-coop captured-item interaction.
 *
 * <p>Only stable IDs, the world executor, and the original slot are retained. Player, inventory,
 * and item state are re-resolved inside every queued callback.</p>
 */
public final class HytaleManagedCoopItemInteractionSession
        implements ItemRetirementAction, FeedbackSink {
    public static final String RETIREMENT_RECEIPT_METADATA_KEY =
            ManagedCoopItemRetirementReceiptCodec.METADATA_KEY;

    private final World world;
    private final UUID playerUuid;
    private final short hotbarSlot;
    private final String itemId;
    private final ManagedCoopCapturedItemEnvelopeCodec envelopes;
    private final ManagedCoopItemRetirementReceiptCodec receipts =
            new ManagedCoopItemRetirementReceiptCodec();

    public HytaleManagedCoopItemInteractionSession(@Nonnull World world,
                                                   @Nonnull UUID playerUuid,
                                                   short hotbarSlot,
                                                   @Nonnull String itemId) {
        this(world, playerUuid, hotbarSlot, itemId, new ManagedCoopCapturedItemEnvelopeCodec());
    }

    HytaleManagedCoopItemInteractionSession(@Nonnull World world,
                                            @Nonnull UUID playerUuid,
                                            short hotbarSlot,
                                            @Nonnull String itemId,
                                            @Nonnull ManagedCoopCapturedItemEnvelopeCodec envelopes) {
        this.world = Objects.requireNonNull(world, "world");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.itemId = requireText(itemId, "itemId");
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        if (hotbarSlot < 0) {
            throw new IllegalArgumentException("hotbarSlot must not be negative");
        }
        this.hotbarSlot = hotbarSlot;
    }

    /** Replaces the exact filled item with an empty crate carrying an operation receipt. */
    @Nonnull
    @Override
    public CompletionStage<ItemRetirementReceipt> retire(@Nonnull RetirementReady ready,
                                                         @Nonnull Envelope envelope) {
        Objects.requireNonNull(ready, "ready");
        Objects.requireNonNull(envelope, "envelope");
        CompletableFuture<ItemRetirementReceipt> completion = new CompletableFuture<>();
        enqueue(completion, () -> retireOnWorldThread(ready, envelope, completion));
        return completion;
    }

    @Override
    public void send(@Nonnull String message) {
        String copied = requireText(message, "message");
        try {
            world.execute(() -> sendOnWorldThread(copied));
        } catch (RuntimeException ignored) {
            // Feedback is best-effort and never changes transaction state.
        }
    }

    private void retireOnWorldThread(RetirementReady ready,
                                     Envelope envelope,
                                     CompletableFuture<ItemRetirementReceipt> completion) {
        try {
            Store<EntityStore> store = entityStore();
            ItemContainer hotbar = resolveHotbar(store);
            ItemStack current = hotbar != null ? hotbar.getItemStack(hotbarSlot) : null;
            if (!matchesFilledItem(current, envelope)) {
                completion.completeExceptionally(
                        new IllegalStateException("managed_coop_item_changed_before_retirement"));
                return;
            }
            String receiptJson = receipts.encode(ready.operationId(), envelope.fingerprint());
            ItemStack retired = current.withMetadata(null).withMetadata(
                    RETIREMENT_RECEIPT_METADATA_KEY, Codec.STRING, receiptJson);
            if (!hotbar.replaceItemStackInSlot(hotbarSlot, current, retired).succeeded()) {
                completion.completeExceptionally(
                        new IllegalStateException("managed_coop_item_retirement_replace_failed"));
                return;
            }
            completion.complete(new ItemRetirementReceipt(
                    envelope.fingerprint(),
                    ready.operationId(),
                    () -> cleanupReceipt(receiptJson)
            ));
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
    }

    @Nonnull
    private CompletionStage<Boolean> cleanupReceipt(String expectedReceipt) {
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        enqueue(completion, () -> cleanupOnWorldThread(expectedReceipt, completion));
        return completion;
    }

    private void cleanupOnWorldThread(String expectedReceipt,
                                      CompletableFuture<Boolean> completion) {
        try {
            ItemContainer hotbar = resolveHotbar(entityStore());
            ItemStack current = hotbar != null ? hotbar.getItemStack(hotbarSlot) : null;
            if (current == null || !itemId.equals(current.getItemId())) {
                completion.complete(false);
                return;
            }
            String receipt = current.getFromMetadataOrNull(
                    RETIREMENT_RECEIPT_METADATA_KEY, Codec.STRING);
            if (receipt == null) {
                completion.complete(true);
                return;
            }
            if (!expectedReceipt.equals(receipt)) {
                completion.complete(false);
                return;
            }
            completion.complete(hotbar.replaceItemStackInSlot(
                    hotbarSlot, current, current.withMetadata(null)).succeeded());
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
    }

    private boolean matchesFilledItem(@Nullable ItemStack current, Envelope expected) {
        if (current == null || !itemId.equals(current.getItemId())) {
            return false;
        }
        String raw = current.getFromMetadataOrNull(
                ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY, Codec.STRING);
        ManagedCoopCapturedItemEnvelopeCodec.DecodeOutcome decoded = envelopes.decode(itemId, raw);
        return decoded.found()
                && decoded.envelope() != null
                && decoded.envelope().fingerprint().equals(expected.fingerprint());
    }

    @Nullable
    private ItemContainer resolveHotbar(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        InventoryComponent.Hotbar hotbar = store.getComponent(
                playerRef, InventoryComponent.Hotbar.getComponentType());
        return hotbar != null ? hotbar.getInventory() : null;
    }

    private void sendOnWorldThread(String message) {
        Store<EntityStore> store = entityStore();
        Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return;
        }
        Player player = store.getComponent(playerRef, Player.getComponentType());
        PlayerRef network = player != null ? player.getPlayerRef() : null;
        if (network != null) {
            network.sendMessage(Message.raw(message));
        }
    }

    @Nullable
    private Store<EntityStore> entityStore() {
        if (world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        store.assertThread();
        return store;
    }

    private <T> void enqueue(CompletableFuture<T> completion, Runnable action) {
        try {
            world.execute(action);
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
    }

    @Nonnull
    private static String requireText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
