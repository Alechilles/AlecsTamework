package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Keeps the bonded lifecycle independent from the generic persistence authorities. */
class BondedCompanionPersistenceBoundaryTest {
    private static final Path BONDED = Path.of(
            "src/main/java/com/alechilles/alecstamework/companion/bonded"
    );
    private static final Path BONDED_PERSISTENCE = Path.of(
            "src/main/java/com/alechilles/alecstamework/persistence/bonded"
    );
    private static final Path SQLITE_ADAPTERS = Path.of(
            "src/main/java/com/alechilles/alecstamework/persistence/adapter/sqlite"
    );
    private static final Path COMPOSITION = Path.of(
            "src/main/java/com/alechilles/alecstamework/"
                    + "TameworkBondedCompanionComposition.java"
    );
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "com.alechilles.alecstamework.companion.command.",
            "com.alechilles.alecstamework.companion.lifecycle.",
            "com.alechilles.alecstamework.companion.population.",
            "com.alechilles.alecstamework.companion.profile.",
            "com.alechilles.alecstamework.api.Command",
            "com.alechilles.alecstamework.api.CompanionProvisioning",
            "com.alechilles.alecstamework.api.NpcProfilesApi",
            "com.alechilles.alecstamework.api.PaidCommandRevival",
            "com.alechilles.alecstamework.api.Population",
            "com.alechilles.alecstamework.api.ProfileDataApi",
            "com.alechilles.alecstamework.api.ProvisionedCompanion",
            "com.alechilles.alecstamework.items.Command",
            "com.alechilles.alecstamework.items.Spawner",
            "com.alechilles.alecstamework.items.CompanionProfileSnapshotSink",
            "com.alechilles.alecstamework.persistence.operation.",
            "com.alechilles.alecstamework.persistence.projection.",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCommand",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionLifecycle",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteCompanionProfile",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqlitePopulation",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionOutbox",
            "com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteTimed"
    );

    @Test
    void bondedPackageDoesNotImportGenericRosterLifecycleOrOutboxAuthorities()
            throws Exception {
        ArrayList<String> violations = new ArrayList<>();
        for (Path file : domainJavaFiles()) {
            String source = Files.readString(file);
            for (String forbidden : FORBIDDEN_IMPORTS) {
                if (source.contains("import " + forbidden)) {
                    violations.add(relative(file) + " imports " + forbidden);
                }
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    @Test
    void bondedLifecycleHasExactlyTheThreePlayerVisibleStates() {
        assertEquals(
                List.of("STORED", "ACTIVE", "DEAD"),
                Stream.of(BondedCompanionState.values())
                        .map(Enum::name)
                        .toList()
        );
    }

    @Test
    void bondedDomainClassesRemainFocused() throws Exception {
        ArrayList<String> violations = new ArrayList<>();
        for (Path file : bondedJavaFiles()) {
            long lines;
            try (Stream<String> source = Files.lines(file)) {
                lines = source.count();
            }
            if (lines > 500L) {
                violations.add(relative(file) + " has " + lines
                        + " lines; bonded domain ceiling is 500");
            }
        }
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static List<Path> domainJavaFiles() throws Exception {
        if (!Files.exists(BONDED)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(BONDED)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static List<Path> bondedJavaFiles() throws Exception {
        ArrayList<Path> files = new ArrayList<>(domainJavaFiles());
        files.addAll(javaFilesBelow(BONDED_PERSISTENCE, false));
        files.addAll(javaFilesBelow(SQLITE_ADAPTERS, true));
        if (Files.exists(COMPOSITION)) {
            files.add(COMPOSITION);
        }
        return List.copyOf(files);
    }

    private static List<Path> javaFilesBelow(
            Path root,
            boolean bondedNameOnly
    ) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !bondedNameOnly
                            || path.getFileName().toString()
                            .contains("BondedCompanion"))
                    .toList();
        }
    }

    private static String relative(Path file) {
        return Path.of("").toAbsolutePath().relativize(file.toAbsolutePath())
                .toString();
    }
}
