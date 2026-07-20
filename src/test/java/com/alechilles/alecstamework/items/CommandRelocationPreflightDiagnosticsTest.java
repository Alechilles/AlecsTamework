package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityDecision;
import com.alechilles.alecstamework.persistence.health.PersistenceMutationAvailabilityStatus;
import com.alechilles.alecstelemetry.api.TelemetryBreadcrumbContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression coverage for privacy-bounded relocation rejection breadcrumbs. */
class CommandRelocationPreflightDiagnosticsTest {
    @Test
    void deniedSameWorldRecallIncludesAdmissionReasonWithoutRawScopeValues() {
        String profileId = "private-profile-id";
        String worldName = "private-world-name";
        PersistenceMutationAvailabilityDecision decision =
                new PersistenceMutationAvailabilityDecision(
                        PersistenceMutationAvailabilityStatus.AUTHORITY_NOT_READY,
                        "required_evidence_coverage_unavailable",
                        "private-incident-id"
                );

        TelemetryBreadcrumbContext breadcrumb =
                CommandRelocationPreflightDiagnostics.breadcrumb(
                        "recall", decision, profileId, worldName, worldName, false);
        String serialized = breadcrumb.toString();

        assertEquals("required_evidence_coverage_unavailable", breadcrumb.detail());
        assertEquals("recall", breadcrumb.operation());
        assertEquals("authority_not_ready", breadcrumb.failureClass());
        assertEquals("rejected", breadcrumb.disposition());
        assertEquals("true", breadcrumb.attributes().get("sameWorld"));
        assertEquals("present", breadcrumb.attributes().get("profileId"));
        assertFalse(serialized.contains(profileId));
        assertFalse(serialized.contains(worldName));
        assertFalse(serialized.contains("private-incident-id"));
    }
}
