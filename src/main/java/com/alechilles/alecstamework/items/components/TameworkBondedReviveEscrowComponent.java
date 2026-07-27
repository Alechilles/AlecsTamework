package com.alechilles.alecstamework.items.components;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Hidden player inventory containing only one bonded-revival payment.
 *
 * <p>The escrow is deliberately excluded from Hytale's normal player
 * inventory combinations. Its contents are operation-specific evidence and
 * are saved in the same player document as the source inventories.</p>
 */
public final class TameworkBondedReviveEscrowComponent
        extends InventoryComponent {
    public static final BuilderCodec<TameworkBondedReviveEscrowComponent> CODEC =
            BuilderCodec.builder(
                    TameworkBondedReviveEscrowComponent.class,
                    TameworkBondedReviveEscrowComponent::new,
                    InventoryComponent.CODEC
            ).<String>append(
                    new KeyedCodec<>("OperationId", Codec.STRING),
                    TameworkBondedReviveEscrowComponent::setOperationId,
                    TameworkBondedReviveEscrowComponent::operationId
            ).add().<String>append(
                    new KeyedCodec<>("ItemId", Codec.STRING),
                    TameworkBondedReviveEscrowComponent::setItemId,
                    TameworkBondedReviveEscrowComponent::itemId
            ).add().<Integer>append(
                    new KeyedCodec<>("Quantity", Codec.INTEGER),
                    TameworkBondedReviveEscrowComponent::setQuantity,
                    TameworkBondedReviveEscrowComponent::quantity
            ).add().<String>append(
                    new KeyedCodec<>("Phase", Codec.STRING),
                    TameworkBondedReviveEscrowComponent::setPhaseName,
                    value -> value.phase.name()
            ).add().<Long>append(
                    new KeyedCodec<>("CreatedAtMs", Codec.LONG),
                    TameworkBondedReviveEscrowComponent::setCreatedAtMs,
                    TameworkBondedReviveEscrowComponent::createdAtMs
            ).add().build();

    /** Finite persisted phases for one operation-specific payment. */
    public enum Phase {
        STAGED,
        RESERVED,
        REFUNDED,
        COMMITTED,
        QUARANTINED
    }

    private String operationId = "uninitialized";
    private String itemId = "uninitialized";
    private int quantity = 1;
    private Phase phase = Phase.STAGED;
    private long createdAtMs;

    /** Codec constructor. New runtime escrows should use {@link #create}. */
    public TameworkBondedReviveEscrowComponent() {
        super();
    }

    private TameworkBondedReviveEscrowComponent(
            short capacity,
            String operationId,
            String itemId,
            int quantity,
            long createdAtMs
    ) {
        super(capacity);
        this.operationId = requireText(operationId, "operationId");
        this.itemId = requireText(itemId, "itemId");
        setQuantity(quantity);
        this.createdAtMs = createdAtMs;
    }

    /** Creates an empty staged escrow with enough hidden source slots. */
    @Nonnull
    public static TameworkBondedReviveEscrowComponent create(
            short capacity,
            @Nonnull String operationId,
            @Nonnull String itemId,
            int quantity,
            long createdAtMs
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Escrow capacity must be positive");
        }
        return new TameworkBondedReviveEscrowComponent(
                capacity, operationId, itemId, quantity, createdAtMs);
    }

    /** Returns whether this component belongs to the exact payment request. */
    public boolean matches(String operationId, String itemId, int quantity) {
        return this.quantity == quantity
                && this.operationId.equals(operationId)
                && this.itemId.equals(itemId);
    }

    /** Returns the quantity of uncontaminated requested items in escrow. */
    public int reservedQuantity() {
        int reserved = 0;
        for (short slot = 0; slot < getInventory().getCapacity(); slot++) {
            ItemStack stack = getInventory().getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;
            if (!itemId.equals(stack.getItemId())) return -1;
            try {
                reserved = Math.addExact(reserved, stack.getQuantity());
            } catch (ArithmeticException overflow) {
                return -1;
            }
        }
        return reserved;
    }

    /** True only for a complete, uncontaminated operation-specific charge. */
    public boolean hasExactReservedCharge() {
        return reservedQuantity() == quantity;
    }

    /** True only for an empty escrow whose disposition was durably decided. */
    public boolean isEmptyTerminal() {
        return (phase == Phase.COMMITTED || phase == Phase.REFUNDED)
                && reservedQuantity() == 0;
    }

    public String operationId() {
        return operationId;
    }

    public String itemId() {
        return itemId;
    }

    public int quantity() {
        return quantity;
    }

    public Phase phase() {
        return phase;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    /** Advances the live phase; the caller still owns its player save fence. */
    public void setPhase(@Nonnull Phase phase) {
        this.phase = Objects.requireNonNull(phase, "phase");
        markChanged();
    }

    @Override
    @Nonnull
    public TameworkBondedReviveEscrowComponent clone() {
        TameworkBondedReviveEscrowComponent copy =
                new TameworkBondedReviveEscrowComponent();
        copy.inventory = inventory.clone();
        copy.operationId = operationId;
        copy.itemId = itemId;
        copy.quantity = quantity;
        copy.phase = phase;
        copy.createdAtMs = createdAtMs;
        copy.registerChangeEvent();
        return copy;
    }

    private void setOperationId(String operationId) {
        this.operationId = requireText(operationId, "operationId");
    }

    private void setItemId(String itemId) {
        this.itemId = requireText(itemId, "itemId");
    }

    private void setQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Escrow quantity must be positive");
        }
        this.quantity = quantity;
    }

    private void setPhaseName(String phase) {
        try {
            this.phase = Phase.valueOf(requireText(phase, "phase"));
        } catch (IllegalArgumentException invalid) {
            this.phase = Phase.QUARANTINED;
        }
    }

    private void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
