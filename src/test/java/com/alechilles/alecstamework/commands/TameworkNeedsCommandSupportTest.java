package com.alechilles.alecstamework.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TameworkNeedsCommandSupportTest {
    @Test
    void parseDoubleArgParsesFiniteTokenAtIndex() {
        Double parsed = TameworkNeedsCommandSupport.parseDoubleArg("tw setneeds 72.5 18", 2);
        assertEquals(72.5, parsed);
    }

    @Test
    void parseDoubleArgReturnsNullForMissingOrInvalidInput() {
        assertNull(TameworkNeedsCommandSupport.parseDoubleArg("tw setneeds", 2));
        assertNull(TameworkNeedsCommandSupport.parseDoubleArg("tw setneeds notNumber 10", 2));
    }

    @Test
    void clampAppliesBounds() {
        assertEquals(0.0, TameworkNeedsCommandSupport.clamp(-5.0, 0.0, 100.0));
        assertEquals(100.0, TameworkNeedsCommandSupport.clamp(120.0, 0.0, 100.0));
        assertEquals(42.0, TameworkNeedsCommandSupport.clamp(42.0, 0.0, 100.0));
    }
}

