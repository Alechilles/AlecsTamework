package com.alechilles.alecstamework.persistence.incidents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceFailureClassificationCatalogTest {
    private static final Path MAIN = Path.of("src/main/java/com/alechilles/alecstamework");
    private static final Path DOCUMENT = Path.of("docs/Persistence-Failure-Classification-Catalog.md");
    private static final Pattern DIRECT_REASON = Pattern.compile(
            "(?:markReadinessDegraded|markDegraded|enterReadOnly|reportFeatureAmbiguity)"
                    + "\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern LEGACY_REASON = Pattern.compile(
            "LegacyGlobalPersistenceFailureBridge\\.markDegraded\\([^,]+,\\s*\"([^\"]+)\"");
    private static final Pattern CONTEXT_REASON = Pattern.compile(
            "new\\s+PersistenceFailureContext\\s*\\(\\s*\"([^\"]+)\"");

    @Test
    void everyLiteralDegradationReasonHasAnExactCatalogEntry() throws Exception {
        Set<String> reasons = sourceReasons();
        List<String> missing = reasons.stream()
                .filter(reason -> PersistenceFailureReasonCatalog.find(reason).isEmpty())
                .toList();

        assertFalse(reasons.isEmpty());
        assertTrue(missing.isEmpty(), "Unclassified persistence failure reasons: " + missing);
    }

    @Test
    void unknownReasonCodesFailClosedAtTheArchitectureBoundary() {
        assertTrue(PersistenceFailureReasonCatalog.find("new_unreviewed_failure").isEmpty());
    }

    @Test
    void checkedInCatalogDocumentsEveryExecutableEntry() throws Exception {
        String document = Files.readString(DOCUMENT);
        List<String> missing = new ArrayList<>();
        PersistenceFailureReasonCatalog.all().keySet().forEach(reason -> {
            if (!document.contains("`" + reason + "`")) missing.add(reason);
        });
        assertTrue(missing.isEmpty(), "Failure catalog documentation is missing: " + missing);
    }

    private Set<String> sourceReasons() throws Exception {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                collect(DIRECT_REASON, source, reasons);
                collect(LEGACY_REASON, source, reasons);
                collect(CONTEXT_REASON, source, reasons);
            }
        }
        return reasons;
    }

    private void collect(Pattern pattern, String source, Set<String> target) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) target.add(matcher.group(1));
    }
}
