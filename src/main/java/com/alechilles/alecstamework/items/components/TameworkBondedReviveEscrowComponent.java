package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final BuilderCodec<CostEntry> COST_CODEC =
            BuilderCodec.builder(CostEntry.class, CostEntry::new)
                    .<String>append(new KeyedCodec<>("ItemId", Codec.STRING),
                            CostEntry::setItemId, CostEntry::itemId)
                    .add().<Integer>append(new KeyedCodec<>("Quantity", Codec.INTEGER),
                            CostEntry::setQuantity, CostEntry::quantity)
                    .add().build();
    private static final ArrayCodec<CostEntry> COST_ARRAY_CODEC =
            new ArrayCodec<>(COST_CODEC, CostEntry[]::new);
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
            ).add().<CostEntry[]>append(
                    new KeyedCodec<>("Costs", COST_ARRAY_CODEC),
                    TameworkBondedReviveEscrowComponent::setCostEntries,
                    TameworkBondedReviveEscrowComponent::costEntries
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
        REFUNDING,
        REFUNDED,
        COMMITTED,
        QUARANTINED
    }

    private String operationId = "uninitialized";
    private String itemId = "uninitialized";
    private int quantity = 1;
    private List<BondedCompanionReviveCost> costs = List.of(
            new BondedCompanionReviveCost(itemId, quantity));
    private boolean recipeValid = true;
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
        setCosts(List.of(new BondedCompanionReviveCost(itemId, quantity)));
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

    /** Creates an empty escrow whose exact ordered recipe is frozen durably. */
    @Nonnull
    public static TameworkBondedReviveEscrowComponent create(
            short capacity,
            @Nonnull String operationId,
            @Nonnull List<BondedCompanionReviveCost> costs,
            long createdAtMs
    ) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Escrow capacity must be positive");
        }
        List<BondedCompanionReviveCost> validated = validateCosts(costs);
        TameworkBondedReviveEscrowComponent escrow =
                new TameworkBondedReviveEscrowComponent(
                        capacity, operationId, validated.getFirst().itemId(),
                        validated.getFirst().quantity(), createdAtMs);
        escrow.setCosts(validated);
        return escrow;
    }

    /** Returns whether this component belongs to the exact payment request. */
    public boolean matches(String operationId, String itemId, int quantity) {
        return recipeValid && costs.size() == 1 && this.quantity == quantity
                && this.operationId.equals(operationId)
                && this.itemId.equals(itemId);
    }

    /** Returns whether this component belongs to the exact ordered recipe. */
    public boolean matches(String operationId,
            List<BondedCompanionReviveCost> costs) {
        return recipeValid && this.operationId.equals(operationId)
                && this.costs.equals(validateCosts(costs));
    }

    /** Returns the quantity of uncontaminated requested items in escrow. */
    public int reservedQuantity() {
        if (reservedState() == ReservedState.INVALID) return -1;
        int reserved = 0;
        for (short slot = 0; slot < getInventory().getCapacity(); slot++) {
            ItemStack stack = getInventory().getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;
            try {
                reserved = Math.addExact(reserved, stack.getQuantity());
            } catch (ArithmeticException overflow) {
                return -1;
            }
        }
        return reserved;
    }

    /** Returns the retained quantity for one frozen recipe item. */
    public int reservedQuantity(String itemId) {
        if (reservedState() == ReservedState.INVALID) return -1;
        int reserved = 0;
        for (short slot = 0; slot < getInventory().getCapacity(); slot++) {
            ItemStack stack = getInventory().getItemStack(slot);
            if (ItemStack.isEmpty(stack) || !itemId.equals(stack.getItemId())) {
                continue;
            }
            try {
                reserved = Math.addExact(reserved, stack.getQuantity());
            } catch (ArithmeticException overflow) {
                return -1;
            }
        }
        return reserved;
    }

    /** Classifies frozen-recipe stack evidence without consulting live policy. */
    public ReservedState reservedState() {
        if (!recipeValid || costs.isEmpty()) return ReservedState.INVALID;
        int total = 0;
        java.util.Map<String, Integer> quantities = new java.util.HashMap<>();
        for (short slot = 0; slot < getInventory().getCapacity(); slot++) {
            ItemStack stack = getInventory().getItemStack(slot);
            if (ItemStack.isEmpty(stack)) continue;
            try {
                total = Math.addExact(total, stack.getQuantity());
                quantities.merge(stack.getItemId(), stack.getQuantity(), Math::addExact);
            } catch (ArithmeticException invalid) {
                return ReservedState.INVALID;
            }
        }
        if (total == 0) return ReservedState.EMPTY;
        boolean complete = true;
        for (BondedCompanionReviveCost cost : costs) {
            int reserved = quantities.getOrDefault(cost.itemId(), 0);
            if (reserved > cost.quantity()) return ReservedState.INVALID;
            if (reserved != cost.quantity()) complete = false;
            quantities.remove(cost.itemId());
        }
        if (!quantities.isEmpty()) return ReservedState.INVALID;
        return complete ? ReservedState.RESERVED : ReservedState.PARTIAL;
    }

    /** True only for a complete, uncontaminated operation-specific charge. */
    public boolean hasExactReservedCharge() {
        return reservedState() == ReservedState.RESERVED;
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

    /** Exact immutable recipe retained independently of config reloads. */
    public List<BondedCompanionReviveCost> costs() {
        return costs;
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
        copy.costs = costs;
        copy.recipeValid = recipeValid;
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
        this.costs = List.of(new BondedCompanionReviveCost(
                this.itemId, quantity));
    }

    private void setQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Escrow quantity must be positive");
        }
        this.quantity = quantity;
        this.costs = List.of(new BondedCompanionReviveCost(itemId, quantity));
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

    private CostEntry[] costEntries() {
        CostEntry[] entries = new CostEntry[costs.size()];
        for (int index = 0; index < costs.size(); index++) {
            BondedCompanionReviveCost cost = costs.get(index);
            entries[index] = new CostEntry(cost.itemId(), cost.quantity());
        }
        return entries;
    }

    private void setCostEntries(CostEntry[] entries) {
        if (entries == null || entries.length == 0) {
            invalidateRecipe();
            return;
        }
        try {
            List<BondedCompanionReviveCost> parsed = new ArrayList<>(entries.length);
            for (CostEntry entry : entries) {
                if (entry == null) throw new IllegalArgumentException(
                        "cost is required");
                parsed.add(new BondedCompanionReviveCost(
                        entry.itemId(), entry.quantity()));
            }
            setCosts(parsed);
        } catch (RuntimeException invalid) {
            invalidateRecipe();
        }
    }

    private void setCosts(List<BondedCompanionReviveCost> costs) {
        this.costs = validateCosts(costs);
        recipeValid = true;
        BondedCompanionReviveCost first = this.costs.getFirst();
        itemId = first.itemId();
        quantity = first.quantity();
    }

    private static List<BondedCompanionReviveCost> validateCosts(
            List<BondedCompanionReviveCost> costs) {
        List<BondedCompanionReviveCost> copy = List.copyOf(
                Objects.requireNonNull(costs, "costs"));
        if (copy.isEmpty()) throw new IllegalArgumentException("costs are required");
        Set<String> ids = new HashSet<>();
        for (BondedCompanionReviveCost cost : copy) {
            if (cost == null || !ids.add(cost.itemId())) {
                throw new IllegalArgumentException("cost item IDs must be unique");
            }
        }
        return copy;
    }

    private void invalidateRecipe() {
        costs = List.of();
        recipeValid = false;
    }

    /** Valid persisted recipe evidence states. */
    public enum ReservedState { EMPTY, PARTIAL, RESERVED, INVALID }

    private static final class CostEntry {
        private String itemId = "uninitialized";
        private int quantity = 1;

        private CostEntry() { }

        private CostEntry(String itemId, int quantity) {
            setItemId(itemId);
            setQuantity(quantity);
        }

        private String itemId() { return itemId; }
        private int quantity() { return quantity; }
        private void setItemId(String itemId) {
            this.itemId = itemId;
        }
        private void setQuantity(int quantity) {
            this.quantity = quantity;
        }
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
