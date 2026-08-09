package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorageFailureEvidence;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Guards the privacy-safe diagnostic payload for bonded storage failures. */
class BondedCompanionPersistenceTelemetryTest {
    @Test
    void contextCarriesClassifiedStorageEvidenceWithoutDatabasePath() {
        String privatePath = "/srv/customer-42/universe/Tamework/Data";
        BondedCompanionStorageFailureEvidence evidence =
                new BondedCompanionStorageFailureEvidence(
                        "maintenance", "SQLiteException", "unknown",
                        "present", "64k-1m", "present", "4-64k",
                        "changed", "decreased", "increased",
                        false, false, "failed",
                        "bonded-schema-table-mismatch", 1, "unknown",
                        new SQLException("failure at " + privatePath)
                );

        TelemetryEventContext context =
                BondedCompanionPersistenceTelemetry.context(evidence);

        assertEquals("maintenance", context.operation());
        assertEquals("64k-1m", context.details().get("baselineSizeBucket"));
        assertEquals("4-64k", context.details().get("failureSizeBucket"));
        assertEquals(1, context.details().get("sqlErrorCode"));
        assertFalse(context.toString().contains(privatePath));
        assertFalse(BondedCompanionPersistenceTelemetry
                .telemetryFailure(evidence).toString().contains(privatePath));
    }
}
