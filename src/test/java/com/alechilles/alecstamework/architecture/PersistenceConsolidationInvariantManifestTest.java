package com.alechilles.alecstamework.architecture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures every hard-won persistence invariant has current evidence and a replacement gate. */
class PersistenceConsolidationInvariantManifestTest {
    @Test
    void everyInvariantHasCharacterizationAndNamedReplacementEvidence() throws Exception {
        JsonObject manifest = loadManifest();
        Set<String> statuses = new HashSet<>();
        for (JsonElement status : manifest.getAsJsonArray("statusValues")) {
            statuses.add(status.getAsString());
        }

        Set<String> ids = new HashSet<>();
        int invariantCount = 0;
        for (JsonElement element : manifest.getAsJsonArray("invariants")) {
            invariantCount++;
            JsonObject invariant = element.getAsJsonObject();
            String id = invariant.get("id").getAsString();
            String status = invariant.get("status").getAsString();
            int replacementPhase = invariant.get("replacementGatePhase").getAsInt();
            String replacementTest = invariant.get("replacementTestClass").getAsString();

            assertTrue(ids.add(id), "duplicate invariant: " + id);
            assertFalse(invariant.get("contract").getAsString().isBlank(), id);
            assertTrue(statuses.contains(status), id + ": " + status);
            assertTrue(replacementPhase >= 1 && replacementPhase <= 8, id);
            assertFalse(replacementTest.isBlank(), id);
            assertNotNull(Class.forName(replacementTest), id + ": " + replacementTest);
            assertFalse(invariant.getAsJsonArray("currentTests").isEmpty(), id);

            for (JsonElement currentTest : invariant.getAsJsonArray("currentTests")) {
                String className = currentTest.getAsString();
                assertNotNull(Class.forName(className), id + ": " + className);
            }
        }

        assertTrue(invariantCount >= 20, "the consolidation must retain the full invariant set");
    }

    private JsonObject loadManifest() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/persistence-consolidation/invariant-manifest.json"
        )) {
            assertNotNull(stream, "persistence consolidation invariant manifest");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
