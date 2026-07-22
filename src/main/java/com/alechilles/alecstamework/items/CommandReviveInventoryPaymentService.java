package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRecord;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact-stack planning plus one all-or-none Hytale inventory payment for revival. */
public final class CommandReviveInventoryPaymentService {
    private static final List<String> COMPARTMENT_ORDER = List.of("backpack", "storage", "hotbar");

    @Nonnull
    public PlanResult plan(@Nonnull Player player,
                           @Nonnull List<ItemCostComponentView> exactCost,
                           long reservationGeneration) {
        Objects.requireNonNull(player, "player");
        Inventory inventory = player.getInventory();
        if (inventory == null) return PlanResult.unavailable("inventory-unavailable");
        return plan(readSlots(inventory), exactCost, reservationGeneration);
    }

    /** Pure deterministic planner used by tests and recovery checks. */
    @Nonnull
    static PlanResult plan(@Nonnull List<SlotStack> slots,
                           @Nonnull List<ItemCostComponentView> exactCost,
                           long reservationGeneration) {
        Objects.requireNonNull(slots, "slots");
        List<ItemCostComponentView> costs = List.copyOf(Objects.requireNonNull(exactCost, "exactCost"));
        if (reservationGeneration < 0L) throw new IllegalArgumentException("reservationGeneration cannot be negative");
        ArrayList<PaidCommandRevivalRecord.Reservation> reservations = new ArrayList<>();
        for (int costOrdinal = 0; costOrdinal < costs.size(); costOrdinal++) {
            ItemCostComponentView cost = costs.get(costOrdinal);
            int remaining = cost.quantity();
            int stackOrdinal = 0;
            for (SlotStack slot : slots) {
                if (remaining == 0) break;
                if (!cost.itemId().equals(slot.itemId()) || slot.quantity() <= 0) continue;
                int take = Math.min(remaining, slot.quantity());
                reservations.add(new PaidCommandRevivalRecord.Reservation(
                        costOrdinal, stackOrdinal++, slot.compartmentId(), slot.slotIndex(), take,
                        slot.fingerprint(), reservationGeneration,
                        PaidCommandRevivalRecord.ReservationState.HELD));
                remaining -= take;
            }
            if (remaining > 0) {
                return new PlanResult(Status.INSUFFICIENT, List.of(), cost.itemId(), remaining);
            }
        }
        return new PlanResult(Status.READY, List.copyOf(reservations), null, 0);
    }

    /**
     * Revalidates every frozen source slot, then removes the complete recipe in one Hytale
     * all-or-nothing transaction. No component can be charged if another is missing.
     */
    @Nonnull
    public ConsumeResult consume(@Nonnull Player player,
                                 @Nonnull List<ItemCostComponentView> exactCost,
                                 @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations) {
        Objects.requireNonNull(player, "player");
        List<ItemCostComponentView> costs = List.copyOf(exactCost);
        List<PaidCommandRevivalRecord.Reservation> frozen = List.copyOf(reservations);
        if (costs.isEmpty()) return new ConsumeResult(Status.CONSUMED, null);
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getCombinedBackpackStorageHotbar() == null) {
            return new ConsumeResult(Status.UNAVAILABLE, "inventory-unavailable");
        }
        List<SlotStack> current = readSlots(inventory);
        for (PaidCommandRevivalRecord.Reservation reservation : frozen) {
            if (reservation.costOrdinal() >= costs.size()) {
                return new ConsumeResult(Status.STALE_FENCE, "reservation-cost-out-of-range");
            }
            SlotStack matching = current.stream()
                    .filter(slot -> slot.compartmentId().equals(reservation.compartmentId())
                            && slot.slotIndex() == reservation.slotIndex())
                    .findFirst().orElse(null);
            ItemCostComponentView cost = costs.get(reservation.costOrdinal());
            if (matching == null || !matching.itemId().equals(cost.itemId())
                    || matching.quantity() < reservation.quantity()
                    || !matching.fingerprint().equals(reservation.sourceStackFingerprint())) {
                return new ConsumeResult(Status.STALE_FENCE, "reserved-stack-changed");
            }
        }
        ArrayList<ItemStack> requested = new ArrayList<>(costs.size());
        for (ItemCostComponentView cost : costs) requested.add(new ItemStack(cost.itemId(), cost.quantity()));
        ItemContainer combined = inventory.getCombinedBackpackStorageHotbar();
        if (!combined.canRemoveItemStacks(requested, true, true)) {
            return new ConsumeResult(Status.INSUFFICIENT, "inventory-cost-no-longer-present");
        }
        ListTransaction<ItemStackTransaction> transaction = combined.removeItemStacks(requested, true, true);
        return transaction != null && transaction.succeeded()
                ? new ConsumeResult(Status.CONSUMED, null)
                : new ConsumeResult(Status.FAILED, "atomic-inventory-remove-failed");
    }

    /** Best-effort exact component refund; a failed delivery remains a durable refund claim. */
    @Nonnull
    public ConsumeResult refund(@Nonnull Player player,
                                @Nonnull List<ItemCostComponentView> exactCost) {
        if (exactCost.isEmpty()) return new ConsumeResult(Status.REFUNDED, null);
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getCombinedBackpackStorageHotbar() == null) {
            return new ConsumeResult(Status.UNAVAILABLE, "inventory-unavailable");
        }
        ArrayList<ItemStack> stacks = new ArrayList<>(exactCost.size());
        for (ItemCostComponentView cost : exactCost) stacks.add(new ItemStack(cost.itemId(), cost.quantity()));
        ListTransaction<ItemStackTransaction> transaction = inventory.getCombinedBackpackStorageHotbar()
                .addItemStacks(stacks, true, true, true);
        return transaction != null && transaction.succeeded()
                ? new ConsumeResult(Status.REFUNDED, null)
                : new ConsumeResult(Status.FAILED, "refund-inventory-full");
    }

    private static List<SlotStack> readSlots(Inventory inventory) {
        ArrayList<SlotStack> slots = new ArrayList<>();
        addSlots(slots, "backpack", inventory.getBackpack());
        addSlots(slots, "storage", inventory.getStorage());
        addSlots(slots, "hotbar", inventory.getHotbar());
        return List.copyOf(slots);
    }

    private static void addSlots(List<SlotStack> output, String compartment, @Nullable ItemContainer container) {
        if (container == null) return;
        for (short slot = 0; slot < container.getCapacity(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack == null || stack.isEmpty() || stack.getQuantity() <= 0) continue;
            output.add(new SlotStack(compartment, slot, stack.getItemId(), stack.getQuantity(), fingerprint(stack)));
        }
    }

    static String fingerprint(ItemStack stack) {
        String canonical = stack.getItemId() + "\u001f" + stack.getQuantity() + "\u001f"
                + stack.getDurability() + "\u001f" + stack.getMaxDurability() + "\u001f"
                + (stack.getMetadata() == null ? "" : stack.getMetadata().toJson());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    record SlotStack(@Nonnull String compartmentId, int slotIndex, @Nonnull String itemId,
                     int quantity, @Nonnull String fingerprint) {
        SlotStack {
            if (!COMPARTMENT_ORDER.contains(compartmentId) || slotIndex < 0 || quantity <= 0) {
                throw new IllegalArgumentException("invalid inventory slot evidence");
            }
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public enum Status { READY, INSUFFICIENT, CONSUMED, REFUNDED, STALE_FENCE, UNAVAILABLE, FAILED }

    public record PlanResult(@Nonnull Status status,
                             @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations,
                             @Nullable String missingItemId,
                             int shortage) {
        public PlanResult {
            reservations = List.copyOf(reservations);
            if (shortage < 0) throw new IllegalArgumentException("shortage cannot be negative");
        }

        static PlanResult unavailable(String reason) {
            return new PlanResult(Status.UNAVAILABLE, List.of(), reason, 0);
        }

        public boolean ready() { return status == Status.READY; }
    }

    public record ConsumeResult(@Nonnull Status status, @Nullable String reason) {
        public boolean succeeded() { return status == Status.CONSUMED || status == Status.REFUNDED; }
    }
}
