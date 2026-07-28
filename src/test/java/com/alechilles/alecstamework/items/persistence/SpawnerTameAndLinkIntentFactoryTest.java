package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Fail-closed authoritative-source tests for tame/link intent composition. */
class SpawnerTameAndLinkIntentFactoryTest {
    private final SpawnerTameAndLinkEvidenceFixture fixture =
            new SpawnerTameAndLinkEvidenceFixture();

    @Test
    void injectedAuthoritativeEvidenceCreatesReachableTameLinkIntent() {
        SpawnerTameAndLinkIntentEvidence evidence =
                fixture.baseIntentEvidence();
        SpawnerTameAndLinkIntentFactory factory =
                new SpawnerTameAndLinkIntentFactory(input -> evidence);

        SpawnerCaptureIntent intent = factory.create(
                input(resolution(
                        CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                        CaptureAttemptResolution.Outcome.SUCCESS
                ))
        );

        assertEquals(fixture.OWNER, intent.resultingOwnerId());
        assertEquals("Alec", intent.resultingOwnerName());
        assertNull(intent.filledArtifactStack());
        assertSame(evidence, intent.tameAndLinkEvidence());
    }

    @Test
    void missingOrMismatchedAuthorityFailsClosed() {
        SpawnerTameAndLinkIntentFactory unavailable =
                new SpawnerTameAndLinkIntentFactory(input -> null);
        assertNull(unavailable.create(input(resolution(
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.SUCCESS
        ))));

        OwnerId other = OwnerId.parse(
                "92000000-0000-0000-0000-000000000001"
        );
        SpawnerTameAndLinkIntentEvidence base =
                fixture.baseIntentEvidence();
        SpawnerTameAndLinkIntentEvidence mismatched =
                new SpawnerTameAndLinkIntentEvidence(
                        new SpawnerTameAndLinkIntentEvidence.TargetEvidence(
                                other,
                                base.target().ownerName(),
                                base.target().roleId(),
                                base.target().metadataJson(),
                                base.target().expectedLiveStateHash(),
                                base.target().targetLiveStateHash(),
                                base.target().commandAccess()
                        ),
                        base.ownerPopulation(),
                        base.groups(),
                        base.command()
                );
        SpawnerTameAndLinkIntentFactory wrongOwner =
                new SpawnerTameAndLinkIntentFactory(
                        input -> mismatched
                );
        assertNull(wrongOwner.create(input(resolution(
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.SUCCESS
        ))));
    }

    @Test
    void capturedItemAndFailedRollNeverQueryTameLinkAuthority() {
        AtomicInteger queries = new AtomicInteger();
        SpawnerTameAndLinkIntentFactory factory =
                new SpawnerTameAndLinkIntentFactory(input -> {
                    queries.incrementAndGet();
                    return fixture.baseIntentEvidence();
                });

        assertNull(factory.create(input(resolution(
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.SUCCESS
        ))));
        assertNull(factory.create(input(resolution(
                CaptureSuccessDisposition.TAME_AND_COMMAND_LINK,
                CaptureAttemptResolution.Outcome.FAILED_ROLL
        ))));
        assertEquals(0, queries.get());
    }

    private SpawnerTameAndLinkIntentFactory.Input input(
            CaptureAttemptResolution resolution
    ) {
        return new SpawnerTameAndLinkIntentFactory.Input(
                resolution.attemptId().toString(),
                fixture.OWNER.value(),
                "Alec",
                fixture.WORLD,
                2,
                HytaleItemStackTestFixture.stack(
                        "HyDragon_Draconic_Stone",
                        new BsonDocument()
                ),
                null,
                null,
                fixture.PROFILE,
                new NpcAlias(UUID.fromString(fixture.ALIAS)),
                null,
                "wild_miniwyvern",
                resolution,
                null
        );
    }

    private CaptureAttemptResolution resolution(
            CaptureSuccessDisposition disposition,
            CaptureAttemptResolution.Outcome outcome
    ) {
        UUID attemptId = UUID.fromString(
                "92000000-0000-0000-0000-000000000002"
        );
        return new CaptureAttemptResolution(
                attemptId,
                "wild_miniwyvern",
                new CaptureAttemptFormula(
                        "draconic-stone",
                        1L,
                        CaptureChanceMode.GUARANTEED,
                        1,
                        1.0D,
                        0.0D,
                        1.0D,
                        1.0D,
                        null,
                        0L,
                        0,
                        0.0D,
                        1.0D,
                        0.0D,
                        null,
                        Sha256Hash.ofUtf8("[]"),
                        1L
                ),
                outcome == CaptureAttemptResolution.Outcome.FAILED_ROLL
                        ? CaptureSourceConsumption.RESOLVED_ATTEMPT
                        : CaptureSourceConsumption.SUCCESS_ONLY,
                disposition,
                outcome,
                outcome == CaptureAttemptResolution.Outcome.SUCCESS
                        ? "capture-success"
                        : "capture-failed",
                1.0D,
                true,
                0.0D,
                outcome == CaptureAttemptResolution.Outcome.FAILED_ROLL
                        ? 0.5D
                        : null,
                outcome == CaptureAttemptResolution.Outcome.FAILED_ROLL
                        ? fixture.REQUESTED_AT + 1_000L
                        : null
        );
    }
}
