package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Protects Tamework's public integration boundary with the embedded Patchwork runtime.
 */
class PatchworkDependencyBoundaryTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final String PATCHWORK_PACKAGE = "com.alechilles.patchwork.";
    private static final String EMBEDDED_PATCHWORK_PACKAGE = "com.alechilles.patchwork.embedded.";
    private static final String LEGACY_PATCH_IMPORT = "import com.alechilles.alecstamework.assets.patches.";
    private static final Set<String> LEGACY_PATCH_IMPORT_ALLOWLIST = Set.of(
            "com/alechilles/alecstamework/Tamework.java",
            "com/alechilles/alecstamework/config/overrides/TwConfigOverrideManager.java",
            "com/alechilles/alecstamework/commands/TameworkPatchesCommand.java",
            "com/alechilles/alecstamework/commands/TameworkPatchesReloadCommand.java",
            "com/alechilles/alecstamework/commands/TameworkPatchesSelfTestCommand.java",
            "com/alechilles/alecstamework/commands/TameworkPatchesStatusCommand.java"
    );

    @Test
    void tameworkImportsOnlyPatchworksEmbeddedApi() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sourceFiles = Files.walk(MAIN_JAVA)) {
            sourceFiles.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectPatchworkImportViolations(path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Tamework may import only com.alechilles.patchwork.embedded.*:\n" + String.join("\n", violations)
        );
    }

    @Test
    void legacyPatcherImportsAreLimitedToTaskFiveMigrationHoldovers() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sourceFiles = Files.walk(MAIN_JAVA)) {
            sourceFiles.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> collectLegacyPatcherImportViolations(path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Legacy asset-patcher imports must stay within the Task 5 migration allowlist:\n"
                        + String.join("\n", violations)
        );
    }

    private static void collectPatchworkImportViolations(Path sourceFile, List<String> violations) {
        try {
            for (String line : Files.readAllLines(sourceFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                String importedReference = importedReference(trimmed);
                if (importedReference != null
                        && importedReference.startsWith(PATCHWORK_PACKAGE)
                        && !importedReference.startsWith(EMBEDDED_PATCHWORK_PACKAGE)) {
                    violations.add(MAIN_JAVA.relativize(sourceFile) + ": " + trimmed);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + sourceFile, exception);
        }
    }

    private static String importedReference(String line) {
        if (!line.startsWith("import ")) {
            return null;
        }
        String reference = line.substring("import ".length()).trim();
        if (reference.startsWith("static ")) {
            reference = reference.substring("static ".length()).trim();
        }
        return reference;
    }

    private static void collectLegacyPatcherImportViolations(Path sourceFile, List<String> violations) {
        String relativePath = MAIN_JAVA.relativize(sourceFile).toString().replace('\\', '/');
        if (relativePath.startsWith("com/alechilles/alecstamework/assets/patches/")) {
            return;
        }

        try {
            for (String line : Files.readAllLines(sourceFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(LEGACY_PATCH_IMPORT) && !LEGACY_PATCH_IMPORT_ALLOWLIST.contains(relativePath)) {
                    violations.add(relativePath + ": " + trimmed);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + sourceFile, exception);
        }
    }
}
