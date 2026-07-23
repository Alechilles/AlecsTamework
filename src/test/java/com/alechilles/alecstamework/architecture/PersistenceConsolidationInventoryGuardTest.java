package com.alechilles.alecstamework.architecture;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Makes the persistence consolidation baseline executable.
 *
 * <p>Every legacy allowance has a zero target and may only decrease. New replacement code is
 * guarded independently so temporary side-by-side work cannot create another unbounded package.
 */
class PersistenceConsolidationInventoryGuardTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/alechilles/alecstamework");
    private static final Path LEGACY_SQLITE_ROOT = SOURCE_ROOT.resolve("persistence/sqlite");
    private static final String LEGACY_SQLITE_IMPORT =
            "import com.alechilles.alecstamework.persistence.sqlite.";
    private static final String REPLACEMENT_ADAPTER_IMPORT =
            "import com.alechilles.alecstamework.persistence.adapter.sqlite.";
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-zA-Z0-9_]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final List<Path> REPLACEMENT_ROOTS = List.of(
            SOURCE_ROOT.resolve("persistence/kernel"),
            SOURCE_ROOT.resolve("persistence/operation"),
            SOURCE_ROOT.resolve("persistence/projection"),
            SOURCE_ROOT.resolve("persistence/control"),
            SOURCE_ROOT.resolve("persistence/migration"),
            SOURCE_ROOT.resolve("persistence/adapter"),
            SOURCE_ROOT.resolve("persistence/facade"),
            SOURCE_ROOT.resolve("companion/identity"),
            SOURCE_ROOT.resolve("companion/lifecycle"),
            SOURCE_ROOT.resolve("companion/snapshot")
    );

    @Test
    void legacyPersistenceSurfaceCanOnlyShrink() throws Exception {
        JsonObject baseline = loadBaseline();
        Inventory inventory = inventory();

        assertAtMost(baseline, "legacySqlite", "javaFiles", inventory.legacyFiles());
        assertAtMost(baseline, "legacySqlite", "javaLines", inventory.legacyLines());
        assertAtMost(baseline, "legacySqlite", "schemaTables", inventory.legacyTables().size());
        assertAtMost(
                baseline,
                "legacySqlite",
                "classesAtOrAbove1000Lines",
                inventory.legacyClassesAtOrAbove1000()
        );
        assertAtMost(
                baseline,
                "crossPackageCoupling",
                "nonPersistenceFilesImportingLegacySqlite",
                inventory.nonPersistenceFilesImportingLegacySqlite()
        );
        assertAtMost(
                baseline,
                "crossPackageCoupling",
                "legacySqliteImportStatementsOutsidePersistence",
                inventory.legacySqliteImportsOutsidePersistence()
        );
        assertAtMost(
                baseline,
                "legacyWriteApi",
                "submitTrackedCalls",
                inventory.submitTrackedCalls()
        );
        assertAtMost(
                baseline,
                "legacyWriteApi",
                "submitWithCompletionCalls",
                inventory.submitWithCompletionCalls()
        );
        assertAtMost(
                baseline,
                "legacyWriteApi",
                "untrackedSubmitCalls",
                inventory.untrackedSubmitCalls()
        );

        writeReport(baseline, inventory);
    }

    @Test
    void replacementCodeCannotDependOnLegacySqlite() throws Exception {
        for (Path file : replacementJavaFiles()) {
            String source = Files.readString(file);
            assertTrue(
                    !source.contains(LEGACY_SQLITE_IMPORT),
                    () -> relative(file) + " imports the legacy SQLite implementation"
            );
        }
    }

    @Test
    void replacementAdaptersCannotLeakIntoGameplayPackages() throws Exception {
        for (Path file : productionJavaFiles()) {
            if (file.startsWith(SOURCE_ROOT.resolve("persistence"))
                    || file.startsWith(SOURCE_ROOT.resolve("Tamework.java"))) {
                continue;
            }
            String source = Files.readString(file);
            assertTrue(
                    !source.contains(REPLACEMENT_ADAPTER_IMPORT),
                    () -> relative(file) + " imports the replacement SQLite adapter directly"
            );
        }
    }

    @Test
    void replacementClassesRespectHardSizeCeiling() throws Exception {
        int hardCeiling = loadBaseline()
                .getAsJsonObject("replacementRules")
                .get("hardClassLines")
                .getAsInt();
        for (Path file : replacementJavaFiles()) {
            long lines;
            try (Stream<String> sourceLines = Files.lines(file)) {
                lines = sourceLines.count();
            }
            long measuredLines = lines;
            assertTrue(
                    measuredLines <= hardCeiling,
                    () -> relative(file) + " has " + measuredLines
                            + " lines; hard ceiling is " + hardCeiling
            );
        }
    }

    @Test
    void replacementKernelClassesRespectTargetSize() throws Exception {
        int target = loadBaseline()
                .getAsJsonObject("replacementRules")
                .get("targetClassLines")
                .getAsInt();
        List<Path> focusedRoots = List.of(
                SOURCE_ROOT.resolve("persistence/kernel"),
                SOURCE_ROOT.resolve("persistence/adapter"),
                SOURCE_ROOT.resolve("persistence/projection"),
                SOURCE_ROOT.resolve("companion/identity"),
                SOURCE_ROOT.resolve("companion/lifecycle")
        );
        for (Path root : focusedRoots) {
            for (Path file : javaFiles(root)) {
                long lines = countLines(List.of(file));
                assertTrue(
                        lines <= target,
                        () -> relative(file) + " has " + lines
                                + " lines; replacement target is " + target
                );
            }
        }
    }

    @Test
    void replacementAdapterHasNoLegacyQueueOrMetadataVocabulary() throws Exception {
        List<String> forbidden = List.of(
                "submitTracked(",
                "submitWithCompletion(",
                "PersistenceOperationMetadata",
                "PersistenceWriteQueue",
                "MAX_BATCH_SIZE",
                "batchExecutor"
        );
        for (Path file : javaFiles(SOURCE_ROOT.resolve("persistence/adapter"))) {
            String source = Files.readString(file);
            for (String vocabulary : forbidden) {
                assertTrue(
                        !source.contains(vocabulary),
                        () -> relative(file) + " contains superseded persistence API: " + vocabulary
                );
            }
        }
    }

    private Inventory inventory() throws Exception {
        List<Path> productionFiles = productionJavaFiles();
        List<Path> legacyFiles = javaFiles(LEGACY_SQLITE_ROOT);
        long legacyLines = countLines(legacyFiles);
        int legacyClassesAtOrAbove1000 = 0;
        for (Path file : legacyFiles) {
            if (countLines(List.of(file)) >= 1000) {
                legacyClassesAtOrAbove1000++;
            }
        }

        Set<String> tables = new LinkedHashSet<>();
        int nonPersistenceFilesImportingLegacySqlite = 0;
        int legacySqliteImportsOutsidePersistence = 0;
        int submitTrackedCalls = 0;
        int submitWithCompletionCalls = 0;
        int untrackedSubmitCalls = 0;

        for (Path file : productionFiles) {
            String source = Files.readString(file);
            if (file.startsWith(LEGACY_SQLITE_ROOT)) {
                Matcher matcher = CREATE_TABLE.matcher(source);
                while (matcher.find()) {
                    tables.add(matcher.group(1).toLowerCase());
                }
            }

            if (!file.startsWith(SOURCE_ROOT.resolve("persistence"))) {
                int imports = countOccurrences(source, LEGACY_SQLITE_IMPORT);
                if (imports > 0) {
                    nonPersistenceFilesImportingLegacySqlite++;
                    legacySqliteImportsOutsidePersistence += imports;
                }
            }

            submitTrackedCalls += countOccurrences(source, "submitTracked(");
            submitWithCompletionCalls += countOccurrences(source, "submitWithCompletion(");
            untrackedSubmitCalls += countOccurrences(source, "writeQueue.submit(");
        }

        return new Inventory(
                legacyFiles.size(),
                legacyLines,
                tables,
                legacyClassesAtOrAbove1000,
                nonPersistenceFilesImportingLegacySqlite,
                legacySqliteImportsOutsidePersistence,
                submitTrackedCalls,
                submitWithCompletionCalls,
                untrackedSubmitCalls
        );
    }

    private void writeReport(JsonObject baseline, Inventory inventory) throws Exception {
        JsonObject report = new JsonObject();
        report.addProperty("manifestVersion", 1);
        report.addProperty("baselineCommit", baseline.get("capturedAtCommit").getAsString());
        report.addProperty("legacySqliteJavaFiles", inventory.legacyFiles());
        report.addProperty("legacySqliteJavaLines", inventory.legacyLines());
        report.addProperty("legacySchemaTableCount", inventory.legacyTables().size());
        report.add("legacySchemaTables", new GsonBuilder().create().toJsonTree(
                inventory.legacyTables().stream().sorted().toList()
        ));
        report.addProperty(
                "legacyClassesAtOrAbove1000Lines",
                inventory.legacyClassesAtOrAbove1000()
        );
        report.addProperty(
                "nonPersistenceFilesImportingLegacySqlite",
                inventory.nonPersistenceFilesImportingLegacySqlite()
        );
        report.addProperty(
                "legacySqliteImportStatementsOutsidePersistence",
                inventory.legacySqliteImportsOutsidePersistence()
        );
        report.addProperty("submitTrackedCalls", inventory.submitTrackedCalls());
        report.addProperty("submitWithCompletionCalls", inventory.submitWithCompletionCalls());
        report.addProperty("untrackedSubmitCalls", inventory.untrackedSubmitCalls());

        Path output = Path.of("target/persistence-consolidation-inventory.json");
        Files.createDirectories(output.getParent());
        Files.writeString(
                output,
                new GsonBuilder().setPrettyPrinting().create().toJson(report) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private void assertAtMost(
            JsonObject baseline,
            String section,
            String metric,
            long actual
    ) {
        JsonObject contract = baseline
                .getAsJsonObject(section)
                .getAsJsonObject(metric);
        long maximum = contract.get("baseline").getAsLong();
        int removalPhase = contract.get("removeByPhase").getAsInt();
        assertTrue(
                actual <= maximum,
                () -> section + "." + metric + " increased from " + maximum + " to " + actual
                        + "; target is zero by phase " + removalPhase
        );
    }

    private JsonObject loadBaseline() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/persistence-consolidation/inventory-baseline.json"
        )) {
            assertNotNull(stream, "persistence consolidation inventory baseline");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private List<Path> productionJavaFiles() throws Exception {
        return javaFiles(SOURCE_ROOT);
    }

    private List<Path> replacementJavaFiles() throws Exception {
        ArrayList<Path> files = new ArrayList<>();
        for (Path root : REPLACEMENT_ROOTS) {
            files.addAll(javaFiles(root));
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private List<Path> javaFiles(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private long countLines(List<Path> files) throws Exception {
        long total = 0;
        for (Path file : files) {
            try (Stream<String> sourceLines = Files.lines(file)) {
                total += sourceLines.count();
            }
        }
        return total;
    }

    private int countOccurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private String relative(Path file) {
        return Path.of("").toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private record Inventory(
            int legacyFiles,
            long legacyLines,
            Set<String> legacyTables,
            int legacyClassesAtOrAbove1000,
            int nonPersistenceFilesImportingLegacySqlite,
            int legacySqliteImportsOutsidePersistence,
            int submitTrackedCalls,
            int submitWithCompletionCalls,
            int untrackedSubmitCalls
    ) {
    }
}
