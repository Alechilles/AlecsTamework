package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.bonded.BondedCompanionPersistenceReadiness;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionStoreDiagnostics;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the exact aggregate-only bonded diagnostic and export contract. */
class BondedCompanionDiagnosticContributorTest {
    @Test
    void snapshotContainsOnlyBoundedAggregateFields() {
        BondedCompanionDiagnosticContributor contributor =
                new BondedCompanionDiagnosticContributor(
                        () -> BondedCompanionPersistenceReadiness.ready(),
                        () -> new BondedCompanionStoreDiagnostics(
                                8L, 2L, 1L, 2L, 3L
                        ),
                        3
                );
        contributor.recordFailure(
                BondedCompanionDiagnosticSnapshot.FailureCategory.WORLD_EFFECT
        );

        BondedCompanionDiagnosticSnapshot snapshot = contributor.snapshot();
        String json = new String(
                contributor.exportEntry().content(),
                StandardCharsets.UTF_8
        );

        assertEquals("READY", snapshot.readiness());
        assertEquals(3, snapshot.schemaVersion());
        assertEquals(8L, snapshot.storedProfiles());
        assertEquals(2L, snapshot.activeProfiles());
        assertEquals(1L, snapshot.deadProfiles());
        assertEquals(2L, snapshot.activeLeases());
        assertEquals(3L, snapshot.pendingBoundedCleanups());
        assertEquals(
                BondedCompanionDiagnosticSnapshot.FailureCategory.WORLD_EFFECT,
                snapshot.lastFailureCategory()
        );
        assertEquals("bonded-companions.json", contributor.exportEntry().name());
        assertEquals(
                Set.of(
                        "readiness", "schemaVersion", "storedProfiles",
                        "activeProfiles", "deadProfiles", "activeLeases",
                        "pendingBoundedCleanups", "lastFailureCategory"
                ),
                contributor.exportFieldNames()
        );
        assertFalse(json.contains("player"));
        assertFalse(json.contains("profileId"));
        assertFalse(json.contains("owner"));
        assertFalse(json.contains("uuid"));
        assertFalse(json.contains("snapshot"));
        assertFalse(json.contains("extension"));
        assertFalse(json.contains("position"));
        assertFalse(json.contains("10000000-0000-0000-0000-000000000005"));
    }

    @Test
    void failedAggregateReadReturnsAWhitelistedFailureWithoutLeakingMessage() {
        BondedCompanionDiagnosticContributor contributor =
                new BondedCompanionDiagnosticContributor(
                        () -> BondedCompanionPersistenceReadiness.failed(
                                "schema-message-canary"
                        ),
                        () -> {
                            throw new IllegalStateException(
                                    "owner/profile/live-uuid/payload-canary"
                            );
                        },
                        3
                );

        BondedCompanionDiagnosticSnapshot snapshot = contributor.snapshot();
        String json = new String(
                contributor.exportEntry().content(),
                StandardCharsets.UTF_8
        );

        assertEquals("UNAVAILABLE", snapshot.readiness());
        assertEquals(
                BondedCompanionDiagnosticSnapshot.FailureCategory.DIAGNOSTIC,
                snapshot.lastFailureCategory()
        );
        assertTrue(json.contains("DIAGNOSTIC"));
        assertFalse(json.contains("canary"));
    }

    @Test
    void failedReadinessReadAlsoReturnsAWhitelistedFailure() {
        BondedCompanionDiagnosticContributor contributor =
                new BondedCompanionDiagnosticContributor(
                        () -> {
                            throw new IllegalStateException(
                                    "owner/profile/readiness-canary"
                            );
                        },
                        BondedCompanionStoreDiagnostics::empty,
                        3
                );

        String json = new String(
                contributor.exportEntry().content(),
                StandardCharsets.UTF_8
        );

        assertTrue(json.contains("\"readiness\": \"UNAVAILABLE\""));
        assertTrue(json.contains("DIAGNOSTIC"));
        assertFalse(json.contains("canary"));
    }
}
