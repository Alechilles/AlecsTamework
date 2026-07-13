package com.alechilles.alecstamework.npc.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for lossless attachment item settlement. */
class AttachmentExchangeInventoryServiceTest {
    @Test
    void emptyHandRemovalPlacesRefundInActiveSlot() {
        FakeInventoryPort port = new FakeInventoryPort(item(null, 0), true);
        AttachmentExchangePlan plan = new AttachmentExchangePlan(
                "Saddle", "Yes", "None", null, "AH_Saddle", 0
        );

        assertTrue(AttachmentExchangeInventoryService.canApply(port, plan));
        assertTrue(AttachmentExchangeInventoryService.apply(port, plan));
        assertStack(port.activeItem(), "AH_Saddle", 1);
    }

    @Test
    void singleHeldItemReplacementSwapsDirectlyToRefund() {
        FakeInventoryPort port = new FakeInventoryPort(item("Cloth_Block_Wool_Red", 1), true);
        AttachmentExchangePlan plan = replacementPlan(1);

        assertTrue(AttachmentExchangeInventoryService.apply(port, plan));
        assertStack(port.activeItem(), "Cloth_Block_Wool_Blue", 1);
        assertEquals(0, port.refundsAdded);
    }

    @Test
    void initialEquipConsumesSingleHeldItemWithoutRefund() {
        FakeInventoryPort port = new FakeInventoryPort(item("AH_Saddle", 1), true);
        AttachmentExchangePlan plan = new AttachmentExchangePlan(
                "Saddle", "None", "Yes", "AH_Saddle", null, 1
        );

        assertTrue(AttachmentExchangeInventoryService.apply(port, plan));
        assertNull(port.activeItem().itemId());
        assertEquals(0, port.activeItem().quantity());
        assertEquals(0, port.refundsAdded);
    }

    @Test
    void initialEquipDecrementsStackWithoutNeedingInventorySpace() {
        FakeInventoryPort port = new FakeInventoryPort(item("AH_Saddle", 3), false);
        AttachmentExchangePlan plan = new AttachmentExchangePlan(
                "Saddle", "None", "Yes", "AH_Saddle", null, 3
        );

        assertTrue(AttachmentExchangeInventoryService.canApply(port, plan));
        assertTrue(AttachmentExchangeInventoryService.apply(port, plan));
        assertStack(port.activeItem(), "AH_Saddle", 2);
        assertEquals(0, port.refundsAdded);
    }

    @Test
    void stackedReplacementDecrementsHeldStackAndAddsRefundAllOrNothing() {
        FakeInventoryPort port = new FakeInventoryPort(item("Cloth_Block_Wool_Red", 4), true);
        AttachmentExchangePlan plan = replacementPlan(4);

        assertTrue(AttachmentExchangeInventoryService.canApply(port, plan));
        assertTrue(AttachmentExchangeInventoryService.apply(port, plan));
        assertStack(port.activeItem(), "Cloth_Block_Wool_Red", 3);
        assertEquals(1, port.refundsAdded);
        assertEquals("Cloth_Block_Wool_Blue", port.lastRefundItemId);
    }

    @Test
    void fullInventoryRejectsStackedReplacementBeforeMutation() {
        FakeInventoryPort port = new FakeInventoryPort(item("Cloth_Block_Wool_Red", 4), false);
        AttachmentExchangePlan plan = replacementPlan(4);

        assertFalse(AttachmentExchangeInventoryService.canApply(port, plan));
        assertFalse(AttachmentExchangeInventoryService.apply(port, plan));
        assertStack(port.activeItem(), "Cloth_Block_Wool_Red", 4);
        assertEquals(0, port.refundsAdded);
    }

    @Test
    void refundInsertionFailureRestoresOriginalHeldStack() {
        FakeInventoryPort port = new FakeInventoryPort(item("Cloth_Block_Wool_Red", 2), true);
        port.failAdd = true;

        assertFalse(AttachmentExchangeInventoryService.apply(port, replacementPlan(2)));
        assertStack(port.activeItem(), "Cloth_Block_Wool_Red", 2);
        assertEquals(0, port.refundsAdded);
    }

    private AttachmentExchangePlan replacementPlan(int heldQuantity) {
        return new AttachmentExchangePlan(
                "SaddleBlanket",
                "Blue",
                "Red",
                "Cloth_Block_Wool_Red",
                "Cloth_Block_Wool_Blue",
                heldQuantity
        );
    }

    private AttachmentExchangeInventoryService.ActiveItem item(String itemId, int quantity) {
        return new AttachmentExchangeInventoryService.ActiveItem(itemId, quantity);
    }

    private void assertStack(AttachmentExchangeInventoryService.ActiveItem stack,
                             String itemId,
                             int quantity) {
        assertEquals(itemId, stack.itemId());
        assertEquals(quantity, stack.quantity());
    }

    private static final class FakeInventoryPort implements AttachmentExchangeInventoryService.InventoryPort {
        private AttachmentExchangeInventoryService.ActiveItem active;
        private final boolean canAdd;
        private boolean failAdd;
        private int refundsAdded;
        private String lastRefundItemId;

        private FakeInventoryPort(AttachmentExchangeInventoryService.ActiveItem active, boolean canAdd) {
            this.active = active;
            this.canAdd = canAdd;
        }

        @Override
        public AttachmentExchangeInventoryService.ActiveItem activeItem() {
            return active;
        }

        @Override
        public boolean replaceActive(AttachmentExchangeInventoryService.ActiveItem expected,
                                     AttachmentExchangeInventoryService.ActiveItem replacement) {
            if (!active.equals(expected)) {
                return false;
            }
            active = replacement;
            return true;
        }

        @Override
        public boolean canAddAllOrNothing(String itemId) {
            return canAdd;
        }

        @Override
        public boolean addAllOrNothing(String itemId) {
            if (!canAdd || failAdd) {
                return false;
            }
            refundsAdded++;
            lastRefundItemId = itemId;
            return true;
        }
    }
}
