package com.alechilles.alecstamework.companion.migrate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LegacySourceTest {
    /** A cleanly stopped server leaves no write-ahead log; a database of a gigabyte must not be copied to be read. */
    @Test
    void aDatabaseWithNoPendingLogIsReadInPlaceWithoutACopy(@TempDir Path temp) throws Exception {
        Path data = temp.resolve("Data");
        ImportFixtures.state(data);
        Files.write(data.resolve(LegacySource.STATE_FILE + "-wal"), new byte[0]);
        Map<String, String> before = ImportFixtures.contents(data);
        Path scratch = temp.resolve("scratch");

        LegacyRows.State state = LegacyReader.read(List.of(data), scratch).state();

        assertNotNull(state);
        assertEquals(4, state.profiles().size());
        assertFalse(Files.exists(scratch), "no copy is made");
        assertEquals(before, ImportFixtures.contents(data), "no file is added, and every file is byte for byte the same");
    }
}
