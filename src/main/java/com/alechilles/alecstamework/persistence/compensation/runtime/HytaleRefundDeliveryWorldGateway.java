package com.alechilles.alecstamework.persistence.compensation.runtime;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.compensation.RefundClaim;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Current-world Hytale adapter for the generic receipt-first refund state machine. */
final class HytaleRefundDeliveryWorldGateway
        implements HytaleWorldOperationGateway<RefundClaim> {
    private final ReceiptFirstRefundDelivery delivery;

    HytaleRefundDeliveryWorldGateway() {
        this(new ReceiptFirstRefundDelivery());
    }

    HytaleRefundDeliveryWorldGateway(
            @Nonnull ReceiptFirstRefundDelivery delivery
    ) {
        if (delivery == null) {
            throw new IllegalArgumentException(
                    "Refund delivery state machine is required"
            );
        }
        this.delivery = delivery;
    }

    @Override
    @Nonnull
    public LiveOperationResult applyOrResolve(
            @Nonnull World world,
            @Nonnull Store<EntityStore> store,
            @Nonnull RefundClaim claim,
            @Nonnull OperationEnvelope operation
    ) {
        if (world == null || store == null || claim == null) {
            return LiveOperationResult.retryable(
                    "refund_recipient_unavailable",
                    null
            );
        }
        try {
            store.assertThread();
            Ref<EntityStore> recipient =
                    resolveRecipient(world, store, claim);
            CombinedItemContainer inventory = recipient == null
                    ? null
                    : InventoryComponent.getCombined(
                            store,
                            recipient,
                            InventoryComponent.BACKPACK_STORAGE_HOTBAR
                    );
            return inventory == null
                    ? LiveOperationResult.retryable(
                            "refund_recipient_unavailable",
                            null
                    )
                    : delivery.applyOrResolve(
                            claim,
                            new HytaleReceiptInventory(inventory)
                    );
        } catch (RuntimeException | LinkageError failure) {
            return LiveOperationResult.unknown(
                    "refund_world_access_failed",
                    failure
            );
        }
    }

    private Ref<EntityStore> resolveRecipient(
            World world,
            Store<EntityStore> store,
            RefundClaim claim
    ) {
        Ref<EntityStore> recipientRef =
                world.getEntityRef(claim.recipientUuid());
        if (recipientRef == null || !recipientRef.isValid()
                || recipientRef.getStore() != store
                || Player.getComponentType() == null) {
            return null;
        }
        Player player = store.getComponent(
                recipientRef,
                Player.getComponentType()
        );
        return player == null ? null : recipientRef;
    }

    private static final class HytaleReceiptInventory
            implements ReceiptFirstRefundDelivery.ReceiptInventory {
        private final CombinedItemContainer inventory;

        private HytaleReceiptInventory(
                CombinedItemContainer inventory
        ) {
            this.inventory = inventory;
        }

        @Override
        @Nonnull
        public ReceiptFirstRefundDelivery.ReceiptObservation observe(
                @Nonnull String expectedItemId,
                @Nonnull String receipt
        ) {
            int quantity = 0;
            boolean conflicting = false;
            for (short slot = 0; slot < inventory.getCapacity(); slot++) {
                ItemStack stack = inventory.getItemStack(slot);
                if (!carries(stack, receipt)) {
                    continue;
                }
                if (!expectedItemId.equals(stack.getItemId())) {
                    conflicting = true;
                    continue;
                }
                try {
                    quantity = Math.addExact(
                            quantity,
                            stack.getQuantity()
                    );
                } catch (ArithmeticException overflow) {
                    conflicting = true;
                }
            }
            return ReceiptFirstRefundDelivery.ReceiptObservation.readable(
                    quantity,
                    conflicting
            );
        }

        @Override
        @Nonnull
        public ReceiptFirstRefundDelivery.AddResult add(
                @Nonnull String itemId,
                int quantity,
                @Nonnull String receipt
        ) {
            ItemStack stack = new ItemStack(itemId, quantity)
                    .withMetadata(
                            TameworkMetadataKeys
                                    .PERSISTENCE_REFUND_RECEIPT,
                            Codec.STRING,
                            receipt
                    );
            ItemStackTransaction transaction = inventory.addItemStack(
                    stack,
                    true,
                    false,
                    true
            );
            return transaction != null
                    && transaction.succeeded()
                    && ItemStack.isEmpty(transaction.getRemainder())
                    ? ReceiptFirstRefundDelivery.AddResult.APPLIED
                    : ReceiptFirstRefundDelivery.AddResult.REJECTED;
        }

        private boolean carries(ItemStack stack, String receipt) {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            String stored = stack.getFromMetadataOrNull(
                    TameworkMetadataKeys.PERSISTENCE_REFUND_RECEIPT,
                    Codec.STRING
            );
            return receipt.equals(stored);
        }
    }
}
