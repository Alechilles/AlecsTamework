package com.alechilles.alecstamework.metrics;

import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorageFailureEvidence;
import com.alechilles.beacon.api.TelemetryEventContext;
import javax.annotation.Nonnull;

/** Emits descriptor-approved diagnostics for bonded persistence containment. */
public final class BondedCompanionPersistenceTelemetry {
    public static final String RUNTIME_FAILURE_EVENT =
            "bonded_persistence_runtime_failed";
    private static final String FINGERPRINT =
            "tamework.persistence.bonded.runtime_failed";

    private BondedCompanionPersistenceTelemetry() {
    }

    public static void recordRuntimeFailure(
            @Nonnull BondedCompanionStorageFailureEvidence evidence
    ) {
        TameworkTelemetryEvents.recordErrorIfAvailable(
                RUNTIME_FAILURE_EVENT,
                telemetryFailure(evidence),
                context(evidence)
        );
    }

    static TelemetryEventContext context(
            @Nonnull BondedCompanionStorageFailureEvidence evidence
    ) {
        return TameworkTelemetryContext.persistence(
                                "bonded", evidence.operation(),
                                evidence.schemaDiagnostic(),
                                "Bonded persistence disabled after a runtime storage failure.")
                        .severity("error")
                        .fingerprint(FINGERPRINT)
                        .detail("failureClass", evidence.failureClass())
                        .detail("failureReason", evidence.failureReason())
                        .detail("baselineFileState", evidence.baselineFileState())
                        .detail("baselineSizeBucket", evidence.baselineSizeBucket())
                        .detail("failureFileState", evidence.failureFileState())
                        .detail("failureSizeBucket", evidence.failureSizeBucket())
                        .detail("identityComparison", evidence.identityComparison())
                        .detail("sizeComparison", evidence.sizeComparison())
                        .detail("modifiedComparison", evidence.modifiedComparison())
                        .detail("walPresent", evidence.walPresent())
                        .detail("shmPresent", evidence.shmPresent())
                        .detail("schemaStatus", evidence.schemaStatus())
                        .detail("schemaDiagnostic", evidence.schemaDiagnostic())
                        .detail("sqlErrorCode", evidence.sqlErrorCode())
                        .detail("sqlState", evidence.sqlState())
                        .build();
    }

    static Throwable telemetryFailure(
            @Nonnull BondedCompanionStorageFailureEvidence evidence
    ) {
        IllegalStateException sanitized = new IllegalStateException(
                "bonded-runtime-storage-failed:" + evidence.failureReason());
        sanitized.setStackTrace(evidence.failure().getStackTrace());
        return sanitized;
    }
}
