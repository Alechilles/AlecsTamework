package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Third capture terminal variant codec and authority consistency tests. */
class CompanionCaptureTameAndLinkDefinitionTest {
    @Test
    void tameAndLinkRoundTripsEveryAuthorityExactly() throws Exception {
        CompanionCaptureRequest request =
                CaptureTameAndLinkTestFixtures.request();

        CompanionCaptureRequest decoded =
                CompanionCaptureDefinition.INSTANCE.decode(
                        CompanionCaptureDefinition.INSTANCE.encode(
                                request
                        )
                );

        assertEquals(request, decoded);
        assertTrue(decoded.tameAndCommandLink());
        assertEquals(
                "Tamed_Dragon_Fire",
                decoded.tameAndLinkEvidence()
                        .targetIdentity().roleId()
        );
        assertEquals(
                2,
                decoded.tameAndLinkEvidence()
                        .ownerPopulation().increases().size()
        );
    }

    @Test
    void requestRejectsOwnerThatDisagreesWithFrozenTarget() {
        CompanionCaptureRequest valid =
                CaptureTameAndLinkTestFixtures.request();

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureRequest(
                        valid.profileId(),
                        valid.expectedLifecycleRevision(),
                        OwnerId.parse(
                                "30000000-0000-0000-0000-000000000999"
                        ),
                        valid.targetAlias(),
                        valid.targetWorldKey(),
                        valid.terminal(),
                        valid.source(),
                        valid.requestedAtMs()
                )
        );
    }

    @Test
    void terminalRejectsResolutionForDifferentLiveRole() {
        CaptureAttemptResolution valid =
                CaptureTameAndLinkTestFixtures.resolution();
        CaptureAttemptResolution wrong = new CaptureAttemptResolution(
                valid.attemptId(),
                "Dragon_Ice",
                valid.formula(),
                valid.sourceConsumption(),
                valid.successDisposition(),
                valid.outcome(),
                valid.reason(),
                valid.effectiveChance(),
                valid.guaranteed(),
                valid.missingHealthFraction(),
                valid.entropy(),
                valid.failureCooldownUntilMs()
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CaptureTerminalPlan.TameAndCommandLink(
                        wrong,
                        CaptureTameAndLinkTestFixtures.evidence()
                )
        );
    }
}
