package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SensorTameworkReachableBlockTargetTest {
    @Test
    void exactBlockTypeMatchingIsCaseInsensitiveAndTrimsIds() {
        Set<String> blockTypes = SensorTameworkReachableBlockTarget.sanitizeIdSet(new String[]{
                " hytale:hay_bale ",
                "MOD:Feeder"
        });

        assertTrue(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("HYTALE:HAY_BALE"),
                42,
                null,
                blockTypes
        ));
        assertTrue(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("mod:feeder"),
                43,
                null,
                blockTypes
        ));
        assertFalse(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("hytale:stone"),
                44,
                null,
                blockTypes
        ));
    }

    @Test
    void emptyBlockSetAndTypesDoNotMatchAnything() {
        assertFalse(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("hytale:hay_bale"),
                42,
                " ",
                Set.of()
        ));
    }

    @Test
    void sanitizerDropsBlankIdsAndKeepsUniqueValues() {
        Set<String> sanitized = SensorTameworkReachableBlockTarget.sanitizeIdSet(new String[]{
                "A",
                " ",
                null,
                "a",
                "B"
        });

        assertEquals(Set.of("a", "b"), sanitized);
    }

    @Test
    void sourceCandidateScanCountIsBounded() {
        assertTrue(SensorTameworkReachableBlockTarget.maxSourceCandidatesPerScanForTests() > 0);
        assertTrue(SensorTameworkReachableBlockTarget.maxSourceCandidatesPerScanForTests() <= 32);
    }
}
