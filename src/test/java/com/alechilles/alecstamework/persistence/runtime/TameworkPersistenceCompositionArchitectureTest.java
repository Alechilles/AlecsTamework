package com.alechilles.alecstamework.persistence.runtime;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the single production composition root for replacement persistence. */
class TameworkPersistenceCompositionArchitectureTest {
    private static final Path MAIN = Path.of(
            "src/main/java/com/alechilles/alecstamework"
    );
    private static final Path TAMEWORK = MAIN.resolve("Tamework.java");
    private static final Path COMPOSITION =
            MAIN.resolve("TameworkPersistenceComposition.java");
    private static final Path COMMAND_ROOT =
            MAIN.resolve("commands/TameworkCommandRoot.java");
    private static final Path DEBUG_DB_COMMAND =
            MAIN.resolve("commands/TameworkDebugDbCommand.java");
    private static final Path LEGACY_SQLITE =
            MAIN.resolve("persistence/sqlite");
    private static final Path LEGACY_BRIDGES =
            MAIN.resolve("persistence/legacy");

    @Test
    void persistenceBootstrapHasOneProductionConstructionSite() throws Exception {
        List<String> constructionSites = new ArrayList<>();
        int constructions = 0;
        for (Path file : javaFiles(MAIN)) {
            String source = read(file);
            int fileConstructions = occurrences(
                    source,
                    "new PersistenceBootstrap("
            );
            constructions += fileConstructions;
            if (fileConstructions > 0) {
                constructionSites.add(relative(file));
            }
        }

        assertEquals(1, constructions,
                "Production must have exactly one PersistenceBootstrap construction");
        assertEquals(
                List.of("TameworkPersistenceComposition.java"),
                constructionSites,
                "PersistenceBootstrap must be owned by the composition root"
        );
    }

    @Test
    void tameworkCreatesAndShutsDownOnePersistenceComposition()
            throws Exception {
        String source = read(TAMEWORK);

        assertEquals(
                1,
                occurrences(
                        source,
                        "TameworkPersistenceComposition.create("
                ),
                "Tamework must create exactly one persistence composition"
        );
        assertEquals(
                1,
                occurrences(source, "persistenceComposition.shutdown("),
                "Tamework must shut down exactly one persistence composition"
        );
    }

    @Test
    void debugCommandReceivesOnlyBoundedPersistenceReads()
            throws Exception {
        String tamework = read(TAMEWORK);
        String commandRoot = read(COMMAND_ROOT);
        String debugCommand = read(DEBUG_DB_COMMAND);
        List<String> readerMethods = Stream.of(
                        PersistenceDiagnosticsReader.class
                                .getDeclaredMethods()
                )
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .sorted()
                .toList();

        assertFalse(
                tamework.contains("getPersistenceBootstrap("),
                "Tamework must not publicly expose lifecycle authority"
        );
        assertTrue(
                tamework.contains(
                        "persistenceComposition.diagnosticsReader(),"
                ),
                "Production command wiring must inject the bounded reader"
        );
        assertTrue(
                tamework.contains(
                        "persistenceComposition.diagnosticsExporter()"
                ),
                "Production command wiring must inject the bounded exporter"
        );
        assertTrue(
                commandRoot.contains(
                        "new TameworkDebugDbCommand("
                ),
                "The command root must pass the injected reader through"
        );
        assertFalse(
                debugCommand.contains("PersistenceBootstrap"),
                "The debug command must not depend on lifecycle authority"
        );
        assertFalse(
                debugCommand.contains("Tamework.getInstance()"),
                "The debug command must not rediscover persistence globally"
        );
        assertEquals(
                List.of("details", "metrics", "status"),
                readerMethods,
                "The reader must remain a read-only diagnostics seam"
        );
    }

    @Test
    void gameplayPackagesDoNotImportSqliteAdapter() throws Exception {
        String forbiddenImport =
                "import com.alechilles.alecstamework.persistence"
                        + ".adapter.sqlite.";
        List<String> violations = new ArrayList<>();
        Path persistenceRoot = MAIN.resolve("persistence");
        for (Path file : javaFiles(MAIN)) {
            if (file.startsWith(persistenceRoot)) {
                continue;
            }
            if (read(file).contains(forbiddenImport)) {
                violations.add(relative(file));
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Gameplay imports the SQLite adapter directly: "
                        + violations
        );
    }

    @Test
    void supersededPersistencePackagesArePhysicallyAbsent() throws Exception {
        assertFalse(
                Files.exists(LEGACY_SQLITE),
                "Superseded persistence/sqlite directory must not exist"
        );
        assertFalse(
                Files.exists(LEGACY_BRIDGES),
                "Superseded persistence/legacy directory must not exist"
        );

        List<String> declarations = new ArrayList<>();
        for (Path file : javaFiles(MAIN)) {
            String source = read(file);
            if (source.contains(
                    "package com.alechilles.alecstamework.persistence.sqlite"
            ) || source.contains(
                    "package com.alechilles.alecstamework.persistence.legacy"
            )) {
                declarations.add(relative(file));
            }
        }
        assertTrue(
                declarations.isEmpty(),
                () -> "Superseded persistence classes remain: " + declarations
        );
    }

    @Test
    void persistenceCompositionStaysWithinComplexityTarget() throws Exception {
        long lines;
        try (Stream<String> source = Files.lines(
                COMPOSITION,
                StandardCharsets.UTF_8
        )) {
            lines = source.count();
        }

        assertTrue(
                lines <= 500,
                () -> relative(COMPOSITION) + " has " + lines
                        + " lines; target is 500"
        );
    }

    private static List<Path> javaFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static String read(Path file) throws Exception {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static String relative(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }
}
