package com.alechilles.alecstamework.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies descriptor parity for every structured persistence telemetry event. */
class PersistenceTelemetryDescriptorTest {
    private static final Set<String> ERROR_EVENTS = Set.of(
            "persistence_incident_opened", "persistence_recovery_failed",
            "persistence_global_read_only_entered");
    private static final Set<String> LIFECYCLE_EVENTS = Set.of(
            "persistence_incident_repeated", "persistence_quarantine_opened",
            "persistence_quarantine_cleared", "persistence_recovery_completed",
            "persistence_global_read_only_recovered");

    @Test
    void descriptorAllowsExactlyTheRemotePersistenceFields() throws Exception {
        JsonObject events = descriptorEvents();
        assertFields(events.getAsJsonObject("errors"), ERROR_EVENTS);
        assertFields(events.getAsJsonObject("lifecycle"), LIFECYCLE_EVENTS);
    }

    private JsonObject descriptorEvents() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/telemetry/project.json")) {
            assertNotNull(stream, "packaged telemetry descriptor");
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("events");
        }
    }

    private void assertFields(JsonObject eventType, Set<String> names) {
        JsonObject details = eventType.getAsJsonObject("details");
        for (String name : names) {
            JsonObject allowed = details.getAsJsonObject(name).getAsJsonObject("allowedFields");
            assertEquals(TameworkPersistenceTelemetry.REMOTE_DETAIL_KEYS,
                    new HashSet<>(allowed.keySet()), name);
        }
    }
}
