package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEvent;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEventKind;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkPersistenceTelemetryTest {
    private static final String RAW_PROFILE_ID = "1f7c780d-f09a-4bf3-a8f1-raw-profile";

    @Test
    void structuredBreadcrumbAndEventContainOnlySanitizedScopeEvidence() {
        TameworkPersistenceTelemetry adapter = new TameworkPersistenceTelemetry();
        PersistenceIncidentEvent event = event(PersistenceIncidentEventKind.INCIDENT_OPENED);

        TelemetryBreadcrumbContext breadcrumb = adapter.breadcrumb(event);
        TelemetryEventContext details = adapter.details(event);

        String serialized = breadcrumb.toString() + details.toString();
        assertFalse(serialized.contains(RAW_PROFILE_ID));
        assertTrue(serialized.contains("remote-safe-profile-hash"));
        assertEquals("incident-random", breadcrumb.incidentId());
        assertEquals(TameworkPersistenceTelemetry.REMOTE_DETAIL_KEYS, details.details().keySet());
    }

    @Test
    void descriptorExactlyAllowsEveryPersistenceDetailField() throws Exception {
        String json;
        try (var stream = getClass().getResourceAsStream("/telemetry/project.json")) {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject events = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("events");
        Set<String> eventNames = Set.of(
                "persistence_incident_opened", "persistence_recovery_failed",
                "persistence_global_read_only_entered", "persistence_incident_repeated",
                "persistence_quarantine_opened", "persistence_quarantine_cleared",
                "persistence_recovery_completed", "persistence_global_read_only_recovered");
        for (String eventName : eventNames) {
            JsonObject type = eventName.equals("persistence_incident_opened")
                    || eventName.equals("persistence_recovery_failed")
                    || eventName.equals("persistence_global_read_only_entered")
                    ? events.getAsJsonObject("errors") : events.getAsJsonObject("lifecycle");
            JsonObject allowed = type.getAsJsonObject("details").getAsJsonObject(eventName)
                    .getAsJsonObject("allowedFields");
            assertEquals(TameworkPersistenceTelemetry.REMOTE_DETAIL_KEYS,
                    new HashSet<>(allowed.keySet()), eventName);
        }
    }

    @Test
    void allIncidentKindsMapToDeclaredStableEventNames() {
        TameworkPersistenceTelemetry adapter = new TameworkPersistenceTelemetry();
        for (PersistenceIncidentEventKind kind : PersistenceIncidentEventKind.values()) {
            assertTrue(adapter.eventName(kind).startsWith("persistence_"));
        }
    }

    private PersistenceIncidentEvent event(PersistenceIncidentEventKind kind) {
        return new PersistenceIncidentEvent(
                1, 1L, kind, "boot-random", "incident-random", "trace-random",
                "operation-random", PersistenceDomain.OWNER_MUTATION,
                PersistenceOperationPhase.PUBLICATION, "publication_failed",
                PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE,
                PersistenceDisposition.SCOPED_QUARANTINE,
                List.of(new PersistenceIncidentEvent.SafeScope(
                        "PROFILE", "remote-safe-profile-hash", "canonical_profile_catalog")),
                1L, 0L, "opened");
    }
}
