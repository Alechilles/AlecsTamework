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
                Kind.PLAYER, "world", "alice", "Alice", 1, 2, 3
        );
        CapturedItemLocationIndex saved = new CapturedItemLocationIndex();
        saved.observe(holder, List.of(capture), 123L);

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
        assertEquals(123L, sighting.observedAtMs());
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
