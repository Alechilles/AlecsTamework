package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for attachment equip, replacement, and removal planning. */
class AttachmentExchangePlanTest {
    private static final Set<String> OPTIONS = Set.of("None", "Blue", "Red", "Canada");

    @Test
    void equipsFromNoneWithoutRefund() {
        AttachmentExchangePlan plan = resolve("None", "Cloth_Block_Wool_Blue", 1, "Cloth_Block_Wool_Blue", OPTIONS);

        assertEquals("Blue", plan.targetValue());
        assertEquals("Cloth_Block_Wool_Blue", plan.consumedItemId());
        assertNull(plan.refundedItemId());
    }

    @Test
    void replacingColorConsumesNewBlockAndRefundsOldColor() {
        AttachmentExchangePlan plan = resolve("Blue", "Cloth_Block_Wool_Red", 3, "Cloth_Block_Wool_Red", OPTIONS);

        assertEquals("Red", plan.targetValue());
        assertEquals("Cloth_Block_Wool_Red", plan.consumedItemId());
        assertEquals("Cloth_Block_Wool_Blue", plan.refundedItemId());
        assertEquals(3, plan.heldQuantity());
    }

    @Test
    void removingMappedColorRefundsExactBlockAndTargetsNone() {
        AttachmentExchangePlan plan = resolve("Blue", null, 0, null, OPTIONS);

        assertEquals("None", plan.targetValue());
        assertNull(plan.consumedItemId());
        assertEquals("Cloth_Block_Wool_Blue", plan.refundedItemId());
    }

    @Test
    void rejectsSameColorStaleHandAndUnmappedDynamicValues() {
        assertNull(resolve("Blue", "Cloth_Block_Wool_Blue", 1, "Cloth_Block_Wool_Blue", OPTIONS));
        assertNull(resolve("Blue", "Cloth_Block_Wool_Red", 1, "Cloth_Block_Wool_Blue", OPTIONS));
        assertNull(resolve("Canada", null, 0, null, OPTIONS));
        assertNull(resolve("Canada", "Cloth_Block_Wool_Red", 1, "Cloth_Block_Wool_Red", OPTIONS));
    }

    @Test
    void rejectsRemovalWhenModelDoesNotSupportNone() {
        assertNull(resolve("Blue", null, 0, null, Set.of("Blue", "Red")));
    }

    private AttachmentExchangePlan resolve(String current,
                                           String liveItem,
                                           int quantity,
                                           String capturedItem,
                                           Set<String> options) {
        return AttachmentExchangePlan.resolve(
                mapping(),
                current,
                liveItem,
                quantity,
                capturedItem,
                options
        );
    }

    private HeldItemAttachmentMapping mapping() {
        return HeldItemAttachmentMapping.parseExchange(new InteractionRequirementSpec(
                "tamework:attachment_exchange_available",
                "SaddleBlanket",
                List.of(
                        "Cloth_Block_Wool_Blue=Blue",
                        "Cloth_Block_Wool_Red=Red"
                ),
                null
        ));
    }
}
