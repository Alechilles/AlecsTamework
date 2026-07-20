package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.items.LoadedNpcIdentityIndex;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationFinalizationServiceTest {
    @Test
    void liveEvidenceMutationDuringReadyWriteInvalidatesCommittedSession() {
        Fixture fixture = fixture();
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());
        assertFalse(completion.isDone());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                fixture.projections().snapshot().state()
        );

        fixture.liveEvidence().advance();
        fixture.readyCommit().complete(true);

        assertDegradedAfterPostCommitMutation(
                fixture,
                completion.join(),
                "reconciliation-live-evidence-mutated-during-final-reload"
        );
    }

    @Test
    void loadedIdentityMutationDuringReadyWriteInvalidatesCommittedSession() {
        Fixture fixture = fixture();
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());
        assertFalse(completion.isDone());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                fixture.projections().snapshot().state()
        );

        fixture.loadedIdentities().recordAdded(
                UUID.fromString("00000000-0000-0000-0000-000000000911"),
                new LoadedNpcIdentityIndex.Location("alpha", "store-alpha")
        );
        fixture.readyCommit().complete(true);

        assertDegradedAfterPostCommitMutation(
                fixture,
                completion.join(),
                "reconciliation-loaded-identity-mutated-during-final-reload"
        );
    }

    @Test
    void successfulReadyCommitSealsOnlyAfterTheDurableTransition() {
        Fixture fixture = fixture();
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());

        assertFalse(completion.isDone());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                fixture.projections().snapshot().state()
        );
        fixture.readyCommit().complete(true);

        CompanionPopulationReconciliationService.Result result = completion.join();
        assertEquals(CompanionPopulationReconciliationService.Status.READY, result.status());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SEALED,
                fixture.projections().snapshot().state()
        );
        assertEquals(0, fixture.invalidations().get());
        assertTrue(fixture.recoveryProofs().isSealedConflictFree("profile-a"));
        assertNull(result.loadedIdentityRevision());
        assertNull(result.liveEvidenceRevision());
        assertNull(result.projectionEvidenceSet());
    }

    @Test
    void readyCoverageWaitsForTheSessionCommitAndExactSeal() {
        CompletableFuture<Boolean> globalReady = new CompletableFuture<>();
        CompletableFuture<Boolean> perWorldReady = new CompletableFuture<>();
        RecordingCoverageWriter coverage = new RecordingCoverageWriter(
                globalReady, perWorldReady
        );
        Fixture fixture = fixture(coverage);
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());

        assertEquals(List.of(), coverage.readyDimensions());
        fixture.readyCommit().complete(true);
        assertFalse(completion.isDone());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SEALED,
                fixture.projections().snapshot().state()
        );
        assertEquals(
                List.of(CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER),
                coverage.readyDimensions()
        );

        globalReady.complete(true);
        assertFalse(completion.isDone());
        assertEquals(
                List.of(
                        CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER,
                        CompanionPopulationCoverageRecord.Dimension.PER_WORLD_OWNER
                ),
                coverage.readyDimensions()
        );
        perWorldReady.complete(true);

        assertEquals(
                CompanionPopulationReconciliationService.Status.READY,
                completion.join().status()
        );
        assertEquals(0, fixture.invalidations().get());
    }

    @Test
    void mutationDuringFinalCoverageWriteRollsBackEveryReadyAuthority() {
        CompletableFuture<Boolean> globalReady = new CompletableFuture<>();
        CompletableFuture<Boolean> perWorldReady = new CompletableFuture<>();
        RecordingCoverageWriter coverage = new RecordingCoverageWriter(
                globalReady, perWorldReady
        );
        Fixture fixture = fixture(coverage);
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());

        fixture.readyCommit().complete(true);
        globalReady.complete(true);
        fixture.liveEvidence().advance();
        perWorldReady.complete(true);
        CompanionPopulationReconciliationService.Result result = completion.join();

        assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
        assertEquals(
                "reconciliation-live-evidence-mutated-during-final-reload",
                result.reason()
        );
        assertEquals(1, fixture.invalidations().get());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                fixture.projections().snapshot().state()
        );
        assertFalse(fixture.recoveryProofs().isSealedConflictFree("profile-a"));
        assertEquals(2, coverage.count(CompanionPopulationCoverageRecord.State.DEGRADED));
    }

    @Test
    void projectionInvalidationDuringFinalCoverageWriteRollsBackEveryReadyAuthority() {
        CompletableFuture<Boolean> globalReady = new CompletableFuture<>();
        CompletableFuture<Boolean> perWorldReady = new CompletableFuture<>();
        RecordingCoverageWriter coverage = new RecordingCoverageWriter(
                globalReady, perWorldReady
        );
        Fixture fixture = fixture(coverage);
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());

        fixture.readyCommit().complete(true);
        globalReady.complete(true);
        fixture.projections().degrade(
                "finalization-test-epoch", "concurrent-projection-invalidation"
        );
        perWorldReady.complete(true);
        CompanionPopulationReconciliationService.Result result = completion.join();

        assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
        assertEquals(
                "reconciliation-projection-evidence-invalidated-during-coverage-publish",
                result.reason()
        );
        assertEquals(1, fixture.invalidations().get());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                fixture.projections().snapshot().state()
        );
        assertEquals(2, coverage.count(CompanionPopulationCoverageRecord.State.DEGRADED));
    }

    @Test
    void synchronousNullRejectedAndExceptionalCoverageWritesRollBackReadySession() {
        List<CompanionPopulationCoveragePublisher.CoverageWriter> failures = List.of(
                coverage -> {
                    if (coverage.state() == CompanionPopulationCoverageRecord.State.READY) {
                        throw new IllegalStateException("synchronous coverage failure");
                    }
                    return CompletableFuture.completedFuture(true);
                },
                coverage -> coverage.state() == CompanionPopulationCoverageRecord.State.READY
                        ? null : CompletableFuture.completedFuture(true),
                coverage -> coverage.state() == CompanionPopulationCoverageRecord.State.READY
                        ? CompletableFuture.completedFuture(false)
                        : CompletableFuture.completedFuture(true),
                coverage -> coverage.state() == CompanionPopulationCoverageRecord.State.READY
                        ? CompletableFuture.failedFuture(
                                new IllegalStateException("exceptional coverage failure")
                        ) : CompletableFuture.completedFuture(true)
        );

        for (CompanionPopulationCoveragePublisher.CoverageWriter coverageFailure : failures) {
            Fixture fixture = fixture(coverageFailure);
            CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                    fixture.finalization().completeAsync(fixture.readyResult());
            fixture.readyCommit().complete(true);
            CompanionPopulationReconciliationService.Result result = completion.join();

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
            assertEquals("reconciliation-final-coverage-publish-failed", result.reason());
            assertEquals(1, fixture.invalidations().get());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    fixture.projections().snapshot().state()
            );
        }
    }

    @Test
    void synchronousNullRejectedAndExceptionalReadyTransitionsFailClosed() {
        List<CompanionPopulationFinalizationService.SessionTransition> failures = List.of(
                ignored -> {
                    throw new IllegalStateException("synchronous ready failure");
                },
                ignored -> null,
                ignored -> CompletableFuture.completedFuture(false),
                ignored -> CompletableFuture.failedFuture(
                        new IllegalStateException("exceptional ready failure")
                )
        );

        for (CompanionPopulationFinalizationService.SessionTransition readyFailure : failures) {
            AtomicInteger invalidations = new AtomicInteger();
            Fixture fixture = fixture(
                    readyFailure,
                    ignored -> {
                        invalidations.incrementAndGet();
                        return CompletableFuture.completedFuture(true);
                    },
                    new CompletableFuture<>(),
                    invalidations
            );

            CompanionPopulationReconciliationService.Result result = fixture.finalization()
                    .completeAsync(fixture.readyResult()).join();

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
            assertEquals("reconciliation-session-complete-failed", result.reason());
            assertEquals(1, invalidations.get());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    fixture.projections().snapshot().state()
            );
        }
    }

    @Test
    void failedInvalidationTransitionsRemainDegradedAfterReadyCommit() {
        List<CompanionPopulationFinalizationService.SessionTransition> failures = List.of(
                ignored -> {
                    throw new IllegalStateException("synchronous invalidation failure");
                },
                ignored -> null,
                ignored -> CompletableFuture.completedFuture(false),
                ignored -> CompletableFuture.failedFuture(
                        new IllegalStateException("exceptional invalidation failure")
                )
        );

        for (CompanionPopulationFinalizationService.SessionTransition invalidationFailure : failures) {
            CompletableFuture<Boolean> readyCommit = new CompletableFuture<>();
            AtomicInteger invalidations = new AtomicInteger();
            Fixture fixture = fixture(
                    ignored -> readyCommit,
                    ignored -> {
                        invalidations.incrementAndGet();
                        return invalidationFailure.apply(ignored);
                    },
                    readyCommit,
                    invalidations
            );
            CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                    fixture.finalization().completeAsync(fixture.readyResult());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                    fixture.projections().snapshot().state()
            );

            fixture.liveEvidence().advance();
            readyCommit.complete(true);
            CompanionPopulationReconciliationService.Result result = completion.join();

            assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
            assertEquals("reconciliation-session-invalidate-failed", result.reason());
            assertEquals(1, invalidations.get());
            assertEquals(
                    CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                    fixture.projections().snapshot().state()
            );
        }
    }

    @Test
    void sealPublicationFailureAfterReadyCommitInvalidatesSession() {
        Fixture fixture = fixture();
        CompletableFuture<CompanionPopulationReconciliationService.Result> completion =
                fixture.finalization().completeAsync(fixture.readyResult());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.SCANNING,
                fixture.projections().snapshot().state()
        );

        fixture.projections().degrade(
                "finalization-test-epoch", "concurrent-finalization-rejection"
        );
        fixture.readyCommit().complete(true);
        CompanionPopulationReconciliationService.Result result = completion.join();

        assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
        assertEquals("reconciliation-projection-evidence-publish-failed", result.reason());
        assertEquals(1, fixture.invalidations().get());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                fixture.projections().snapshot().state()
        );
    }

    private static void assertDegradedAfterPostCommitMutation(
            Fixture fixture,
            CompanionPopulationReconciliationService.Result result,
            String reason
    ) {
        assertEquals(CompanionPopulationReconciliationService.Status.DEGRADED, result.status());
        assertEquals(reason, result.reason());
        assertEquals(1, fixture.invalidations().get());
        assertEquals(
                CompanionPersistedProjectionEvidenceRegistry.State.DEGRADED,
                fixture.projections().snapshot().state()
        );
    }

    private static Fixture fixture() {
        return fixture(coverage -> CompletableFuture.completedFuture(true));
    }

    private static Fixture fixture(
            CompanionPopulationCoveragePublisher.CoverageWriter coverageWriter
    ) {
        CompletableFuture<Boolean> readyCommit = new CompletableFuture<>();
        AtomicInteger invalidations = new AtomicInteger();
        return fixture(
                ignored -> readyCommit,
                ignored -> {
                    invalidations.incrementAndGet();
                    return CompletableFuture.completedFuture(true);
                },
                readyCommit,
                invalidations,
                coverageWriter
        );
    }

    private static Fixture fixture(
            CompanionPopulationFinalizationService.SessionTransition readyTransition,
            CompanionPopulationFinalizationService.SessionTransition invalidationTransition,
            CompletableFuture<Boolean> readyCommit,
            AtomicInteger invalidations
    ) {
        return fixture(
                readyTransition,
                invalidationTransition,
                readyCommit,
                invalidations,
                coverage -> CompletableFuture.completedFuture(true)
        );
    }

    private static Fixture fixture(
            CompanionPopulationFinalizationService.SessionTransition readyTransition,
            CompanionPopulationFinalizationService.SessionTransition invalidationTransition,
            CompletableFuture<Boolean> readyCommit,
            AtomicInteger invalidations,
            CompanionPopulationCoveragePublisher.CoverageWriter coverageWriter
    ) {
        String epoch = "finalization-test-epoch";
        LoadedNpcIdentityIndex identities = new LoadedNpcIdentityIndex();
        identities.markInitializationComplete();
        CompanionLiveEvidenceRevision liveEvidence = new CompanionLiveEvidenceRevision();
        CompanionPersistedProjectionEvidenceRegistry projections =
                new CompanionPersistedProjectionEvidenceRegistry();
        projections.bindLoadedIdentityIndex(identities);
        projections.bindLiveEvidenceRevision(liveEvidence);
        projections.begin(epoch);
        CompanionPopulationCoveragePublisher coverage = new CompanionPopulationCoveragePublisher(
                catalog(), coverageWriter
        );
        ReconciliationEvidenceRecoveryProofRegistry recoveryProofs =
                new ReconciliationEvidenceRecoveryProofRegistry();
        recoveryProofs.stage(epoch, Set.of("profile-a"));
        CompanionPopulationFinalizationService finalization =
                new CompanionPopulationFinalizationService(
                        epoch,
                        identities,
                        projections,
                        liveEvidence,
                        coverage,
                        readyTransition,
                        invalidationTransition,
                        recoveryProofs
                );
        CompanionPopulationEvidenceSet evidence = new CompanionPopulationEvidenceSet(List.of());
        CompanionPopulationReconciliationService.Result readyResult =
                new CompanionPopulationReconciliationService.Result(
                        CompanionPopulationReconciliationService.Status.READY,
                        "reconciliation-ready",
                        0,
                        0,
                        0,
                        0,
                        identities.snapshot().mutationRevision(),
                        liveEvidence.capture(),
                        evidence
                );
        return new Fixture(
                finalization,
                readyResult,
                identities,
                liveEvidence,
                projections,
                readyCommit,
                invalidations,
                recoveryProofs
        );
    }

    private static CompanionPopulationReconciliationCatalog catalog() {
        return new CompanionPopulationReconciliationCatalog(
                List.of(),
                true,
                true,
                true,
                true,
                new CustomContainerReconciliationRegistry.Snapshot(
                        List.of(), true, "sealed", "generation"
                )
        );
    }

    private record Fixture(
            CompanionPopulationFinalizationService finalization,
            CompanionPopulationReconciliationService.Result readyResult,
            LoadedNpcIdentityIndex loadedIdentities,
            CompanionLiveEvidenceRevision liveEvidence,
            CompanionPersistedProjectionEvidenceRegistry projections,
            CompletableFuture<Boolean> readyCommit,
            AtomicInteger invalidations,
            ReconciliationEvidenceRecoveryProofRegistry recoveryProofs
    ) {
    }

    private static final class RecordingCoverageWriter
            implements CompanionPopulationCoveragePublisher.CoverageWriter {
        private final CompletableFuture<Boolean> globalReady;
        private final CompletableFuture<Boolean> perWorldReady;
        private final List<CompanionPopulationCoverageRecord> writes = new ArrayList<>();

        private RecordingCoverageWriter(
                CompletableFuture<Boolean> globalReady,
                CompletableFuture<Boolean> perWorldReady
        ) {
            this.globalReady = globalReady;
            this.perWorldReady = perWorldReady;
        }

        @Override
        public CompletableFuture<Boolean> write(CompanionPopulationCoverageRecord coverage) {
            writes.add(coverage);
            if (coverage.state() != CompanionPopulationCoverageRecord.State.READY) {
                return CompletableFuture.completedFuture(true);
            }
            return coverage.dimension() == CompanionPopulationCoverageRecord.Dimension.GLOBAL_OWNER
                    ? globalReady : perWorldReady;
        }

        private List<CompanionPopulationCoverageRecord.Dimension> readyDimensions() {
            return writes.stream()
                    .filter(coverage -> coverage.state()
                            == CompanionPopulationCoverageRecord.State.READY)
                    .map(CompanionPopulationCoverageRecord::dimension)
                    .toList();
        }

        private long count(CompanionPopulationCoverageRecord.State state) {
            return writes.stream().filter(coverage -> coverage.state() == state).count();
        }
    }
}
