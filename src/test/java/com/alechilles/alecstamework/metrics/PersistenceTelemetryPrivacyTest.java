package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEvent;
import com.alechilles.alecstamework.persistence.diagnostics.PersistenceIncidentEventKind;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDisposition;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFailureClass;
import com.alechilles.alecstamework.persistence.incidents.PersistenceOperationPhase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the remote persistence envelope to hashed, bounded support evidence. */
class PersistenceTelemetryPrivacyTest {
    private static final String RAW_IDENTITY = "1f7c780d-f09a-4bf3-a8f1-private-profile";

    @Test
    void remoteContextsNeverContainRawScopeIdentityOrSensitiveFieldNames() {
        TameworkPersistenceTelemetry adapter = new TameworkPersistenceTelemetry();
        PersistenceIncidentEvent event = new PersistenceIncidentEvent(
                1, 1L, PersistenceIncidentEventKind.INCIDENT_OPENED,
                "boot-random", "incident-random", "trace-random", "operation-random",
                PersistenceDomain.OWNER_MUTATION, PersistenceOperationPhase.PUBLICATION,
                "publication_failed", PersistenceFailureClass.POST_COMMIT_PUBLICATION_FAILURE,
                PersistenceDisposition.SCOPED_QUARANTINE,
                List.of(new PersistenceIncidentEvent.SafeScope(
                        "PROFILE", "hmac-sha256-profile", "canonical_profile_catalog")),
                1L, 0L, "opened");

        String serialized = adapter.breadcrumb(event).toString() + adapter.details(event).toString();

        assertFalse(serialized.contains(RAW_IDENTITY));
        assertTrue(serialized.contains("hmac-sha256-profile"));
        assertFalse(TameworkPersistenceTelemetry.REMOTE_DETAIL_KEYS.stream()
                .map(String::toLowerCase)
                .anyMatch(Set.of("profileid", "owneruuid", "npcuuid", "playername", "worldpath")::contains));
    }
}
