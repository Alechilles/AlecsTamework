package com.alechilles.alecstamework.npc.ambient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Catches discovery regressions that turn unloaded/no-water terrain into an unbounded scan. */
class AmbientHerdWaterBankSearchTest {
    @Test
    void concurrentDistantBankSearchesFinishBeforeTheCoordinatorDeadline() {
        Terrain terrain = new Terrain();
        for (int x = 58; x < 72; x++) for (int z = -8; z < 9; z++) terrain.ground(x, 0, z);
        terrain.water(64, 0, 0);
        var search = new AmbientHerdWaterBankSearch();
        var cursors = new AmbientHerdWaterBankSearch.SearchCursor[4];
        var completed = new boolean[4];
        for (int i = 0; i < cursors.length; i++) {
            cursors[i] = search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.9, 2.04);
        }
        var budget = new AmbientHerdWorkBudget();
        UUID world = UUID.randomUUID();
        long previousTick = Long.MIN_VALUE;
        int found = 0;
        for (long now = 0; now < AmbientHerdCoordinator.DISCOVERY_TIMEOUT_MS && found < 4; now += 10) {
            if (!AmbientHerdCoordinator.isTickDue(previousTick, now)) continue;
            previousTick = now;
            for (int i = 0; i < cursors.length; i++) {
                if (completed[i]) continue;
                var result = search.step(cursors[i], terrain, budget, world, now);
                if (result.status() == AmbientHerdWaterBankSearch.SearchStatus.FOUND) {
                    completed[i] = true;
                    found++;
                }
            }
        }
        assertEquals(4, found, "Admitted herds must be able to finish bounded discovery before timing out");
        assertTrue(terrain.reads * 2 <= 4 * 4_096);
    }

    @Test
    void findsAUsableDryBankWithoutScanningPastTheFiniteCursor() {
        Terrain terrain = new Terrain();
        terrain.water(16, 0, 0);
        for (int x = 10; x < 24; x++) for (int z = -8; z < 9; z++) terrain.ground(x, 0, z);
        terrain.water(16, 0, 0);
        AmbientHerdWaterBankSearch search = new AmbientHerdWaterBankSearch();
        AmbientHerdWaterBankSearch.SearchCursor cursor =
                search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.0, 2.0);
        AmbientHerdWaterBankSearch.StepResult result = run(search, cursor, terrain);
        assertEquals(AmbientHerdWaterBankSearch.SearchStatus.FOUND, result.status());
        assertNotNull(result.patch());
        assertTrue(result.patch().slots().size() >= 2);
        assertTrue(result.patch().staging().size() >= 2);
        assertTrue(terrain.reads * 2 <= 4_096);
    }

    @Test
    void allLoadedDryTerrainEndsInMissAfterTheFixedSampleSet() {
        Terrain terrain = new Terrain();
        AmbientHerdWaterBankSearch search = new AmbientHerdWaterBankSearch();
        AmbientHerdWaterBankSearch.StepResult result =
                run(
                        search,
                        search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.0, 2.0),
                        terrain);
        assertEquals(AmbientHerdWaterBankSearch.SearchStatus.MISS, result.status());
        assertTrue(terrain.reads * 2 <= 4_096);
    }

    @Test
    void budgetExhaustionBeforeWaterLeavesTheCursorAndTerrainUntouchedUntilTheNextWindow() {
        Terrain terrain = new Terrain();
        terrain.water(16, 0, 0);
        AmbientHerdWaterBankSearch search = new AmbientHerdWaterBankSearch();
        AmbientHerdWaterBankSearch.SearchCursor cursor =
                search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.0, 2.0);
        AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
        UUID world = UUID.randomUUID();
        assertTrue(budget.claim(world, AmbientHerdWorkBudget.Work.POINT_READ, 32, 0L));
        assertEquals(
                AmbientHerdWaterBankSearch.SearchStatus.PENDING,
                search.step(cursor, terrain, budget, world, 0L).status());
        assertEquals(0, terrain.reads);
        assertTrue(
                search.step(cursor, terrain, budget, world, 50L).status()
                        != AmbientHerdWaterBankSearch.SearchStatus.MISS);
    }

    @Test
    void wideFootprintResumesAcrossWindowsAndRejectsLavaBanks() {
        Terrain safe = new Terrain();
        safe.water(16, 0, 0);
        for (int x = 10; x < 24; x++) for (int z = -8; z < 9; z++) safe.ground(x, 0, z);
        safe.water(16, 0, 0);
        AmbientHerdWaterBankSearch search = new AmbientHerdWaterBankSearch();
        assertEquals(
                AmbientHerdWaterBankSearch.SearchStatus.FOUND,
                run(search, search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.9, 2.04), safe)
                        .status());
        Terrain lava = new Terrain();
        lava.water(16, 0, 0);
        for (int x = 10; x < 24; x++) for (int z = -8; z < 9; z++) lava.lava(x, 0, z);
        lava.water(16, 0, 0);
        assertEquals(
                AmbientHerdWaterBankSearch.SearchStatus.MISS,
                run(search, search.newCursor(new AmbientHerdPoint(.5, 0, .5), 0L, 1.9, 2.04), lava)
                        .status());
    }

    private static AmbientHerdWaterBankSearch.StepResult run(
            AmbientHerdWaterBankSearch search,
            AmbientHerdWaterBankSearch.SearchCursor cursor,
            Terrain terrain) {
        AmbientHerdWorkBudget budget = new AmbientHerdWorkBudget();
        UUID world = UUID.randomUUID();
        long now = 0L;
        AmbientHerdWaterBankSearch.StepResult result;
        do {
            result = search.step(cursor, terrain, budget, world, now);
            now += 50L;
        } while (result.status() == AmbientHerdWaterBankSearch.SearchStatus.PENDING
                && now < 30_000L);
        return result;
    }

    private static final class Terrain implements AmbientHerdWaterBankSearch.WorldAccess {
        private final Map<String, AmbientHerdWaterBankSearch.TerrainCell> cells = new HashMap<>();
        int reads;

        void water(int x, int y, int z) {
            cells.put(
                    key(x, y, z),
                    new AmbientHerdWaterBankSearch.TerrainCell(true, true, false, false));
        }

        void ground(int x, int y, int z) {
            cells.put(
                    key(x, y, z),
                    new AmbientHerdWaterBankSearch.TerrainCell(true, false, true, false));
        }

        void lava(int x, int y, int z) {
            cells.put(
                    key(x, y, z),
                    new AmbientHerdWaterBankSearch.TerrainCell(true, false, false, true));
        }

        @Override
        public AmbientHerdWaterBankSearch.TerrainCell read(AmbientHerdPoint point) {
            reads++;
            return cells.getOrDefault(
                    key(point.blockX(), point.blockY(), point.blockZ()),
                    new AmbientHerdWaterBankSearch.TerrainCell(true, false, false, false));
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
