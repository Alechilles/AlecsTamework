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
    private static final List<ForbiddenUsage> FORBIDDEN_USAGES = List.of(
            new ForbiddenUsage(
                    "Update 5 removed Hytale Vector3d/Vector3f/Vector3i classes. Use org.joml vectors instead.",
                    List.of(
                            "com.hypixel.hytale.math.vector.Vector3d",
                            "com.hypixel.hytale.math.vector.Vector3f",
                            "com.hypixel.hytale.math.vector.Vector3i"
                    ),
                    List.of()
            ),
            new ForbiddenUsage(
                    "Update 5 moved active hotbar state to inventory components. Use PlayerInventoryAccess.",
                    List.of(
                            ".getActiveHotbarItem(",
                            ".getActiveHotbarSlot("
                    ),
                    List.of("PlayerInventoryAccess.")
            ),
            new ForbiddenUsage(
                    "Update 5 vector migration should not reintroduce removed vector helper methods.",
                    List.of(
                            ".distanceTo(",
                            ".squaredLength(",
                            ".subtract("
                    ),
                    List.of()
            )
    );

    @Test
    void sourceAvoidsRemovedUpdate5Apis() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : listMainJavaFiles()) {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                for (ForbiddenUsage forbiddenUsage : FORBIDDEN_USAGES) {
                    if (forbiddenUsage.matches(line)) {
                        violations.add(toUnixRelativePath(sourceFile) + ":" + (i + 1)
                                + " -> " + forbiddenUsage.description() + " -> " + line.trim());
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Update 5 compatibility guard found removed API usage.\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    private record ForbiddenUsage(String description, List<String> tokens, List<String> allowedSnippets) {
        boolean matches(String line) {
            for (String token : tokens) {
                if (line.contains(token) && !isAllowed(line)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isAllowed(String line) {
            for (String allowedSnippet : allowedSnippets) {
                if (line.contains(allowedSnippet)) {
                    return true;
                }
            }
            return false;
        }
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
