package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLifecycleDiagnosticsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void freshSchemaProducesBoundedReadOnlyLifecycleSummaries() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            List<String> lines = new CommandLifecycleDiagnosticsService(runtime).overview();

            assertEquals(2, lines.size());
            assertTrue(lines.get(0).startsWith("Command-family rosters:"));
            assertTrue(lines.get(1).startsWith("Timed summons:"));
            assertTrue(lines.stream().noneMatch(line -> line.contains("diagnostic unavailable")));
        }
    }
}
