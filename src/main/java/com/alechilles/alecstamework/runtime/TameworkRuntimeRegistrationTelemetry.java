package com.alechilles.alecstamework.runtime;

import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeDiagnostics;

/** Records passive counters after one runtime participant is installed. */
public final class TameworkRuntimeRegistrationTelemetry {
    private TameworkRuntimeRegistrationTelemetry() {
    }

    /** Records the installation kind without starting a monitor or scheduler. */
    public static void record(
            TameworkRuntimeDiagnostics diagnostics,
            TameworkRuntimeRegistrationContext.Participant participant
    ) {
        switch (participant.kind()) {
            case ECS_SYSTEM, CHUNK_SYSTEM ->
                    diagnostics.recordSystemRegistration(participant.module());
            case WORKER -> diagnostics.recordWorkerStart(participant.module());
            case LISTENER, SUBSCRIPTION -> diagnostics.recordSubscription(participant.module());
        }
    }
}
