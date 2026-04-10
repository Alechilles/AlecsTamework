package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TalentIdCodecTest {
    @Test
    void encodeReturnsNullForEmptyInput() {
        assertNull(TalentIdCodec.encode(null));
        assertNull(TalentIdCodec.encode(new String[0]));
    }

    @Test
    void encodeDecodeRoundTripPreservesEscapedIds() {
        String encoded = TalentIdCodec.encode(new String[] {
                "talent.alpha",
                "talent|beta",
                "talent\\gamma"
        });

        assertEquals("talent.alpha|talent\\|beta|talent\\\\gamma", encoded);
        assertArrayEquals(
                new String[] { "talent.alpha", "talent|beta", "talent\\gamma" },
                TalentIdCodec.decode(encoded)
        );
    }

    @Test
    void decodeDropsBlankEntriesAndDeduplicates() {
        assertArrayEquals(
                new String[] { "talent.a", "talent.b" },
                TalentIdCodec.decode("talent.a||talent.b|talent.a")
        );
    }
}
