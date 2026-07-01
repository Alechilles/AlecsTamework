package com.alechilles.alecstamework.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SingletonTickingSystemStateGuardTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    @Test
    void tickingSystemsDoNotDeclarePlainSharedHashMaps() throws IOException {
        List<Path> offenders;
        try (var stream = Files.walk(SOURCE_ROOT)) {
            offenders = stream
                    .filter(path -> path.toString().endsWith("System.java"))
                    .filter(SingletonTickingSystemStateGuardTest::containsTickingSystem)
                    .filter(SingletonTickingSystemStateGuardTest::containsTopLevelPlainSharedHashMapField)
                    .toList();
        }

        assertTrue(offenders.isEmpty(), "Plain HashMap fields in registered ticking systems: " + offenders);
    }

    @Test
    void tickingSystemsDoNotDeclareSharedNextSweepField() throws IOException {
        List<Path> offenders;
        try (var stream = Files.walk(SOURCE_ROOT)) {
            offenders = stream
                    .filter(path -> path.toString().endsWith("System.java"))
                    .filter(SingletonTickingSystemStateGuardTest::containsTickingSystem)
                    .filter(SingletonTickingSystemStateGuardTest::containsTopLevelSharedNextSweepField)
                    .toList();
        }

        assertTrue(offenders.isEmpty(), "Shared nextSweepAtMs fields in registered ticking systems: " + offenders);
    }

    private static boolean containsTickingSystem(Path path) {
        return read(path).contains("extends TickingSystem<");
    }

    private static boolean containsTopLevelPlainSharedHashMapField(Path path) {
        return read(path).lines()
                .anyMatch(line -> line.startsWith("    private final Map<") && line.contains("new HashMap<"));
    }

    private static boolean containsTopLevelSharedNextSweepField(Path path) {
        return read(path).lines().anyMatch(line -> line.equals("    private long nextSweepAtMs;"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
