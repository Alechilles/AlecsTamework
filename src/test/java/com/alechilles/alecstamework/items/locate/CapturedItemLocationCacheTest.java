package com.alechilles.alecstamework.items.locate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.CaptureKey;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Holder;
import com.alechilles.alecstamework.items.locate.CapturedItemLocationIndex.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapturedItemLocationCacheTest {
    @TempDir
    Path directory;

    @Test
    void reloadsLastKnownSightingAsStale() throws Exception {
        Path file = directory.resolve("captured-item-locations.json");
        CaptureKey capture = new CaptureKey(
                "profile-a", "snapshot-a", UUID.fromString(
                        "00000000-0000-0000-0000-000000000001")
        );
        Holder holder = new Holder(
                Kind.CONTAINER, "world", "1,2,3", "Furniture_Chest_Wood", 1, 2, 3
        );
        CapturedItemLocationIndex saved = new CapturedItemLocationIndex();
        saved.observe(holder, Map.of(capture, "Tamework_Soul_Lantern"), 123L);

        try (CapturedItemLocationCache cache = new CapturedItemLocationCache(
                file, saved
        )) {
            cache.save();
        }

        CapturedItemLocationIndex restored = new CapturedItemLocationIndex();
        try (CapturedItemLocationCache cache = new CapturedItemLocationCache(
                file, restored
        )) {
            cache.load();
        }

        var sighting = restored.find(capture).orElseThrow();
        assertEquals(holder, sighting.holder());
        assertEquals("Tamework_Soul_Lantern", sighting.itemId());
        assertEquals(123L, sighting.observedAtMs());
        assertFalse(sighting.loaded());
    }

    @Test
    void loadsOlderSightingsWithoutItemNames() throws Exception {
        Path file = directory.resolve("old-locations.json");
        Files.writeString(file, """
                {"version":1,"sightings":[{
                  "capture":{"profileId":"profile-a","snapshotId":"snapshot-a",
                    "npcUuid":"00000000-0000-0000-0000-000000000001"},
                  "holder":{"kind":"CONTAINER","worldName":"world","id":"1,2,3",
                    "name":"","x":1,"y":2,"z":3},
                  "observedAtMs":123,"loaded":true}]}
                """);
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();
        new CapturedItemLocationCache(file, index).load();
        var sighting = index.find(new CaptureKey("profile-a", "snapshot-a",
                UUID.fromString("00000000-0000-0000-0000-000000000001"))).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(sighting.itemId());
        assertEquals("", sighting.holder().name());
        assertFalse(sighting.loaded());
    }

    @Test
    void movingAnItemUpdatesItsDescriptionAndKeepsItWhenUnloaded() {
        CaptureKey capture = new CaptureKey("profile", "snapshot", UUID.randomUUID());
        Holder player = new Holder(Kind.PLAYER, "world", "alice", "Alice", 0, 0, 0);
        Holder chest = new Holder(Kind.CONTAINER, "world", "1,2,3", "Wooden_Chest", 1, 2, 3);
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();
        index.observe(player, Map.of(capture, "Old_Lantern"), 1);
        index.observe(chest, Map.of(capture, "Soul_Lantern"), 2);
        index.observe(player, List.of(), 3);
        index.unload(chest);
        var sighting = index.find(capture).orElseThrow();
        assertEquals("Soul_Lantern", sighting.itemId());
        assertEquals(chest, sighting.holder());
        assertFalse(sighting.loaded());
    }

    @Test
    void reportsCorruptCacheInsteadOfTreatingItAsAnEmptyLocationIndex()
            throws Exception {
        Path file = directory.resolve("captured-item-locations.json");
        Files.writeString(file, "not json");
        CapturedItemLocationIndex index = new CapturedItemLocationIndex();

        try (CapturedItemLocationCache cache = new CapturedItemLocationCache(
                file, index
        )) {
            assertThrows(IOException.class, cache::load);
        }
    }
}
