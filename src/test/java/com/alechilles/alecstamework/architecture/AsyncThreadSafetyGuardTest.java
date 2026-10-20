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

    /**
     * Calls that wait. Systems run on the world thread, so any of these stalls the whole world
     * tick until a future, lock or storage call finishes.
     */
    private static final List<String> BLOCKING_TOKENS = List.of(
            ".toCompletableFuture().join(",
            "Thread.sleep(",
            "LockSupport.park",
            "CountDownLatch",
            ".await(",
            "java.sql.",
            "DataSource"
    );

    private static final List<String> DIRECT_PLAYER_COMPONENT_TOKENS = List.of(
            "PlayerRef.getComponent(Player",
            ".getHolder().getComponent(Player.getComponentType())"
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

    @Test
    void systemsNeverBlockTheWorldThread() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path systemFile : listSystemFiles()) {
            String content = Files.readString(systemFile, StandardCharsets.UTF_8);
            for (String token : BLOCKING_TOKENS) {
                if (content.contains(token)) {
                    violations.add(toUnixRelativePath(systemFile) + " contains " + token);
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Tick systems run on the world thread and must not wait on futures, locks, sleeps "
                        + "or storage. Queue the work and apply its result on a later tick or through "
                        + "world.execute(...).\nViolations:\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void runtimeCodeAvoidsDirectPlayerComponentHolderLookups() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : listJavaFiles()) {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (containsAny(line, DIRECT_PLAYER_COMPONENT_TOKENS)) {
                    violations.add(toUnixRelativePath(sourceFile) + ":" + (i + 1) + " -> " + line);
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Runtime code must not read Player components directly from PlayerRef/Holder. "
                        + "Use PlayerRef for identity or resolve Player through the active world/store.\nViolations:\n"
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
        return listJavaFiles().stream()
                .filter(path -> path.getFileName().toString().endsWith("System.java"))
                .toList();
    }

    private static List<Path> listJavaFiles() throws IOException {
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
