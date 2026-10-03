package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compatibility and signed-time contracts for restored persistence-facing API values. */
class PersistenceFeatureApiContractTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000901"
    );

    @Test
    void persistedWorldTimesAcceptNegativeEpochValues() {
        CommandFamilyRosterMembershipView member =
                new CommandFamilyRosterMembershipView(
                        OWNER,
                        "primary",
                        "profile-1",
                        "tamed_chicken",
                        0,
                        CommandFamilyRosterMemberState.ROSTER_STORED,
                        null,
                        true,
                        null,
                        -2_000
                );
        CommandFamilyRosterView roster = new CommandFamilyRosterView(
                OWNER, "primary", 0, List.of(member), -2_000
        );
        CommandTimedSummoningView timed =
                new CommandTimedSummoningView(
                        OWNER,
                        "primary",
                        "profile-1",
                        1,
                        CommandTimedSummoningState.ROSTER_STORED,
                        null,
                        0L,
                        false,
                        -1_000,
                        -2_000
                );

        assertEquals(-2_000, roster.updatedAtMs());
        assertEquals(-1_000, timed.cooldownUntilMs());
    }

    @Test
    void resolvedCaptureEventCarriesExactTerminalEvidence() {
        CaptureAttemptResolvedEvent event = new CaptureAttemptResolvedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                OWNER,
                UUID.randomUUID(),
                "profile-1",
                "tamed_chicken",
                "capture-crate",
                "capture-config",
                1,
                null,
                -1,
                1,
                0,
                5.0,
                10.0,
                0.5,
                0.25,
                0.75,
                false,
                CaptureAttemptOutcome.FAILED_ROLL,
                "roll-failed",
                -2_000,
                -1_999
        );

        assertEquals(CaptureAttemptOutcome.FAILED_ROLL, event.outcome());
        assertEquals(-2_000, event.resolvedAtMs());
        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureAttemptResolvedEvent(
                        event.attemptId(),
                        event.operationId(),
                        event.actorUuid(),
                        event.targetNpcUuid(),
                        event.profileId(),
                        event.roleId(),
                        event.sourceItemId(),
                        event.spawnerConfigId(),
                        event.spawnerConfigRevision(),
                        null,
                        0,
                        event.power(),
                        event.minimumPower(),
                        event.currentHealth(),
                        event.maximumHealth(),
                        event.missingHealthFraction(),
                        event.configuredConditionBonus(),
                        event.effectiveChance(),
                        event.guaranteed(),
                        event.outcome(),
                        event.reason(),
                        event.resolvedAtMs(),
                        event.emittedAtMs()
                )
        );
    }
}
