package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.ItemCostComponentView;
import com.alechilles.alecstamework.persistence.sqlite.PaidCommandRevivalRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandReviveInventoryPaymentServiceTest {
    @Test
    void plansEveryComponentAcrossSplitStacksInStableOrder() {
        List<CommandReviveInventoryPaymentService.SlotStack> slots = List.of(
                slot("backpack", 0, "Life_Essence", 2, "a"),
                slot("backpack", 1, "Gold_Bar", 3, "b"),
                slot("storage", 0, "Life_Essence", 4, "c"),
                slot("hotbar", 2, "Gold_Bar", 8, "d"));

        var result = CommandReviveInventoryPaymentService.plan(slots, List.of(
                new ItemCostComponentView("Life_Essence", 5),
                new ItemCostComponentView("Gold_Bar", 7)), 11L);

        assertTrue(result.ready());
        assertEquals(List.of(2, 3, 3, 4),
                result.reservations().stream().map(r -> r.quantity()).toList());
        assertEquals(List.of(0, 0, 1, 1),
                result.reservations().stream().map(r -> r.costOrdinal()).toList());
        assertEquals(List.of("backpack", "storage", "backpack", "hotbar"),
                result.reservations().stream().map(r -> r.compartmentId()).toList());
    }

    @Test
    void missingOneComponentProducesNoPartialReservation() {
        var result = CommandReviveInventoryPaymentService.plan(List.of(
                slot("backpack", 0, "Life_Essence", 99, "a"),
                slot("storage", 0, "Gold_Bar", 1, "b")), List.of(
                new ItemCostComponentView("Life_Essence", 2),
                new ItemCostComponentView("Gold_Bar", 4)), 1L);

        assertEquals(CommandReviveInventoryPaymentService.Status.INSUFFICIENT, result.status());
        assertEquals("Gold_Bar", result.missingItemId());
        assertEquals(3, result.shortage());
        assertTrue(result.reservations().isEmpty());
    }

    @Test
    void emptyRecipeIsAValidFreeRevival() {
        var result = CommandReviveInventoryPaymentService.plan(List.of(), List.of(), 0L);
        assertTrue(result.ready());
        assertTrue(result.reservations().isEmpty());
    }

    @Test
    void movedReceiptRemainsHeldWhenItsCleanStackEvidenceIsExact() {
        UUID operationId = UUID.randomUUID();
        PaidCommandRevivalRecord.Reservation reservation = reservation("original-stack", 3);
        String receipt = CommandReviveInventoryPaymentService.receipt(operationId, reservation);

        var evidence = CommandReviveInventoryPaymentService.classifyHeldReceipts(List.of(
                new CommandReviveInventoryPaymentService.ReceiptObservation(
                        receipt, 8, "original-stack")), operationId, List.of(reservation));

        assertEquals(CommandReviveInventoryPaymentService.ReceiptEvidence.HELD, evidence);
    }

    @Test
    void splitReceiptIsAmbiguousEvenWhenCombinedQuantityIsSufficient() {
        UUID operationId = UUID.randomUUID();
        PaidCommandRevivalRecord.Reservation reservation = reservation("original-stack", 3);
        String receipt = CommandReviveInventoryPaymentService.receipt(operationId, reservation);

        var evidence = CommandReviveInventoryPaymentService.classifyHeldReceipts(List.of(
                new CommandReviveInventoryPaymentService.ReceiptObservation(receipt, 4, "split-a"),
                new CommandReviveInventoryPaymentService.ReceiptObservation(receipt, 4, "split-b")),
                operationId, List.of(reservation));

        assertEquals(CommandReviveInventoryPaymentService.ReceiptEvidence.AMBIGUOUS, evidence);
    }

    @Test
    void copiedReceiptIsAmbiguousAndCannotDoubleCharge() {
        UUID operationId = UUID.randomUUID();
        PaidCommandRevivalRecord.Reservation reservation = reservation("original-stack", 3);
        String receipt = CommandReviveInventoryPaymentService.receipt(operationId, reservation);
        var copy = new CommandReviveInventoryPaymentService.ReceiptObservation(
                receipt, 8, "original-stack");

        var evidence = CommandReviveInventoryPaymentService.classifyHeldReceipts(
                List.of(copy, copy), operationId, List.of(reservation));

        assertEquals(CommandReviveInventoryPaymentService.ReceiptEvidence.AMBIGUOUS, evidence);
    }

    @Test
    void receiptWithInsufficientOrChangedStackEvidenceIsAmbiguous() {
        UUID operationId = UUID.randomUUID();
        PaidCommandRevivalRecord.Reservation reservation = reservation("original-stack", 3);
        String receipt = CommandReviveInventoryPaymentService.receipt(operationId, reservation);

        var evidence = CommandReviveInventoryPaymentService.classifyHeldReceipts(List.of(
                new CommandReviveInventoryPaymentService.ReceiptObservation(
                        receipt, 2, "original-stack")), operationId, List.of(reservation));

        assertEquals(CommandReviveInventoryPaymentService.ReceiptEvidence.AMBIGUOUS, evidence);
    }

    private static PaidCommandRevivalRecord.Reservation reservation(String fingerprint, int quantity) {
        return new PaidCommandRevivalRecord.Reservation(
                0, 0, "backpack", 1, quantity, fingerprint, 7L,
                PaidCommandRevivalRecord.ReservationState.HELD);
    }

    private static CommandReviveInventoryPaymentService.SlotStack slot(
            String compartment, int slot, String itemId, int quantity, String fingerprint) {
        return new CommandReviveInventoryPaymentService.SlotStack(
                compartment, slot, itemId, quantity, fingerprint);
    }
}
