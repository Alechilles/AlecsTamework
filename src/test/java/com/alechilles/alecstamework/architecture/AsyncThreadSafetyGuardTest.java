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
 * Enforces async thread-safety guardrails for runtime system classes.
 */
class AsyncThreadSafetyGuardTest {
    private static final Path MAIN_JAVA = Paths.get("src", "main", "java");

    private static final List<String> ASYNC_TOKENS = List.of(
            "CompletableFuture.runAsync(",
            "CompletableFuture.supplyAsync(",
            "CompletableFuture.delayedExecutor(",
            "new Thread(",
            "Executors.new"
    );

    private static final List<String> PLAYER_AFFINE_TOKENS = List.of(
            "PlayerRef.getComponent(",
            ".getPlayerRef()",
            "Universe.getPlayers(",
            "getWorld().getPlayerRefs("
    );

    @Test
    void asyncSystemWorkMarshalsBackToWorldThread() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path systemFile : listSystemFiles()) {
            String content = Files.readString(systemFile, StandardCharsets.UTF_8);
            if (!containsAny(content, ASYNC_TOKENS)) {
                continue;
            }
            if (content.contains("world.execute(")) {
                continue;
            }
            violations.add(toUnixRelativePath(systemFile));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "System files with async work must marshal world/entity mutations through world.execute(...).\n"
                        + "Violations:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void asyncSystemWorkAvoidsPlayerRefApis() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path systemFile : listSystemFiles()) {
            String content = Files.readString(systemFile, StandardCharsets.UTF_8);
            if (!containsAny(content, ASYNC_TOKENS)) {
                continue;
            }
            if (!containsAny(content, PLAYER_AFFINE_TOKENS)) {
                continue;
            }
            violations.add(toUnixRelativePath(systemFile));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "System files that schedule async work must not access PlayerRef-affine APIs. "
                        + "Capture UUIDs and resolve components inside world.execute(...).\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    private static boolean containsAny(String content, List<String> tokens) {
        for (String token : tokens) {
            if (content.contains(token)) {
                return true;
            }
        }
        return false;
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
