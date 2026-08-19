package com.alechilles.alecstamework.npc.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.npc.progression.ReachableBlockSourceCache;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.component.TestEntityComponentStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.joml.Vector3d;

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
        assertTrue(SensorTameworkReachableBlockTarget.maxSourceCandidatesPerScanForTests() <= 64);
    }

    @Test
    void exactAuthoritySelectionKeepsAUsableZeroVerticalSource() {
        ReachableBlockSourceCache.AuthoritySourceSelector selector =
                new ReachableBlockSourceCache.AuthoritySourceSelector(
                        new ReachableBlockSourceCache.SearchBounds(0, 3, 0, 3, 0, 3),
                        0,
                        0,
                        0,
                        4.0,
                        0
                );
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 4; z++) {
                selector.offer(new ReachableBlockSourceCache.SourceCoordinate(x, 1, z));
            }
        }
        ReachableBlockSourceCache.SourceCoordinate usable =
                new ReachableBlockSourceCache.SourceCoordinate(3, 0, 3);

        selector.offer(usable);
        List<ReachableBlockSourceCache.SourceCoordinate> selected = selector.finish();

        assertTrue(selected.contains(usable));
        assertTrue(SensorTameworkReachableBlockTarget.isSourceInRange(
                usable,
                new Vector3d(3.9, 0.9, 3.9),
                4.0,
                0
        ));
    }

    @Test
    void exactAuthoritySelectionKeepsOppositeAuthorityWithSmallRange() {
        ReachableBlockSourceCache.AuthoritySourceSelector selector =
                new ReachableBlockSourceCache.AuthoritySourceSelector(
                        new ReachableBlockSourceCache.SearchBounds(-1, 4, -20, 23, -1, 4),
                        0,
                        0,
                        0,
                        1.0,
                        20
                );
        for (int y = -20; y <= 1; y++) {
            selector.offer(new ReachableBlockSourceCache.SourceCoordinate(2, y, 2));
        }
        ReachableBlockSourceCache.SourceCoordinate usable =
                new ReachableBlockSourceCache.SourceCoordinate(3, 0, 3);

        selector.offer(usable);
        List<ReachableBlockSourceCache.SourceCoordinate> selected = selector.finish();

        assertTrue(selected.size() <= ReachableBlockSourceCache.MAX_SOURCE_CANDIDATES);
        assertTrue(selected.contains(usable));
        assertTrue(SensorTameworkReachableBlockTarget.isSourceInRange(
                usable,
                new Vector3d(3.9, 0.9, 3.9),
                1.0,
                20
        ));
    }

    @Test
    void sharedSourceCoordinatesUseExactNpcHorizontalAndVerticalRange() {
        Vector3d npcPosition = new Vector3d(0.5, 64.0, 0.5);

        assertTrue(SensorTameworkReachableBlockTarget.isSourceInRange(
                new ReachableBlockSourceCache.SourceCoordinate(3, 64, 0),
                npcPosition,
                4.0,
                2
        ));
        assertFalse(SensorTameworkReachableBlockTarget.isSourceInRange(
                new ReachableBlockSourceCache.SourceCoordinate(5, 64, 0),
                npcPosition,
                4.0,
                2
        ));
        assertFalse(SensorTameworkReachableBlockTarget.isSourceInRange(
                new ReachableBlockSourceCache.SourceCoordinate(3, 67, 0),
                npcPosition,
                4.0,
                2
        ));
    }

    @Test
    void noPathCandidateFallsBackToAnotherSharedCandidate() {
        ReachableBlockSourceCache.SourceCoordinate rejected =
                new ReachableBlockSourceCache.SourceCoordinate(1, 64, 1);
        ReachableBlockSourceCache.SourceCoordinate fallback =
                new ReachableBlockSourceCache.SourceCoordinate(2, 64, 2);
        ReachableBlockSourceCache.Snapshot snapshot = new ReachableBlockSourceCache.Snapshot(
                List.of(rejected, fallback)
        );

        ReachableBlockTargetCandidateSelector.Selection selection =
                ReachableBlockTargetCandidateSelector.select(
                        snapshot,
                        source -> rejected.equals(source)
                                ? CandidateResult.empty()
                                : CandidateResult.hit(new Vector3d(source.x() + 0.5, source.y(), source.z() + 0.5))
                );

        assertTrue(selection.validated());
        assertEquals(fallback, selection.source());
    }

    @Test
    void candidateRejectionChecksOnlyTheCurrentTarget() {
        ReachableBlockSourceCache.SourceCoordinate rejected =
                new ReachableBlockSourceCache.SourceCoordinate(1, 64, 1);
        ReachableBlockSourceCache.SourceCoordinate fallback =
                new ReachableBlockSourceCache.SourceCoordinate(2, 64, 2);
        ReachableBlockSourceCache.Snapshot snapshot = new ReachableBlockSourceCache.Snapshot(
                List.of(rejected, fallback)
        );
        UUID npcUuid = UUID.randomUUID();
        AtomicInteger rejectionChecks = new AtomicInteger();

        ReachableBlockTargetCandidateSelector.Selection selection =
                ReachableBlockTargetCandidateSelector.select(
                        snapshot,
                        source -> {
                            Vector3d target = new Vector3d(source.x(), source.y(), source.z());
                            Vector3d accepted = ReachableBlockTargetCandidateSelector.acceptIfNotRejected(
                                    npcUuid,
                                    "Water",
                                    target,
                                    1_000L,
                                    (ignoredNpc, ignoredLabel, candidate, ignoredNow) -> {
                                        rejectionChecks.incrementAndGet();
                                        return rejected.equals(source);
                                    }
                            );
                            return accepted == null
                                    ? CandidateResult.empty()
                                    : CandidateResult.hit(accepted);
                        }
                );

        assertTrue(selection.validated());
        assertEquals(fallback, selection.source());
        assertEquals(2, rejectionChecks.get());
    }

    @Test
    void pendingCandidateRetainsItsSourceCoordinateForResume() {
        ReachableBlockSourceCache.SourceCoordinate source =
                new ReachableBlockSourceCache.SourceCoordinate(1, 64, 1);
        ReachableBlockSourceCache.Snapshot snapshot = new ReachableBlockSourceCache.Snapshot(
                List.of(source)
        );

        ReachableBlockTargetCandidateSelector.Selection selection =
                ReachableBlockTargetCandidateSelector.select(
                        snapshot,
                        ignored -> CandidateResult.pending(new Vector3d(1.5, 64.0, 1.5))
                );

        assertTrue(selection.deferred());
        assertEquals(source, selection.source());
    }

    @Test
    void currentNpcNearestSourceWinsBeforeCandidateResolution() {
        ReachableBlockSourceCache.SourceCoordinate farther =
                new ReachableBlockSourceCache.SourceCoordinate(8, 64, 0);
        ReachableBlockSourceCache.SourceCoordinate nearer =
                new ReachableBlockSourceCache.SourceCoordinate(2, 64, 0);
        ReachableBlockSourceCache.Snapshot snapshot = new ReachableBlockSourceCache.Snapshot(
                List.of(farther, nearer)
        );

        ReachableBlockTargetCandidateSelector.Selection selection =
                ReachableBlockTargetCandidateSelector.select(
                        snapshot,
                        new Vector3d(0.5, 64.0, 0.5),
                        source -> CandidateResult.hit(new Vector3d(source.x(), source.y(), source.z()))
                );

        assertTrue(selection.validated());
        assertEquals(nearer, selection.source());
    }

    @Test
    void targetStateIsStoreScopedAndBoundedByAuthorityKey() {
        ReachableBlockTargetStateCache cache = ReachableBlockTargetStateCache.shared();
        UUID npcUuid = UUID.randomUUID();
        ReachableBlockTargetStateCache.SensorAuthority authority = authority("Water");
        ReachableBlockTargetStateCache.SensorAuthority otherAuthority = authority("Food");
        try (TestEntityComponentStore firstStore = newStore();
             TestEntityComponentStore secondStore = newStore()) {
            cache.clear(firstStore);
            cache.clear(secondStore);
            cache.put(
                    firstStore,
                    npcUuid,
                    authority,
                    ReachableBlockTargetStateCache.State.VALIDATED,
                    new Vector3d(1.5, 64.0, 1.5),
                    new ReachableBlockSourceCache.SourceCoordinate(1, 64, 1),
                    1_000L
            );

            assertTrue(cache.get(firstStore, npcUuid, authority, 1_001L) != null);
            assertTrue(cache.get(firstStore, npcUuid, otherAuthority, 1_001L) == null);
            cache.put(
                    firstStore,
                    npcUuid,
                    otherAuthority,
                    ReachableBlockTargetStateCache.State.VALIDATED,
                    new Vector3d(2.5, 64.0, 2.5),
                    new ReachableBlockSourceCache.SourceCoordinate(2, 64, 2),
                    1_000L
            );
            assertTrue(cache.get(firstStore, npcUuid, authority, 1_001L) != null);
            assertTrue(cache.get(firstStore, npcUuid, otherAuthority, 1_001L) != null);
            assertTrue(cache.get(secondStore, npcUuid, authority, 1_001L) == null);

            cache.clear(firstStore);
            assertTrue(cache.get(firstStore, npcUuid, authority, 1_001L) == null);

            for (int index = 0; index <= ReachableBlockTargetStateCache.MAX_ENTRIES_PER_STORE; index++) {
                cache.put(
                        secondStore,
                        new UUID(0L, index + 1L),
                        authority,
                        ReachableBlockTargetStateCache.State.VALIDATED,
                        new Vector3d(index, 64.0, 0.0),
                        new ReachableBlockSourceCache.SourceCoordinate(index, 64, 0),
                        1_000L
                );
            }

            assertEquals(
                    ReachableBlockTargetStateCache.MAX_ENTRIES_PER_STORE,
                    cache.sizeForTests(secondStore)
            );
            assertTrue(cache.get(secondStore, new UUID(0L, 1L), authority, 1_001L) == null);
            assertTrue(cache.get(
                    secondStore,
                    new UUID(0L, ReachableBlockTargetStateCache.MAX_ENTRIES_PER_STORE + 1L),
                    authority,
                    1_001L
            ) != null);
        }
    }

    private static ReachableBlockTargetStateCache.SensorAuthority authority(String label) {
        return ReachableBlockTargetStateCache.SensorAuthority.from(
                label,
                new ReachableBlockSourceCache.SensorConfiguration(
                        null,
                        List.of("hytale:water")
                ),
                12.0,
                4,
                SensorTameworkReachableBlockTarget.DEFAULT_APPROACH_RADIUS
        );
    }

    private static TestEntityComponentStore newStore() {
        return new TestEntityComponentStore(new EntityStore(null));
    }

    @Test
    void cachedTargetReuseRequiresTheRetainedSourceToRemainInRange() {
        Vector3d npcPosition = new Vector3d(0.5, 64.0, 0.5);
        Vector3d projectedTarget = new Vector3d(0.5, 64.0, 0.5);

        assertFalse(SensorTameworkReachableBlockTarget.isCachedTargetUsable(
                new ReachableBlockSourceCache.SourceCoordinate(8, 64, 0),
                projectedTarget,
                npcPosition,
                4.0,
                2
        ));
        assertTrue(SensorTameworkReachableBlockTarget.isCachedTargetUsable(
                new ReachableBlockSourceCache.SourceCoordinate(3, 64, 0),
                projectedTarget,
                npcPosition,
                4.0,
                2
        ));
    }
}
