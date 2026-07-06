package com.alechilles.alecstamework.npc.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkDynamicAttachmentsComponentTest {

    @Test
    void preservesPreviousValueWhenSlotHadPriorAttachment() {
        TameworkDynamicAttachmentsComponent.ActiveSlot slot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot(
                        "Head",
                        "base:crest",
                        true,
                        "tamework:antlers",
                        "config:Head:0"
                );

        TameworkDynamicAttachmentsComponent component =
                new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {slot});

        TameworkDynamicAttachmentsComponent.ActiveSlot stored = component.getActiveSlots()[0];
        assertEquals("Head", stored.getSlot());
        assertEquals("base:crest", stored.getPreviousValue());
        assertTrue(stored.isHasPreviousValue());
        assertEquals("tamework:antlers", stored.getAppliedValue());
        assertEquals("config:Head:0", stored.getRuleKey());
        assertTrue(component.hasActiveSlots());
    }

    @Test
    void preservesAbsentPreviousValueAsNull() {
        TameworkDynamicAttachmentsComponent.ActiveSlot slot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot(
                        "Tail",
                        "base:tail",
                        false,
                        "tamework:banner_tail",
                        "config:Tail:2"
                );

        TameworkDynamicAttachmentsComponent component =
                new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {slot});

        TameworkDynamicAttachmentsComponent.ActiveSlot stored = component.getActiveSlots()[0];
        assertFalse(stored.isHasPreviousValue());
        assertNull(stored.getPreviousValue());
    }

    @Test
    void previousValueSetterPreservesValueBeforeHasPreviousFlagIsDecoded() {
        TameworkDynamicAttachmentsComponent.ActiveSlot slot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot();

        slot.setPreviousValue("base:crest");
        slot.setHasPreviousValue(true);

        assertTrue(slot.getHasPreviousValue());
        assertEquals("base:crest", slot.getPreviousValue());

        slot.setHasPreviousValue(false);

        assertNull(slot.getPreviousValue());
    }

    @Test
    void sanitizesInvalidSlots() {
        TameworkDynamicAttachmentsComponent.ActiveSlot valid =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Head", null, false, "antlers", "rule");
        TameworkDynamicAttachmentsComponent.ActiveSlot blankSlot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot(" ", null, false, "antlers", "rule");
        TameworkDynamicAttachmentsComponent.ActiveSlot blankApplied =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Head", null, false, " ", "rule");
        TameworkDynamicAttachmentsComponent.ActiveSlot blankRule =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Head", null, false, "antlers", "");

        TameworkDynamicAttachmentsComponent component =
                new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {
                        null,
                        blankSlot,
                        blankApplied,
                        blankRule,
                        valid
                });

        assertEquals(1, component.getActiveSlots().length);
        assertEquals("Head", component.getActiveSlots()[0].getSlot());
    }

    @Test
    void defensivelyCopiesSlotsAndClone() {
        TameworkDynamicAttachmentsComponent.ActiveSlot originalSlot =
                new TameworkDynamicAttachmentsComponent.ActiveSlot("Head", "crest", true, "antlers", "rule");
        TameworkDynamicAttachmentsComponent component =
                new TameworkDynamicAttachmentsComponent(new TameworkDynamicAttachmentsComponent.ActiveSlot[] {originalSlot});

        originalSlot.setAppliedValue("mutated");
        TameworkDynamicAttachmentsComponent.ActiveSlot[] fromGetter = component.getActiveSlots();
        fromGetter[0].setAppliedValue("changed through getter");
        fromGetter[0] = new TameworkDynamicAttachmentsComponent.ActiveSlot("Tail", null, false, "tail", "rule2");

        TameworkDynamicAttachmentsComponent clone = component.clone();
        clone.getActiveSlots()[0].setRuleKey("clone mutation");

        assertEquals("antlers", component.getActiveSlots()[0].getAppliedValue());
        assertEquals("rule", component.getActiveSlots()[0].getRuleKey());
        assertEquals("antlers", clone.getActiveSlots()[0].getAppliedValue());
        assertNotSame(component.getActiveSlots()[0], clone.getActiveSlots()[0]);
        assertArrayEquals(new String[] {"Head"}, new String[] {component.getActiveSlots()[0].getSlot()});
    }

    @Test
    void emptyOrNullSlotsUseEmptyState() {
        TameworkDynamicAttachmentsComponent component = new TameworkDynamicAttachmentsComponent(null);

        assertFalse(component.hasActiveSlots());
        assertEquals(0, component.getActiveSlots().length);

        component.setActiveSlots(new TameworkDynamicAttachmentsComponent.ActiveSlot[0]);

        assertFalse(component.hasActiveSlots());
        assertEquals(0, component.getActiveSlots().length);
    }

    @Test
    void componentTypeIsNullWhenPluginInstanceIsAbsent() {
        assertNull(TameworkDynamicAttachmentsComponent.getComponentType());
    }
}
