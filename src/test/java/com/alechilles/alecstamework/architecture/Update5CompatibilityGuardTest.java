package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces source-level Update 5 API migrations.
 */
class Update5CompatibilityGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");
    private static final List<String> REMOVED_ACTIVE_HOTBAR_TOKENS = List.of(
            ".getActiveHotbarItem(",
            ".getActiveHotbarSlot("
    );

    @Test
    void activeHotbarAccessUsesInventoryComponents() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : listMainJavaFiles()) {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (containsAny(line, REMOVED_ACTIVE_HOTBAR_TOKENS)) {
                    if (line.contains("PlayerInventoryAccess.")) {
                        continue;
                    }
                    violations.add(toUnixRelativePath(sourceFile) + ":" + (i + 1) + " -> " + line.trim());
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Update 5 moved active hotbar state to inventory components. "
                        + "Use PlayerInventoryAccess instead of Inventory active-hotbar getters.\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    private static boolean containsAny(String line, List<String> tokens) {
        for (String token : tokens) {
            if (line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> listMainJavaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MAIN_JAVA)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String toUnixRelativePath(Path path) {
        return MAIN_JAVA.relativize(path).toString().replace('\\', '/');
    }
}
