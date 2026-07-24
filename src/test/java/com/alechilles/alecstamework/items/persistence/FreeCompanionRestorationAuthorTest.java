package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused released-policy and submission tests for free restoration authoring. */
class FreeCompanionRestorationAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "71000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias SOURCE = new NpcAlias(UUID.fromString(
            "71000000-0000-0000-0000-000000000002"
    ));
    private static final UUID ACTOR = UUID.fromString(
            "71000000-0000-0000-0000-000000000003"
    );
    private static final LifecycleRevision SOURCE_REVISION =
            new LifecycleRevision(7);
    private static final LifecycleRevision DORMANT_REVISION =
            new LifecycleRevision(8);
    private final SnapshotCodecRegistry codecs = TameworkSnapshotCodecs.create();

    @Test
    void signedCooldownAndZeroSentinelFollowReleasedDeathPolicy() {
        CompanionSnapshot waiting = modernDeath(-500L, -100L);
        FakePersistence waitingPersistence =
                new FakePersistence(profile(waiting, LifecycleState.DEAD_REVIVABLE));
        CompanionLifecycleAuthorResult waitingResult = author(
                waitingPersistence, -200L, profile -> true, ignoredDispatcher()
        ).restore(intent()).toCompletableFuture().join();

        CompanionSnapshot unset = modernDeath(-500L, 0L);
        FakePersistence unsetPersistence =
                new FakePersistence(profile(unset, LifecycleState.DEAD_REVIVABLE));
        CompanionLifecycleAuthorResult unsetResult = author(
                unsetPersistence, -200L, profile -> true, ignoredDispatcher()
        ).restore(intent()).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.COOLDOWN_ACTIVE,
                waitingResult.status()
        );
        assertEquals(0, waitingPersistence.submissions);
        assertTrue(unsetResult.published());
        assertEquals(1, unsetPersistence.submissions);
    }

    @Test
    void disabledDeathIsDeniedButLostRemainsFreeAndImmediate() {
        CompanionSnapshot death = modernDeath(-500L, -600L);
        FakePersistence deathPersistence =
                new FakePersistence(profile(death, LifecycleState.DEAD_REVIVABLE));
        CompanionLifecycleAuthorResult deathResult = author(
                deathPersistence, -400L, profile -> false, ignoredDispatcher()
        ).restore(intent()).toCompletableFuture().join();

        AtomicInteger lostPolicyCalls = new AtomicInteger();
        CompanionSnapshot lost = modernLost();
        FakePersistence lostPersistence =
                new FakePersistence(profile(lost, LifecycleState.LOST));
        CompanionLifecycleAuthorResult lostResult = author(
                lostPersistence,
                -400L,
                profile -> {
                    lostPolicyCalls.incrementAndGet();
                    return false;
                },
                ignoredDispatcher()
        ).restore(intent()).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.RESTORATION_DISABLED,
                deathResult.status()
        );
        assertEquals(0, deathPersistence.submissions);
        assertTrue(lostResult.published());
        assertEquals(0, lostPolicyCalls.get());
    }

    @Test
    void legacyDeathAndModernLostResolveCompleteFrozenRequests() {
        for (SourceCase sourceCase : List.of(
                new SourceCase(
                        legacyDeath(),
                        LifecycleState.DEAD_REVIVABLE,
                        -400L
                ),
                new SourceCase(modernLost(), LifecycleState.LOST, -400L)
        )) {
            FakePersistence persistence = new FakePersistence(profile(
                    sourceCase.snapshot(), sourceCase.state()
            ));
            FreeCompanionRestorationAuthor author = author(
                    persistence,
                    sourceCase.now(),
                    profile -> true,
                    ignoredDispatcher()
            );

            CompanionLifecycleAuthorResult result = author.restore(intent())
                    .toCompletableFuture().join();

            assertTrue(result.published());
            CompanionRestorationRequest request = persistence.request;
            assertNotNull(request);
            assertEquals(DORMANT_REVISION, request.expectedLifecycleRevision());
            assertSame(sourceCase.snapshot(), request.sourceSnapshot());
            assertEquals(1, request.projection().fullState().payloadVersion());
            assertNotEquals(
                    request.projection().sourceAlias(),
                    request.targetAlias()
            );
            assertEquals(12.25, request.placement().x());
            assertTrue(request.spawnReceiptKey().startsWith("receipt:v1:"));
        }
    }

    @Test
    void rejectedAndExceptionalSubmissionsDispatchExactTerminalResult() {
        AtomicReference<CompanionLifecycleAuthorResult> dispatched =
                new AtomicReference<>();
        FreeCompanionRestorationAuthor.ResultDispatcher dispatcher =
                (world, actor, result) -> {
                    assertEquals("actor-world", world);
                    assertEquals(ACTOR, actor);
                    dispatched.set(result);
                    return true;
                };
        CompanionSnapshot source = modernLost();
        FakePersistence rejected = new FakePersistence(
                profile(source, LifecycleState.LOST)
        );
        rejected.mode = SubmissionMode.REJECTED;
        CompanionLifecycleAuthorResult rejectedResult = author(
                rejected, -400L, profile -> true, dispatcher
        ).restore(intent()).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.SUBMISSION_REJECTED,
                rejectedResult.status()
        );
        assertSame(rejectedResult, dispatched.get());

        FakePersistence failed = new FakePersistence(
                profile(source, LifecycleState.LOST)
        );
        failed.mode = SubmissionMode.EXCEPTIONAL;
        CompanionLifecycleAuthorResult failedResult = author(
                failed, -400L, profile -> true, dispatcher
        ).restore(intent()).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                failedResult.status()
        );
        assertSame(failed.failure, failedResult.failure());
        assertSame(failedResult, dispatched.get());
    }

    private FreeCompanionRestorationAuthor author(
            FakePersistence persistence,
            long now,
            ReleasedRestorationPolicy policy,
            FreeCompanionRestorationAuthor.ResultDispatcher dispatcher
    ) {
        return new FreeCompanionRestorationAuthor(
                persistence,
                new TameworkDormantSnapshotFactsReader(codecs),
                new TameworkRestorationSnapshotResolver(codecs),
                () -> now,
                policy,
                dispatcher
        );
    }

    private FreeCompanionRestorationAuthor.ResultDispatcher
    ignoredDispatcher() {
        return (world, actor, result) -> true;
    }

    private FreeCompanionRestorationAuthor.Intent intent() {
        return new FreeCompanionRestorationAuthor.Intent(
                "restore-click-1",
                ACTOR,
                "actor-world",
                PROFILE,
                new CompanionSpawnPlacement(
                        "target-world",
                        12.25,
                        -3.5,
                        9.75,
                        0.1F,
                        -0.2F,
                        0.3F
                )
        );
    }

    private CompanionSnapshot modernDeath(long diedAt, long availableAt) {
        SnapshotCodecRegistry.EncodedSnapshot encoded = codecs.encode(
                TameworkSnapshotCodecs.DEATH,
                2,
                DeathSnapshotV2Payload.class,
                DeathSnapshotV2Payload.capture(
                        fullState(),
                        diedAt,
                        availableAt,
                        DeathSnapshotV2Payload.DeathCauseKind.ENVIRONMENT,
                        "Lava"
                )
        );
        return snapshot(encoded, -500L);
    }

    private CompanionSnapshot modernLost() {
        SnapshotCodecRegistry.EncodedSnapshot encoded = codecs.encode(
                TameworkSnapshotCodecs.LOST,
                2,
                CoopResidentStateSnapshot.class,
                fullState()
        );
        return snapshot(encoded, -500L);
    }

    private CompanionSnapshot legacyDeath() {
        String payload = new LegacyDeathV1SnapshotCodec().encode(
                new LegacyDeathV1SnapshotCodec().decode(
                        "{\"roleId\":\"tamework_test\","
                                + "\"diedAtMs\":-500,"
                                + "\"respawnAvailableAtMs\":-600}"
                )
        );
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                TameworkSnapshotCodecs.DEATH,
                1,
                payload,
                com.alechilles.alecstamework.persistence.kernel.Sha256Hash
                        .ofUtf8(payload),
                SOURCE_REVISION,
                true,
                -500L
        );
    }

    private CompanionSnapshot snapshot(
            SnapshotCodecRegistry.EncodedSnapshot encoded,
            long createdAt
    ) {
        return new CompanionSnapshot(
                SnapshotId.create(),
                PROFILE,
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                SOURCE_REVISION,
                true,
                createdAt
        );
    }

    private CompanionProfileReadModel profile(
            CompanionSnapshot source,
            LifecycleState state
    ) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Test",
                "tamework_test",
                null,
                null,
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                null,
                state,
                LifecycleLocation.none(),
                DORMANT_REVISION,
                null,
                -500L,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        return new CompanionProfileReadModel(
                identity, null, lifecycle, List.of(), List.of(source), null
        );
    }

    private CoopResidentStateSnapshot fullState() {
        return new CoopResidentStateSnapshot(
                SOURCE.value(),
                null,
                -1,
                "tamework_test",
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
                0.75,
                -700L
        );
    }

    private static OperationWorkflowResult published(
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
        OperationEnvelope envelope = new OperationEnvelope(
                operationId,
                idempotencyKey,
                new OperationKind("test_restoration"),
                1,
                "{}",
                OperationPhase.PUBLISHED,
                "test",
                null,
                null,
                0L,
                1,
                null,
                null,
                -10L,
                -9L,
                -9L,
                -8L,
                -8L,
                List.of(OperationScope.operation(operationId))
        );
        return new OperationWorkflowResult(
                OperationWorkflowResult.Status.PUBLISHED,
                envelope,
                List.of(),
                null
        );
    }

    private record SourceCase(
            CompanionSnapshot snapshot,
            LifecycleState state,
            long now
    ) {
    }

    private enum SubmissionMode {
        PUBLISHED,
        REJECTED,
        EXCEPTIONAL
    }

    private static final class FakePersistence
            implements FreeCompanionRestorationAuthor.PersistencePort {
        private final CompanionProfileReadModel profile;
        private final RuntimeException failure =
                new RuntimeException("workflow failed");
        private SubmissionMode mode = SubmissionMode.PUBLISHED;
        private int submissions;
        private CompanionRestorationRequest request;

        private FakePersistence(CompanionProfileReadModel profile) {
            this.profile = profile;
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.found(profile, 1L)
            );
        }

        @Override
        public PublicOperationSubmission restore(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionRestorationRequest request
        ) {
            submissions++;
            this.request = request;
            if (mode == SubmissionMode.REJECTED) {
                return new PublicOperationSubmission(
                        PublicOperationSubmission.Admission.REJECTED,
                        CompletableFuture.failedFuture(failure)
                );
            }
            if (mode == SubmissionMode.EXCEPTIONAL) {
                return new PublicOperationSubmission(
                        PublicOperationSubmission.Admission.ACCEPTED,
                        CompletableFuture.failedFuture(failure)
                );
            }
            return new PublicOperationSubmission(
                    PublicOperationSubmission.Admission.ACCEPTED,
                    CompletableFuture.completedFuture(
                            published(operationId, idempotencyKey)
                    )
            );
        }
    }
}
