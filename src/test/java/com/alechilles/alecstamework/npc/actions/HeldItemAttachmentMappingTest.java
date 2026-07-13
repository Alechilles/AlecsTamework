package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for exact held-item attachment mapping syntax. */
class HeldItemAttachmentMappingTest {
    @Test
    void resolvesExactItemIdsWithoutAcceptingShapedVariants() {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parse(new InteractionEffectSpec(
                "tamework:set_attachment_from_held_item",
                "SaddleBlanket",
                List.of(
                        "Cloth_Block_Wool_Blue=Blue",
                        "Cloth_Block_Wool_Blue_Light=LightBlue"
                ),
                null
        ));

        assertEquals("SaddleBlanket", mapping.slotId());
        assertEquals(2, mapping.size());
        assertEquals("Blue", mapping.resolve("Cloth_Block_Wool_Blue"));
        assertEquals("LightBlue", mapping.resolve("Cloth_Block_Wool_Blue_Light"));
        assertNull(mapping.resolve("Cloth_Block_Wool_Blue_Half"));
        assertNull(mapping.resolve("cloth_block_wool_blue"));
    }

    @Test
    void rejectsMalformedOrConflictingMappings() {
        assertNull(HeldItemAttachmentMapping.parse(new InteractionEffectSpec(
                "tamework:set_attachment_from_held_item",
                "SaddleBlanket",
                List.of("missing-separator"),
                null
        )));
        assertNull(HeldItemAttachmentMapping.parse(new InteractionEffectSpec(
                "tamework:set_attachment_from_held_item",
                "SaddleBlanket",
                List.of("Cloth_Block_Wool_Blue=Blue", "Cloth_Block_Wool_Blue=Red"),
                null
        )));
        assertNull(HeldItemAttachmentMapping.parse(new InteractionEffectSpec(
                "tamework:set_attachment_from_held_item",
                null,
                List.of("AH_Saddle=Yes"),
                null
        )));
    }

    @Test
    void permitsDuplicateEntriesOnlyWhenTheyAgree() {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parse(new InteractionEffectSpec(
                "tamework:set_attachment_from_held_item",
                "Saddle",
                List.of("AH_Saddle=Yes", "AH_Saddle=Yes"),
                null
        ));

        assertEquals(1, mapping.size());
        assertEquals("Yes", mapping.resolve("AH_Saddle"));
    }

    @Test
    void reversibleMappingsResolveExactRefundItems() {
        HeldItemAttachmentMapping mapping = HeldItemAttachmentMapping.parseExchange(
                new InteractionRequirementSpec(
                        "tamework:attachment_exchange_available",
                        "SaddleBlanket",
                        List.of(
                                "Cloth_Block_Wool_Blue=Blue",
                                "Cloth_Block_Wool_Red=Red"
                        ),
                        null
                )
        );

        assertEquals("Cloth_Block_Wool_Blue", mapping.resolveItemId("Blue"));
        assertEquals("Cloth_Block_Wool_Red", mapping.resolveItemId("Red"));
        assertNull(mapping.resolveItemId("Canada"));
    }

    @Test
    void exchangeMappingsRejectAmbiguousRefundValues() {
        assertNull(HeldItemAttachmentMapping.parseExchange(new InteractionEffectSpec(
                "tamework:exchange_attachment",
                "SaddleBlanket",
                List.of(
                        "Cloth_Block_Wool_Blue=Blue",
                        "Example_Other_Blue=Blue"
                ),
                null
        )));
    }
}
