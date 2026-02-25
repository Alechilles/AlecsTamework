package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests trait command argument parsing for debug mutation commands. */
class TameworkTraitCommandInputParserTest {

    @Test
    void parseSetTraitsParsesPairs() {
        TameworkTraitCommandInputParser.ParseResult parsed =
                TameworkTraitCommandInputParser.parseSetTraits("tw settraits Trait_A 1.2 Trait_B 0.9");

        assertTrue(parsed.isSuccess());
        assertEquals(2, parsed.requests().size());
        assertEquals("Trait_A", parsed.requests().get(0).traitId());
        assertEquals(1.2, parsed.requests().get(0).value(), 0.000001);
        assertEquals("Trait_B", parsed.requests().get(1).traitId());
        assertEquals(0.9, parsed.requests().get(1).value(), 0.000001);
    }

    @Test
    void parseSetTraitsRejectsOddPairCount() {
        TameworkTraitCommandInputParser.ParseResult parsed =
                TameworkTraitCommandInputParser.parseSetTraits("tw settraits Trait_A 1.2 Trait_B");

        assertFalse(parsed.isSuccess());
    }

    @Test
    void parseSetTraitsRejectsInvalidValue() {
        TameworkTraitCommandInputParser.ParseResult parsed =
                TameworkTraitCommandInputParser.parseSetTraits("tw settraits Trait_A nope");

        assertFalse(parsed.isSuccess());
    }

    @Test
    void parseAddTraitParsesSinglePair() {
        TameworkTraitCommandInputParser.ParseResult parsed =
                TameworkTraitCommandInputParser.parseAddTrait("tw addtrait Trait_Health 1.15");

        assertTrue(parsed.isSuccess());
        assertEquals(1, parsed.requests().size());
        assertEquals("Trait_Health", parsed.requests().get(0).traitId());
        assertEquals(1.15, parsed.requests().get(0).value(), 0.000001);
    }

    @Test
    void parseAddTraitRejectsExtraArguments() {
        TameworkTraitCommandInputParser.ParseResult parsed =
                TameworkTraitCommandInputParser.parseAddTrait("tw addtrait Trait_Health 1.15 Extra 1.0");

        assertFalse(parsed.isSuccess());
    }
}

