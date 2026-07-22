package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRecord;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.codec.Codec;
import org.bson.BsonDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Exact-stack planning plus one all-or-none Hytale inventory payment for revival. */
public final class CommandReviveInventoryPaymentService {
    private static final List<String> COMPARTMENT_ORDER = List.of("backpack", "storage", "hotbar");
    static final String RESERVATION_RECEIPT_KEY = "Tamework.PaidRevivalReservation";
    static final String REFUND_RECEIPT_KEY = "Tamework.PaidRevivalRefund";

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
        return consume(player, null, exactCost, reservations);
    }

    @Nonnull
    public ConsumeResult consume(@Nonnull Player player,
                                 @Nullable java.util.UUID operationId,
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
        ItemContainer combined = inventory.getCombinedBackpackStorageHotbar();
        ReceiptEvidence evidence = inspect(player, operationId, frozen);
        if (evidence != ReceiptEvidence.HELD) {
            return new ConsumeResult(Status.STALE_FENCE, "reserved-stack-receipt-unavailable");
        }
        Map<String, PaidCommandRevivalRecord.Reservation> byReceipt = new HashMap<>();
        for (PaidCommandRevivalRecord.Reservation reservation : frozen) {
            byReceipt.put(receipt(operationId, reservation), reservation);
        }
        ListTransaction<com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction> transaction =
                combined.replaceAll((slot, current) -> {
                    String raw = receiptValue(current, RESERVATION_RECEIPT_KEY);
                    PaidCommandRevivalRecord.Reservation reservation = byReceipt.get(raw);
                    if (reservation == null) return current;
                    int remaining = current.getQuantity() - reservation.quantity();
                    return remaining <= 0 ? ItemStack.EMPTY : withoutMetadata(
                            current.withQuantity(remaining), RESERVATION_RECEIPT_KEY);
                });
        return transaction != null && transaction.succeeded()
                ? new ConsumeResult(Status.CONSUMED, null)
                : new ConsumeResult(Status.FAILED, "atomic-inventory-remove-failed");
    }

    /** Best-effort exact component refund; a failed delivery remains a durable refund claim. */
    @Nonnull
    public ConsumeResult refund(@Nonnull Player player,
                                @Nonnull List<ItemCostComponentView> exactCost) {
        return refund(player, null, exactCost);
    }

    @Nonnull
    public ConsumeResult refund(@Nonnull Player player, @Nullable java.util.UUID operationId,
                                @Nonnull List<ItemCostComponentView> exactCost) {
        if (exactCost.isEmpty()) return new ConsumeResult(Status.REFUNDED, null);
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getCombinedBackpackStorageHotbar() == null) {
            return new ConsumeResult(Status.UNAVAILABLE, "inventory-unavailable");
        }
        ArrayList<ItemStack> stacks = new ArrayList<>(exactCost.size());
        if (operationId != null && refundDelivered(player, operationId, exactCost)) {
            return new ConsumeResult(Status.REFUNDED, null);
        }
        for (int ordinal = 0; ordinal < exactCost.size(); ordinal++) {
            ItemCostComponentView cost = exactCost.get(ordinal);
            ItemStack stack = new ItemStack(cost.itemId(), cost.quantity());
            if (operationId != null) stack = stack.withMetadata(
                    REFUND_RECEIPT_KEY, Codec.STRING, refundReceipt(operationId, ordinal));
            stacks.add(stack);
        }
        ListTransaction<ItemStackTransaction> transaction = inventory.getCombinedBackpackStorageHotbar()
                .addItemStacks(stacks, true, true, true);
        return transaction != null && transaction.succeeded()
                ? new ConsumeResult(Status.REFUNDED, null)
                : new ConsumeResult(Status.FAILED, "refund-inventory-full");
    }

    @Nonnull
    public ConsumeResult hold(@Nonnull Player player, @Nonnull java.util.UUID operationId,
                              @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return new ConsumeResult(Status.UNAVAILABLE, "inventory-unavailable");
        ReceiptEvidence before = inspect(player, operationId, reservations);
        if (before == ReceiptEvidence.HELD) return new ConsumeResult(Status.READY, null);
        if (before == ReceiptEvidence.AMBIGUOUS || before == ReceiptEvidence.UNAVAILABLE) {
            return new ConsumeResult(Status.STALE_FENCE, "reservation-receipt-ambiguous");
        }
        ReceiptScan scan = scanReservationReceipts(inventory, operationId, reservations);
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            String expectedReceipt = receipt(operationId, reservation);
            if (scan.occurrences().containsKey(expectedReceipt)) continue;
            ItemContainer container = compartment(inventory, reservation.compartmentId());
            ItemStack current = container != null && reservation.slotIndex() < container.getCapacity()
                    ? container.getItemStack((short) reservation.slotIndex()) : null;
            if (current == null || current.isEmpty()
                    || !fingerprint(current).equals(reservation.sourceStackFingerprint())) {
                return new ConsumeResult(Status.STALE_FENCE, "reservation-source-changed");
            }
            ItemStack held = current.withMetadata(
                    RESERVATION_RECEIPT_KEY, Codec.STRING, expectedReceipt);
            if (!container.replaceItemStackInSlot((short) reservation.slotIndex(), current, held).succeeded()) {
                return new ConsumeResult(Status.FAILED, "reservation-receipt-write-failed");
            }
        }
        return inspect(player, operationId, reservations) == ReceiptEvidence.HELD
                ? new ConsumeResult(Status.READY, null)
                : new ConsumeResult(Status.STALE_FENCE, "reservation-receipt-verification-failed");
    }

    @Nonnull
    public ReceiptEvidence inspect(@Nonnull Player player, @Nullable java.util.UUID operationId,
                                   @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return ReceiptEvidence.UNAVAILABLE;
        ReceiptScan scan = scanReservationReceipts(inventory, operationId, reservations);
        if (scan.ambiguous()) return ReceiptEvidence.AMBIGUOUS;
        boolean canCompleteHold = true;
        int held = 0;
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            String expectedReceipt = receipt(operationId, reservation);
            if (scan.occurrences().containsKey(expectedReceipt)) {
                held++;
                continue;
            }
            ItemContainer container = compartment(inventory, reservation.compartmentId());
            ItemStack original = container != null && reservation.slotIndex() < container.getCapacity()
                    ? container.getItemStack((short) reservation.slotIndex()) : null;
            if (original == null || original.isEmpty()
                    || !fingerprint(original).equals(reservation.sourceStackFingerprint())) {
                canCompleteHold = false;
            }
        }
        if (held == reservations.size()) return ReceiptEvidence.HELD;
        if (canCompleteHold) return held == 0 ? ReceiptEvidence.UNHELD : ReceiptEvidence.PARTIAL_HELD;
        return ReceiptEvidence.AMBIGUOUS;
    }

    public void release(@Nonnull Player player, @Nonnull java.util.UUID operationId,
                        @Nonnull List<PaidCommandRevivalRecord.Reservation> reservations) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            for (LocatedReceipt located : findReceipts(inventory, RESERVATION_RECEIPT_KEY,
                    receipt(operationId, reservation))) {
                ItemStack clean = withoutMetadata(located.stack(), RESERVATION_RECEIPT_KEY);
                located.container().replaceItemStackInSlot(located.slot(), located.stack(), clean);
            }
        }
    }

    public boolean refundDelivered(@Nonnull Player player, @Nonnull java.util.UUID operationId,
                                   @Nonnull List<ItemCostComponentView> exactCost) {
        return inspectRefund(player, operationId, exactCost) == RefundEvidence.DELIVERED;
    }

    @Nonnull
    public RefundEvidence inspectRefund(@Nonnull Player player, @Nonnull java.util.UUID operationId,
                                        @Nonnull List<ItemCostComponentView> exactCost) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return RefundEvidence.UNAVAILABLE;
        String operationPrefix = operationId + ":";
        Map<String, List<LocatedReceipt>> matches = scanReceipts(
                inventory, REFUND_RECEIPT_KEY, operationPrefix);
        if (matches.isEmpty()) return RefundEvidence.NOT_DELIVERED;
        if (matches.size() != exactCost.size()) return RefundEvidence.AMBIGUOUS;
        for (int ordinal = 0; ordinal < exactCost.size(); ordinal++) {
            List<LocatedReceipt> occurrences = matches.get(refundReceipt(operationId, ordinal));
            if (occurrences == null || occurrences.size() != 1) return RefundEvidence.AMBIGUOUS;
            ItemStack stack = occurrences.getFirst().stack();
            ItemCostComponentView cost = exactCost.get(ordinal);
            if (!cost.itemId().equals(stack.getItemId()) || cost.quantity() != stack.getQuantity()) {
                return RefundEvidence.AMBIGUOUS;
            }
        }
        return RefundEvidence.DELIVERED;
    }

    public void clearRefundReceipts(@Nonnull Player player, @Nonnull java.util.UUID operationId,
                                    int componentCount) {
        Inventory inventory = player.getInventory();
        if (inventory == null) return;
        for (int ordinal = 0; ordinal < componentCount; ordinal++) {
            for (LocatedReceipt located : findReceipts(inventory, REFUND_RECEIPT_KEY,
                    refundReceipt(operationId, ordinal))) {
                located.container().replaceItemStackInSlot(located.slot(), located.stack(),
                        withoutMetadata(located.stack(), REFUND_RECEIPT_KEY));
            }
        }
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

    static String receipt(@Nullable java.util.UUID operationId,
                          PaidCommandRevivalRecord.Reservation reservation) {
        String operation = operationId == null ? "*" : operationId.toString();
        return operation + ":" + reservation.costOrdinal() + ":" + reservation.stackOrdinal()
                + ":" + reservation.quantity() + ":" + reservation.sourceStackFingerprint();
    }

    private static String refundReceipt(java.util.UUID operationId, int ordinal) {
        return operationId + ":" + ordinal;
    }

    private static String receiptValue(ItemStack stack, String key) {
        return stack == null || stack.isEmpty() ? null
                : stack.getFromMetadataOrNull(key, Codec.STRING);
    }

    private static List<LocatedReceipt> findReceipts(Inventory inventory, String key, String value) {
        ArrayList<LocatedReceipt> matches = new ArrayList<>();
        for (String compartmentId : COMPARTMENT_ORDER) {
            ItemContainer container = compartment(inventory, compartmentId);
            if (container == null) continue;
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (value.equals(receiptValue(stack, key))) {
                    matches.add(new LocatedReceipt(container, slot, stack));
                }
            }
        }
        return List.copyOf(matches);
    }

    private static Map<String, List<LocatedReceipt>> scanReceipts(
            Inventory inventory, String key, String operationPrefix) {
        LinkedHashMap<String, List<LocatedReceipt>> matches = new LinkedHashMap<>();
        for (String compartmentId : COMPARTMENT_ORDER) {
            ItemContainer container = compartment(inventory, compartmentId);
            if (container == null) continue;
            for (short slot = 0; slot < container.getCapacity(); slot++) {
                ItemStack stack = container.getItemStack(slot);
                String value = receiptValue(stack, key);
                if (value == null || !value.startsWith(operationPrefix)) continue;
                matches.computeIfAbsent(value, ignored -> new ArrayList<>())
                        .add(new LocatedReceipt(container, slot, stack));
            }
        }
        return matches;
    }

    private static ReceiptScan scanReservationReceipts(
            Inventory inventory,
            @Nullable java.util.UUID operationId,
            List<PaidCommandRevivalRecord.Reservation> reservations) {
        String operationPrefix = (operationId == null ? "*" : operationId.toString()) + ":";
        Map<String, List<LocatedReceipt>> found = scanReceipts(
                inventory, RESERVATION_RECEIPT_KEY, operationPrefix);
        ArrayList<ReceiptObservation> observations = new ArrayList<>();
        for (Map.Entry<String, List<LocatedReceipt>> entry : found.entrySet()) {
            for (LocatedReceipt occurrence : entry.getValue()) {
                ItemStack held = occurrence.stack();
                observations.add(new ReceiptObservation(entry.getKey(), held.getQuantity(),
                        fingerprint(withoutMetadata(held, RESERVATION_RECEIPT_KEY))));
            }
        }
        return new ReceiptScan(Map.copyOf(found),
                classifyHeldReceipts(observations, operationId, reservations) == ReceiptEvidence.AMBIGUOUS);
    }

    /** Pure receipt classifier used by restart and Hytale stack split/copy tests. */
    static ReceiptEvidence classifyHeldReceipts(
            List<ReceiptObservation> observations,
            @Nullable java.util.UUID operationId,
            List<PaidCommandRevivalRecord.Reservation> reservations) {
        Map<String, PaidCommandRevivalRecord.Reservation> expected = new LinkedHashMap<>();
        for (PaidCommandRevivalRecord.Reservation reservation : reservations) {
            expected.put(receipt(operationId, reservation), reservation);
        }
        Map<String, List<ReceiptObservation>> found = new LinkedHashMap<>();
        String operationPrefix = (operationId == null ? "*" : operationId.toString()) + ":";
        for (ReceiptObservation observation : observations) {
            if (!observation.value().startsWith(operationPrefix)) continue;
            if (!expected.containsKey(observation.value())) return ReceiptEvidence.AMBIGUOUS;
            found.computeIfAbsent(observation.value(), ignored -> new ArrayList<>()).add(observation);
        }
        for (Map.Entry<String, List<ReceiptObservation>> entry : found.entrySet()) {
            if (entry.getValue().size() != 1) return ReceiptEvidence.AMBIGUOUS;
            PaidCommandRevivalRecord.Reservation reservation = expected.get(entry.getKey());
            ReceiptObservation observation = entry.getValue().get(0);
            if (observation.quantity() < reservation.quantity()
                    || !observation.cleanFingerprint().equals(reservation.sourceStackFingerprint())) {
                return ReceiptEvidence.AMBIGUOUS;
            }
        }
        if (found.size() == expected.size()) return ReceiptEvidence.HELD;
        return found.isEmpty() ? ReceiptEvidence.UNHELD : ReceiptEvidence.PARTIAL_HELD;
    }

    private static ItemContainer compartment(Inventory inventory, String id) {
        return switch (id) {
            case "backpack" -> inventory.getBackpack();
            case "storage" -> inventory.getStorage();
            case "hotbar" -> inventory.getHotbar();
            default -> null;
        };
    }

    private static ItemStack withoutMetadata(ItemStack stack, String key) {
        BsonDocument metadata = stack.getMetadata() == null ? null : stack.getMetadata().clone();
        if (metadata == null) return stack;
        metadata.remove(key);
        return stack.withMetadata(metadata.isEmpty() ? null : metadata);
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
    public enum ReceiptEvidence { UNHELD, PARTIAL_HELD, HELD, AMBIGUOUS, UNAVAILABLE }
    public enum RefundEvidence { NOT_DELIVERED, DELIVERED, AMBIGUOUS, UNAVAILABLE }
    record ReceiptObservation(@Nonnull String value, int quantity, @Nonnull String cleanFingerprint) {
        ReceiptObservation {
            value = Objects.requireNonNull(value, "value");
            cleanFingerprint = Objects.requireNonNull(cleanFingerprint, "cleanFingerprint");
            if (quantity <= 0) throw new IllegalArgumentException("receipt quantity must be positive");
        }
    }
    private record LocatedReceipt(ItemContainer container, short slot, ItemStack stack) { }
    private record ReceiptScan(Map<String, List<LocatedReceipt>> occurrences, boolean ambiguous) { }

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
        public boolean succeeded() {
            return status == Status.READY || status == Status.CONSUMED || status == Status.REFUNDED;
        }
    }
}
