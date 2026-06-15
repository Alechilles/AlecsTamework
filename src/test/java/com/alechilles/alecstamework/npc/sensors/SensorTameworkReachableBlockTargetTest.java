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
    void blockSetMatchingUsesSetIndexBeforeBlockTypeIndex() {
        int expectedBlockSetIndex = 7;
        int expectedBlockTypeIndex = 42;

        assertTrue(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("hytale:hay_bale"),
                expectedBlockTypeIndex,
                expectedBlockSetIndex,
                Set.of(),
                (blockSetIndex, blockTypeIndex) -> {
                    assertEquals(expectedBlockSetIndex, blockSetIndex);
                    assertEquals(expectedBlockTypeIndex, blockTypeIndex);
                    return true;
                }
        ));
    }

    @Test
    void unresolvedBlockSetSkipsMembershipLookup() {
        assertFalse(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("hytale:hay_bale"),
                42,
                Integer.MIN_VALUE,
                Set.of(),
                (blockSetIndex, blockTypeIndex) -> {
                    throw new AssertionError("Unknown block sets should not call membership lookup.");
                }
        ));
    }

    @Test
    void exactBlockTypeMatchDoesNotRequireBlockSetLookup() {
        assertTrue(SensorTameworkReachableBlockTarget.matchesConfiguredBlock(
                new BlockType("hytale:hay_bale"),
                42,
                Integer.MIN_VALUE,
                Set.of("hytale:hay_bale"),
                (blockSetIndex, blockTypeIndex) -> {
                    throw new AssertionError("Exact block types should short-circuit block set lookup.");
                }
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
