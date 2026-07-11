package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import java.util.UUID;

/** Builds strict durable lost envelopes for recovery persistence fixtures. */
final class RecoveryTestEnvelopeFixtures {
    private RecoveryTestEnvelopeFixtures() {
    }

    static String validEnvelope(UUID sourceUuid) {
        return new LostRecoveryEnvelopeCodec().encode(
                lost(sourceUuid, null),
                fullSnapshot(sourceUuid)
        ).payloadJson();
    }

    static String sourceOnlyEnvelope(UUID sourceUuid) {
        return new LostRecoveryEnvelopeCodec().encode(
                lost(sourceUuid, null),
                null
        ).payloadJson();
    }

    static String recoveredEnvelope(UUID sourceUuid, UUID replacementUuid) {
        return new LostRecoveryEnvelopeCodec().encode(
                lost(sourceUuid, replacementUuid),
                fullSnapshot(sourceUuid)
        ).payloadJson();
    }

    private static CommandLinkedNpcLostService.LostLinkedNpcSnapshot lost(
            UUID sourceUuid,
            UUID replacementUuid) {
        return new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                sourceUuid, null, null, -11L, -12L, 2, replacementUuid, -13L);
    }

    private static CoopResidentStateSnapshotService.CoopResidentStateSnapshot fullSnapshot(
            UUID sourceUuid) {
        return new CoopResidentStateSnapshotService.CoopResidentStateSnapshot(
                sourceUuid,
                null,
                -1,
                "Mob_Test",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                -14L
        );
    }
}
