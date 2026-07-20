package com.alechilles.alecstamework.persistence.incidents;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Protects the private-evidence boundary and regression coverage map for historical reports. */
class PersistenceHistoricalCorpusManifestTest {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void manifestReferencesPrivateEvidenceOnlyByLabelAndHash() throws Exception {
        JsonObject manifest = loadManifest();
        JsonObject privacy = manifest.getAsJsonObject("privacy");
        assertFalse(privacy.get("playerArtifactsCommitted").getAsBoolean());
        assertEquals("external-read-only", privacy.get("sourceStorage").getAsString());

        Set<String> labels = new HashSet<>();
        for (JsonElement element : manifest.getAsJsonArray("privateEvidence")) {
            JsonObject evidence = element.getAsJsonObject();
            assertEquals(Set.of("label", "kind", "sha256"), evidence.keySet());
            assertTrue(labels.add(evidence.get("label").getAsString()), "duplicate evidence label");
            assertTrue(SHA_256.matcher(evidence.get("sha256").getAsString()).matches());
        }

        for (JsonElement element : manifest.getAsJsonArray("scenarios")) {
            JsonObject scenario = element.getAsJsonObject();
            assertFalse(scenario.get("expectedPreMigrationDiagnosis").getAsString().isBlank());
            assertFalse(scenario.get("expectedPostMigrationResult").getAsString().isBlank());
            JsonArray tests = scenario.getAsJsonArray("regressionTests");
            assertFalse(tests.isEmpty(), scenario.get("id").getAsString());
            for (JsonElement test : tests) {
                assertNotNull(Class.forName(test.getAsString()), "missing regression test class");
            }
            for (JsonElement reference : scenario.getAsJsonArray("evidence")) {
                assertTrue(labels.contains(reference.getAsString()), "unknown private evidence label");
            }
        }

        String serialized = manifest.toString().toLowerCase();
        assertFalse(serialized.contains("c:\\users"));
        assertFalse(serialized.contains("/users/"));
        assertFalse(serialized.contains("downloads"));
        assertFalse(serialized.contains(".zip\""), "archive filenames must not be committed");
    }

    private JsonObject loadManifest() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/persistence-corpus/manifest.json")) {
            assertNotNull(stream, "historical corpus manifest");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
