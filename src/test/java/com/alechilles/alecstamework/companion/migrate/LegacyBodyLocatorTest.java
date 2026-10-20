package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Entry;
import com.alechilles.alecstamework.companion.migrate.LegacyAliases.Kind;
import com.alechilles.alecstamework.companion.migrate.LegacyBodyLocate.SavedEntity;
import com.alechilles.alecstamework.companion.store.MemoryCompanionFileIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyBodyLocatorTest {
    private static final Path ROOT = Path.of("Companions");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID FIRST_NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SECOND_NPC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final CompanionIndex index = new CompanionIndex(() -> 9_000L, (before, after) -> { });
    private final MemoryCompanionFileIo io = new MemoryCompanionFileIo();
    private final LegacyAliases aliases = new LegacyAliases(Map.of(
            FIRST_NPC, new Entry(FIRST, Kind.CURRENT), SECOND_NPC, new Entry(SECOND, Kind.CURRENT)));
    private Predicate<UUID> hasBody = id -> false;

    private LegacyBodyLocator locator(LegacyAliases known) {
        return LegacyBodyLocator.create(new LegacyBodyLocate(index, known, id -> hasBody.test(id), snapshot -> { },
                () -> 7_000L), io, ROOT, () -> 7_000L, millis -> { });
    }

    private void neverSeen(UUID profileId, UUID npc) {
        index.insert(CompanionRecord.builder(profileId, "Tamed_Wolf", CompanionLocation.live("default", 0, 0, 0))
                .currentNpcUuid(npc).build());
    }

    /** A world of stored chunks with indexes 10, 20, 30 and so on, with bodies in some of them. */
    private static final class World implements LegacyBodyLocator.Chunks {
        private final String name;
        private final int chunks;
        private final Map<Long, UUID> bodies = new HashMap<>();
        private final List<Long> loaded = new ArrayList<>();
        /** The world closes once this many chunks were loaded; below 0 it stays open. */
        private int closeAfter = -1;
        /** Chunks from this index on cannot be read; below 0 every chunk reads. */
        private long unreadableFrom = -1;

        private World(String name) {
            this(name, 10);
        }

        private World(String name, int chunks) {
            this.name = name;
            this.chunks = chunks;
        }

        @Override
        public String world() {
            return name;
        }

        @Override
        public long[] indexes() {
            // Descending on purpose: storage gives no order.
            long[] indexes = new long[chunks];
            for (int i = 0; i < chunks; i++) {
                indexes[i] = (chunks - i) * 10L;
            }
            return indexes;
        }

        @Override
        public List<SavedEntity> load(long index) throws IOException {
            loaded.add(index);
            if (unreadableFrom >= 0 && index >= unreadableFrom) {
                throw new IOException("unreadable chunk " + index);
            }
            UUID body = bodies.get(index);
            return body == null ? List.of() : List.of(LegacyBodyLocateTest.saved(body, name, index, 64, 1));
        }

        @Override
        public boolean open() {
            return closeAfter < 0 || loaded.size() < closeAfter;
        }

        @Override
        public double tickLoad() {
            return 0.1;
        }
    }

    @Test
    void theReadStopsAsSoonAsNoNeverSeenCompanionIsLeft() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        World world = new World("orbis");
        world.bodies.put(30L, FIRST_NPC);
        LegacyBodyLocator locator = locator(aliases);
        locator.offer(world);

        locator.runPass();

        assertEquals(List.of(10L, 20L, 30L), world.loaded);
        assertEquals(30.0, index.get(FIRST).location().x());
        assertNull(locator(aliases), "nothing is left to do at the next start");
    }

    @Test
    void whatIsNotFoundInAnyWorldBecomesLostOnceEveryWorldWasRead() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        neverSeen(SECOND, SECOND_NPC);
        World orbis = new World("orbis");
        World nether = new World("nether");
        nether.bodies.put(20L, FIRST_NPC);
        LegacyBodyLocator locator = locator(aliases);
        locator.offer(orbis);
        locator.offer(nether);

        locator.runPass();

        assertEquals(10, orbis.loaded.size());
        assertEquals(10, nether.loaded.size());
        assertEquals("nether", index.get(FIRST).location().world());
        assertEquals(LocationKind.LOST, index.get(SECOND).location().kind());
        assertEquals(LegacyBodyResolution.CAUSE_BODY_NOT_FOUND, index.get(SECOND).location().cause());
        assertNull(locator(aliases));
    }

    @Test
    void aWorldThatWasNotReadToItsEndMarksNothingLostAndTheNextStartGoesOnFromItsCursor() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        World finished = new World("nether");
        World removedMidRead = new World("orbis");
        removedMidRead.closeAfter = 4;
        LegacyBodyLocator first = locator(aliases);
        first.offer(finished);
        first.offer(removedMidRead);

        first.runPass();

        assertEquals(10, finished.loaded.size());
        assertEquals(List.of(10L, 20L, 30L, 40L), removedMidRead.loaded);
        assertTrue(index.get(FIRST).neverSighted(), "a world that was not read to its end proves nothing");

        World netherAgain = new World("nether");
        World orbisAgain = new World("orbis");
        orbisAgain.bodies.put(90L, FIRST_NPC);
        LegacyBodyLocator resumed = locator(aliases);
        assertNotNull(resumed, "the pass is still unfinished");
        resumed.offer(netherAgain);
        resumed.offer(orbisAgain);
        resumed.runPass();

        assertTrue(netherAgain.loaded.isEmpty(), "a world that was read to its end is not read again");
        assertEquals(List.of(50L, 60L, 70L, 80L, 90L), orbisAgain.loaded);
        assertFalse(index.get(FIRST).neverSighted());
        assertEquals(90.0, index.get(FIRST).location().x());
    }

    @Test
    void manyUnreadableChunksInARowFailTheWorldInsteadOfProvingItsCompanionsAbsent() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        World broken = new World("orbis", 40);
        broken.unreadableFrom = 60;
        LegacyBodyLocator first = locator(aliases);
        first.offer(broken);

        first.runPass();

        assertEquals(5 + LegacyBodyLocator.MAX_CONSECUTIVE_FAILURES, broken.loaded.size());
        assertTrue(index.get(FIRST).neverSighted());

        World repaired = new World("orbis", 40);
        repaired.bodies.put(70L, FIRST_NPC);
        LegacyBodyLocator resumed = locator(aliases);
        resumed.offer(repaired);
        resumed.runPass();

        assertEquals(List.of(60L, 70L), repaired.loaded, "the chunks that failed are read again");
        assertEquals(70.0, index.get(FIRST).location().x());
    }

    @Test
    void aFewUnreadableChunksDoNotStopAWorld() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        World world = new World("orbis");
        world.unreadableFrom = 90;
        LegacyBodyLocator locator = locator(aliases);
        locator.offer(world);

        locator.runPass();

        assertEquals(10, world.loaded.size());
        assertEquals(LocationKind.LOST, index.get(FIRST).location().kind());
    }

    @Test
    void anEntityThatCannotBeCheckedIsSkippedAndThePassGoesOn() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        neverSeen(SECOND, SECOND_NPC);
        World world = new World("orbis");
        world.bodies.put(20L, FIRST_NPC);
        world.bodies.put(40L, SECOND_NPC);
        hasBody = id -> {
            if (id.equals(FIRST)) {
                throw new IllegalStateException("broken entity");
            }
            return false;
        };
        LegacyBodyLocator locator = locator(aliases);
        locator.offer(world);

        assertEquals(LegacyBodyLocator.WorldResult.FINISHED, locator.scanWorld(world));

        assertEquals(10, world.loaded.size());
        assertEquals(40.0, index.get(SECOND).location().x());
        assertTrue(index.get(FIRST).neverSighted());
    }

    @Test
    void aStoppedPassReadsNothingMoreAndMarksNothingLost() throws Exception {
        neverSeen(FIRST, FIRST_NPC);
        World world = new World("orbis");
        LegacyBodyLocator locator = locator(aliases);
        locator.offer(world);
        locator.stop();

        locator.runPass();

        assertTrue(world.loaded.isEmpty());
        assertTrue(index.get(FIRST).neverSighted());
    }

    @Test
    void nothingIsCreatedForAStoreThatWasNeverImportedOrHasNothingLeftToFind() {
        neverSeen(FIRST, FIRST_NPC);
        assertNull(locator(LegacyAliases.EMPTY), "never imported");

        CompanionRecord record = index.get(FIRST);
        index.update(FIRST, record.revision(), b -> b.location(CompanionLocation.live("default", 3, 70, 3)));
        assertNull(locator(aliases), "imported, every companion seen, no pass unfinished");
        assertTrue(io.writtenPaths().isEmpty());
    }

    @Test
    void thePauseGrowsWithTheChunkTimeAndTheTickLoad() {
        assertEquals(5L, LegacyBodyLocator.pauseMs(1, 0.2));
        assertEquals(40L, LegacyBodyLocator.pauseMs(40, 0.2));
        assertEquals(25L, LegacyBodyLocator.pauseMs(1, 0.7));
        assertEquals(100L, LegacyBodyLocator.pauseMs(1, 1.4));
        assertEquals(400L, LegacyBodyLocator.pauseMs(400, 1.4));
        assertEquals(5L, LegacyBodyLocator.pauseMs(1, Double.NaN));
        assertEquals(1_000L, LegacyBodyLocator.pauseMs(30_000, 0.2));
    }
}
