package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.dormant.DormantSourceEvidence;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused contract tests for positive-evidence dormant authoring. */
class PositiveEvidenceDormantAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "70000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias SOURCE = new NpcAlias(UUID.fromString(
            "70000000-0000-0000-0000-000000000002"
    ));
    private static final LifecycleRevision REVISION =
            new LifecycleRevision(7);
    private static final ReconciliationGeneration GENERATION =
            new ReconciliationGeneration(3);

    @Test
    void savedDeathFreezesFullStateAndAuthorsModernExactSnapshot() {
        AtomicInteger snapshotReads = new AtomicInteger();
        CoopResidentStateSnapshot live = fullState();
        TameworkFullStateSnapshotReader reader =
                new TameworkFullStateSnapshotReader(
                        (reference, store, uuid, context) -> {
                            snapshotReads.incrementAndGet();
                            return live;
                        }
                );
        FakePersistence persistence = new FakePersistence(profile());
        PositiveEvidenceDormantAuthor author = author(
                persistence, reader, -450L
        );

        CompanionLifecycleAuthorResult result = author.makeDormant(intent(
                DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT
        )).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.PUBLISHED,
                result.status()
        );
        assertEquals(1, snapshotReads.get());
        CompanionDormantTransitionRequest request = persistence.request;
        assertEquals(REVISION, request.expectedLifecycleRevision());
        assertEquals(REVISION, request.snapshot().sourceLifecycleRevision());
        assertEquals(2, request.snapshot().payloadVersion());
        assertEquals(TameworkSnapshotCodecs.DEATH, request.snapshot().kind());
        assertEquals(
                DormantSourceEvidence.Kind.DEATH_COMPONENT,
                request.source().kind()
        );
        assertEquals(-500L, request.snapshot().createdAtMs());
        DeathSnapshotV2Payload decoded = (DeathSnapshotV2Payload)
                ((com.alechilles.alecstamework.companion.snapshot
                        .SnapshotDecodeResult.Decoded<?>) TameworkSnapshotCodecs
                        .create()
                        .decode(request.snapshot(), DeathSnapshotV2Payload.class))
                        .value();
        assertEquals(-490L, decoded.diedAtMs());
        assertEquals(-300L, decoded.respawnAvailableAtMs());
        assertEquals(SOURCE.value(), decoded.fullState().npcUuid());
        assertNotSame(live, decoded.fullState());
    }

    @Test
    void exactAliasDeathSurvivesAStaleProfileRoleVariant() {
        FakePersistence persistence = new FakePersistence(
                profile("Tamed_Deer")
        );
        PositiveEvidenceDormantAuthor author = author(
                persistence,
                new TameworkFullStateSnapshotReader(
                        (reference, store, uuid, context) ->
                                fullState("Tamed_Deer_Stag")
                ),
                -450L
        );

        CompanionLifecycleAuthorResult result = author.makeDormant(intent(
                DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT
        )).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(
                "tamed_deer_stag",
                ((DeathSnapshotV2Payload) ((com.alechilles.alecstamework
                        .companion.snapshot.SnapshotDecodeResult.Decoded<?>)
                        TameworkSnapshotCodecs.create().decode(
                                persistence.request.snapshot(),
                                DeathSnapshotV2Payload.class
                        )).value()).fullState().roleId()
        );
    }

    @Test
    void destructiveAndWorldDeletionEvidenceAuthorLostV2() {
        for (DormantCompanionObservation.Evidence evidence : List.of(
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL,
                DormantCompanionObservation.Evidence.WORLD_DELETION
        )) {
            FakePersistence persistence = new FakePersistence(profile());
            PositiveEvidenceDormantAuthor author = author(
                    persistence,
                    new TameworkFullStateSnapshotReader(
                            (reference, store, uuid, context) -> fullState()
                    ),
                    -450L
            );

            CompanionLifecycleAuthorResult result = author.makeDormant(
                    intent(evidence)
            ).toCompletableFuture().join();

            assertTrue(result.published());
            assertEquals(
                    TameworkSnapshotCodecs.LOST,
                    persistence.request.snapshot().kind()
            );
            assertEquals(2, persistence.request.snapshot().payloadVersion());
            assertEquals(
                    evidence == DormantCompanionObservation.Evidence
                            .DESTRUCTIVE_REMOVAL
                            ? DormantSourceEvidence.Kind.DESTRUCTIVE_REMOVAL
                            : DormantSourceEvidence.Kind.WORLD_DELETION,
                    persistence.request.source().kind()
            );
        }
    }

    @Test
    void worldDeletionCorrectsAStaleCanonicalWorldForTheExactLiveAlias() {
        FakePersistence persistence = new FakePersistence(
                profile("tamework_test", "old-world")
        );
        PositiveEvidenceDormantAuthor author = author(
                persistence, reader(), -450L
        );

        CompanionLifecycleAuthorResult result = author.makeDormant(intent(
                DormantCompanionObservation.Evidence.WORLD_DELETION,
                "deleted-instance"
        )).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(
                "deleted-instance",
                persistence.request.source().sourceWorldKey()
        );
        assertEquals(LifecycleState.LOST, persistence.request.targetState());
    }

    @Test
    void unloadAbsenceAndTimeoutNeverReadOrTransition() {
        AtomicInteger snapshotReads = new AtomicInteger();
        for (DormantCompanionObservation.Evidence evidence : List.of(
                DormantCompanionObservation.Evidence.UNLOAD,
                DormantCompanionObservation.Evidence.ABSENCE,
                DormantCompanionObservation.Evidence.TIMEOUT
        )) {
            FakePersistence persistence = new FakePersistence(profile());
            PositiveEvidenceDormantAuthor author = author(
                    persistence,
                    new TameworkFullStateSnapshotReader(
                            (reference, store, uuid, context) -> {
                                snapshotReads.incrementAndGet();
                                return fullState();
                            }
                    ),
                    -450L
            );

            CompanionLifecycleAuthorResult result = author.makeDormant(
                    intent(evidence)
            ).toCompletableFuture().join();

            assertEquals(
                    CompanionLifecycleAuthorResult.Status.INVALID_EVIDENCE,
                    result.status()
            );
            assertEquals(0, persistence.profileReads);
            assertEquals(0, persistence.submissions);
            assertNull(persistence.request);
        }
        assertEquals(0, snapshotReads.get());
    }

    @Test
    void rejectedAndExceptionalSubmissionsRemainExplicitFailures() {
        FakePersistence rejected = new FakePersistence(profile());
        rejected.mode = SubmissionMode.REJECTED;
        CompanionLifecycleAuthorResult rejectedResult = author(
                rejected, reader(), -450L
        ).makeDormant(intent(
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL
        )).toCompletableFuture().join();

        FakePersistence failed = new FakePersistence(profile());
        failed.mode = SubmissionMode.EXCEPTIONAL;
        CompanionLifecycleAuthorResult failedResult = author(
                failed, reader(), -450L
        ).makeDormant(intent(
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL
        )).toCompletableFuture().join();

        assertEquals(
                CompanionLifecycleAuthorResult.Status.SUBMISSION_REJECTED,
                rejectedResult.status()
        );
        assertEquals(
                CompanionLifecycleAuthorResult.Status.WORKFLOW_FAILED,
                failedResult.status()
        );
        assertSame(failed.failure, failedResult.failure());
    }

    @Test
    void publishedTransitionEmitsFromFrozenFactsAndCanonicalDormantRead() {
        FakePersistence persistence = new FakePersistence(profile());
        AtomicReference<DormantCompanionEventSink.Published> event =
                new AtomicReference<>();
        PositiveEvidenceDormantAuthor author =
                new PositiveEvidenceDormantAuthor(
                        persistence,
                        reader(),
                        TameworkSnapshotCodecs.create(),
                        () -> -450L,
                        new DormantCompanionEventFactsFreezer(),
                        event::set,
                        warning -> {
                        }
                );

        CompanionLifecycleAuthorResult result = author.makeDormant(intent(
                DormantCompanionObservation.Evidence.SAVED_DEATH_COMPONENT
        )).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(2, persistence.profileReads);
        assertEquals(
                LifecycleState.DEAD_REVIVABLE,
                event.get().canonicalProfile().lifecycle().state()
        );
        assertEquals(SOURCE.value(), event.get().facts().npcUuid());
        assertEquals(-450L, event.get().emittedAtMs());
    }

    @Test
    void releasedEventFailureWarnsWithoutChangingPublishedResult() {
        FakePersistence persistence = new FakePersistence(profile());
        AtomicReference<DormantCompanionEventWarningSink.Warning> warning =
                new AtomicReference<>();
        PositiveEvidenceDormantAuthor author =
                new PositiveEvidenceDormantAuthor(
                        persistence,
                        reader(),
                        TameworkSnapshotCodecs.create(),
                        () -> -450L,
                        new DormantCompanionEventFactsFreezer(),
                        event -> {
                            throw new IllegalStateException("listener failed");
                        },
                        warning::set
                );

        CompanionLifecycleAuthorResult result = author.makeDormant(intent(
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL
        )).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(
                "dormant_event_publish_failed",
                warning.get().code()
        );
        assertEquals(PROFILE, warning.get().profileId());
    }

    private PositiveEvidenceDormantAuthor author(
            FakePersistence persistence,
            TameworkFullStateSnapshotReader reader,
            long now
    ) {
        return new PositiveEvidenceDormantAuthor(
                persistence,
                reader,
                TameworkSnapshotCodecs.create(),
                () -> now,
                new DormantCompanionEventFactsFreezer(),
                event -> {
                },
                warning -> {
                }
        );
    }

    private TameworkFullStateSnapshotReader reader() {
        return new TameworkFullStateSnapshotReader(
                (reference, store, uuid, context) -> fullState()
        );
    }

    private PositiveEvidenceDormantAuthor.Intent intent(
            DormantCompanionObservation.Evidence evidence
    ) {
        return intent(evidence, "world");
    }

    private PositiveEvidenceDormantAuthor.Intent intent(
            DormantCompanionObservation.Evidence evidence,
            String worldKey
    ) {
        DormantCompanionObservation.DeathObservation death =
                evidence == DormantCompanionObservation.Evidence
                        .SAVED_DEATH_COMPONENT
                        ? new DormantCompanionObservation.DeathObservation(
                        -490L,
                        -300L,
                        DeathSnapshotV2Payload.DeathCauseKind.NPC,
                        "Razorbeak"
                        )
                        : null;
        DormantCompanionObservation.LostObservation lost =
                evidence == DormantCompanionObservation.Evidence
                        .DESTRUCTIVE_REMOVAL
                        || evidence == DormantCompanionObservation.Evidence
                        .WORLD_DELETION
                        ? new DormantCompanionObservation.LostObservation(
                        -510L, 2
                )
                        : null;
        DormantCompanionObservation observation =
                new DormantCompanionObservation(
                        "observation-" + evidence.name(),
                        PROFILE,
                        SOURCE,
                        worldKey,
                        evidence,
                        "receipt-" + evidence.name(),
                        -500L,
                        new DormantCompanionObservation.PositionObservation(
                                1.0, 2.0, 3.0
                        ),
                        death,
                        lost
                );
        return new PositiveEvidenceDormantAuthor.Intent(
                observation, null, null, "tamework_test"
        );
    }

    private CompanionProfileReadModel profile() {
        return profile("tamework_test");
    }

    private CompanionProfileReadModel profile(String roleId) {
        return profile(roleId, "world");
    }

    private CompanionProfileReadModel profile(
            String roleId,
            String worldKey
    ) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Test",
                roleId,
                null,
                null,
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionAlias alias = new CompanionAlias(
                SOURCE, PROFILE, 2L, CompanionAlias.State.CURRENT,
                null, -800L, null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(SOURCE.toString(), worldKey),
                REVISION,
                null,
                -800L,
                GENERATION,
                null,
                null
        );
        return new CompanionProfileReadModel(
                identity, alias, lifecycle, List.of(), List.of(), null
        );
    }

    private CoopResidentStateSnapshot fullState() {
        return fullState("tamework_test");
    }

    private CoopResidentStateSnapshot fullState(String roleId) {
        return new CoopResidentStateSnapshot(
                SOURCE.value(),
                null,
                -1,
                roleId,
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
                new OperationKind("test_dormant"),
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

    private enum SubmissionMode {
        PUBLISHED,
        REJECTED,
        EXCEPTIONAL
    }

    private static final class FakePersistence
            implements PositiveEvidenceDormantAuthor.PersistencePort {
        private final CompanionProfileReadModel profile;
        private final RuntimeException failure =
                new RuntimeException("workflow failed");
        private SubmissionMode mode = SubmissionMode.PUBLISHED;
        private int profileReads;
        private int submissions;
        private CompanionDormantTransitionRequest request;

        private FakePersistence(CompanionProfileReadModel profile) {
            this.profile = profile;
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            profileReads++;
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.found(
                            request == null ? profile : dormantProfile(),
                            1L
                    )
            );
        }

        private CompanionProfileReadModel dormantProfile() {
            CompanionLifecycle before = profile.lifecycle();
            CompanionLifecycle dormant = new CompanionLifecycle(
                    before.profileId(),
                    before.ownerId(),
                    request.targetState(),
                    LifecycleLocation.none(),
                    before.revision().next(),
                    null,
                    request.source().observedAtMs(),
                    request.source().observedGeneration(),
                    null,
                    before.ownerWorldKey()
            );
            return new CompanionProfileReadModel(
                    profile.identity(),
                    null,
                    dormant,
                    profile.toolLinks(),
                    List.of(request.snapshot()),
                    null
            );
        }

        @Override
        public PublicOperationSubmission makeDormant(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionDormantTransitionRequest request
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
