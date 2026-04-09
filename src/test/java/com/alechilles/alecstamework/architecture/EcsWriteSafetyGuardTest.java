package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces ECS write-phase safety for runtime system classes.
 */
class EcsWriteSafetyGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final List<String> FORBIDDEN_WRITE_TOKENS = List.of(
            "store.putComponent(",
            "store.removeComponent(",
            "store.tryRemoveComponent(",
            "store.addComponent("
    );

    private static final String LEGACY_CAPTURE_SYSTEM =
            "com/alechilles/alecstamework/items/CommandCoopManagedWildCaptureSystem.java";
    private static final Set<String> LEGACY_CAPTURE_ALLOWED_LINES = Set.of(
            "store.putComponent(reference, type, componentCopy);",
            "deferredStore.putComponent(reference, type, deferredCopy);"
    );

    @Test
    void systemClassesDoNotMutateStoreDirectly() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path systemFile : listSystemFiles()) {
            String relativePath = toUnixRelativePath(systemFile);
            List<String> lines = Files.readAllLines(systemFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!containsForbiddenWrite(line)) {
                    continue;
                }
                if (isAllowedLegacyException(relativePath, line)) {
                    continue;
                }
                violations.add(relativePath + ":" + (i + 1) + " -> " + line);
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Direct store mutations in system classes are forbidden. Route writes through CommandBuffer or a "
                        + "world-thread callback.\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    private static boolean containsForbiddenWrite(String line) {
        for (String token : FORBIDDEN_WRITE_TOKENS) {
            if (line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedLegacyException(String relativePath, String line) {
        if (!LEGACY_CAPTURE_SYSTEM.equals(relativePath)) {
            return false;
        }
        return LEGACY_CAPTURE_ALLOWED_LINES.contains(line);
    }

    private static List<Path> listSystemFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("System.java"))
                    .sorted()
                    .toList();
        }
    }

    private static String toUnixRelativePath(Path path) {
        return MAIN_JAVA.relativize(path).toString().replace('\\', '/');
    }
}
