package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptFormula;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileProjectionState;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
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
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical alias, adoption, and terminal-outcome tests for spawner capture authoring. */
class SpawnerCaptureAuthorTest {
    private static final NpcAlias ALIAS = NpcAlias.parse(
            "81000000-0000-0000-0000-000000000001"
    );
    private static final ProfileId IMPORTED_PROFILE = ProfileId.parse(
            "81000000-0000-0000-0000-000000000099"
    );
    private static final NpcAlias OTHER_ALIAS = NpcAlias.parse(
            "81000000-0000-0000-0000-000000000003"
    );
    private static final UUID ACTOR = UUID.fromString(
            "81000000-0000-0000-0000-000000000002"
    );

    @Test
    void importedAliasIsCanonicalizedBeforeSnapshotAndArtifactFreeze() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                profile(IMPORTED_PROFILE),
                projection(IMPORTED_PROFILE)
        );
        AtomicInteger events = new AtomicInteger();
        SpawnerCaptureAuthor author = author(
                persistence,
                (profile, evidence) -> events.incrementAndGet()
        );

        SpawnerPersistenceAuthorResult result = author.capture(
                intent(new ProfileId(ALIAS.value()))
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(0, persistence.adoptions);
        assertEquals(IMPORTED_PROFILE, persistence.capture.profileId());
        assertEquals(IMPORTED_PROFILE, persistence.lastReadProfile);
        UUID taggedProfile = UUID.fromString(BsonDocument.parse(
                persistence.capture.artifact().metadataExtendedJson()
        ).getString(
                TameworkMetadataKeys.COMPANION_PROFILE_ID
        ).getValue());
        assertEquals(IMPORTED_PROFILE.value(), taggedProfile);
        assertEquals(1, events.get());
    }

    @Test
    void absentAliasAdoptsTheUuidProfileBeforeSubmittingCapture() {
        ProfileId derived = new ProfileId(ALIAS.value());
        FakePersistence persistence = new FakePersistence(
                derived,
                profile(derived),
                null
        );
        persistence.absentBeforeAdoption = true;

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(derived)).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(1, persistence.adoptions);
        assertNotNull(persistence.adoption);
        assertEquals(derived, persistence.adoption.profileId());
        assertEquals(ALIAS, persistence.adoption.alias());
        assertEquals(derived, persistence.capture.profileId());
    }

    @Test
    void observedLiveAdoptionIsCapturedWithoutACompetingAdoption() {
        ProfileId derived = new ProfileId(ALIAS.value());
        FakePersistence persistence = new FakePersistence(
                derived,
                observedLiveProfile(derived),
                projection(derived)
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(derived)).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(0, persistence.adoptions);
        assertEquals(derived, persistence.capture.profileId());
        assertEquals(LifecycleRevision.INITIAL,
                persistence.capture.expectedLifecycleRevision());
    }

    @Test
    void currentLiveAliasInAnotherWorldIsReconciledBeforeCapture() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                profileInWorld(IMPORTED_PROFILE, "old_world"),
                projection(IMPORTED_PROFILE)
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(1, persistence.reconciliations);
        assertEquals("world", persistence.capture.targetWorldKey());
        assertEquals(
                new LifecycleRevision(4L),
                persistence.capture.expectedLifecycleRevision()
        );
    }

    @Test
    void currentUnloadedAliasIsReconciledBeforeCapture() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                unloadedProfile(IMPORTED_PROFILE),
                projection(IMPORTED_PROFILE)
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(1, persistence.reconciliations);
        assertEquals("world", persistence.capture.targetWorldKey());
    }

    @Test
    void historicalAliasFailsClosedWithDistinctReason() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                profileWithCurrentAlias(IMPORTED_PROFILE, OTHER_ALIAS),
                projection(IMPORTED_PROFILE)
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                result.status()
        );
        assertEquals("capture_alias_not_current", result.detail());
        assertEquals(0, persistence.reconciliations);
        assertEquals(null, persistence.capture);
    }

    @Test
    void returnedRetiredAliasIsResolvedBeforeAdoption() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                profileWithCurrentAlias(IMPORTED_PROFILE, OTHER_ALIAS),
                null
        );
        persistence.profileByAlias = persistence.profile;

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(new ProfileId(ALIAS.value())))
                .toCompletableFuture().join();

        // Protects the 2026-08-13 returned-original report: a retired UUID
        // must not start ADOPT_LIVE as a second profile.
        assertEquals(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                result.status()
        );
        assertEquals("capture_alias_not_current", result.detail());
        assertEquals(0, persistence.adoptions);
        assertEquals(null, persistence.capture);
    }

    @Test
    void activeOperationFailsClosedWithDistinctReason() {
        CompanionProfileReadModel base = profile(IMPORTED_PROFILE);
        CompanionLifecycle current = base.lifecycle();
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                withLifecycle(base, new CompanionLifecycle(
                        IMPORTED_PROFILE,
                        current.ownerId(),
                        current.state(),
                        current.location(),
                        current.revision(),
                        new OperationId(UUID.fromString(
                                "81000000-0000-0000-0000-000000000004"
                        )),
                        current.stateChangedAtMs(),
                        current.lastReconciledGeneration(),
                        null,
                        current.ownerWorldKey()
                )),
                projection(IMPORTED_PROFILE)
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                result.status()
        );
        assertEquals("capture_operation_in_progress", result.detail());
        assertEquals(0, persistence.reconciliations);
    }

    @Test
    void rejectedAndCompensatedCapturesRemainDistinctTerminalResults() {
        FakePersistence rejected = new FakePersistence(
                IMPORTED_PROFILE,
                profile(IMPORTED_PROFILE),
                projection(IMPORTED_PROFILE)
        );
        rejected.captureMode = CaptureMode.REJECTED;
        SpawnerPersistenceAuthorResult rejectedResult = author(
                rejected,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        FakePersistence compensated = new FakePersistence(
                IMPORTED_PROFILE,
                profile(IMPORTED_PROFILE),
                projection(IMPORTED_PROFILE)
        );
        compensated.captureMode = CaptureMode.COMPENSATED;
        SpawnerPersistenceAuthorResult compensatedResult = author(
                compensated,
                (profile, evidence) -> {
                }
        ).capture(intent(IMPORTED_PROFILE)).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.SUBMISSION_REJECTED,
                rejectedResult.status()
        );
        assertEquals(
                SpawnerPersistenceAuthorResult.Status.COMPENSATED,
                compensatedResult.status()
        );
    }

    @Test
    void resolvedFailureUsesCanonicalOperationWithoutCaptureEvent() {
        FakePersistence persistence = new FakePersistence(
                IMPORTED_PROFILE,
                profile(IMPORTED_PROFILE),
                projection(IMPORTED_PROFILE)
        );
        AtomicInteger events = new AtomicInteger();
        UUID attemptId = UUID.fromString(
                "81000000-0000-0000-0000-000000000070"
        );
        SpawnerCaptureIntent failed = new SpawnerCaptureIntent(
                attemptId.toString(),
                ACTOR,
                "world",
                2,
                stack("capture-device-empty"),
                null,
                null,
                null,
                IMPORTED_PROFILE,
                ALIAS,
                null,
                null,
                null,
                "tamework_test",
                failedResolution(attemptId),
                null
        );

        SpawnerPersistenceAuthorResult result = author(
                persistence,
                (profile, evidence) -> events.incrementAndGet()
        ).capture(failed).toCompletableFuture().join();

        assertTrue(result.published());
        assertTrue(persistence.capture.failedAttempt());
        assertEquals(
                attemptId.toString(),
                persistence.capture.source().receiptKey()
        );
        assertEquals(0, events.get());
    }

    private SpawnerCaptureAuthor author(
            FakePersistence persistence,
            SpawnerCapturePublishedEventSink events
    ) {
        TameworkFullStateSnapshotReader reader =
                new TameworkFullStateSnapshotReader(
                        (reference, store, uuid, context) -> fullState()
                );
        return new SpawnerCaptureAuthor(
                persistence,
                new SpawnerCaptureEvidenceFreezer(
                        reader,
                        new HytaleCapturedArtifactAdapter(
                                HytaleItemStackTestFixture::stack
                        ),
                        new SpawnerCaptureSnapshotMapper(),
                        () -> -500L
                ),
                new SpawnerCaptureAdoptionFactory(),
                events,
                (world, actor, effect, result) -> {
                }
        );
    }

    private SpawnerCaptureIntent intent(ProfileId profileId) {
        return new SpawnerCaptureIntent(
                "capture-click-1",
                ACTOR,
                "world",
                2,
                stack("capture-device-empty"),
                stack("capture-device-filled"),
                null,
                null,
                profileId,
                ALIAS,
                null,
                null,
                null,
                "tamework_test",
                null
        );
    }

    private ItemStack stack(String itemId) {
        return HytaleItemStackTestFixture.stack(
                itemId,
                new BsonDocument()
        );
    }

    private CaptureAttemptResolution failedResolution(UUID attemptId) {
        return new CaptureAttemptResolution(
                attemptId,
                "tamework_test",
                new CaptureAttemptFormula(
                        "test-stone",
                        7L,
                        CaptureChanceMode.PROBABILITY,
                        1,
                        0.25D,
                        0.1D,
                        0.0D,
                        1.0D,
                        "test-policy",
                        3L,
                        1,
                        0.1D,
                        1.0D,
                        0.2D,
                        null,
                        com.alechilles.alecstamework.persistence.kernel
                                .Sha256Hash.ofUtf8("[]"),
                        4L
                ),
                CaptureSourceConsumption.RESOLVED_ATTEMPT,
                CaptureSuccessDisposition.CAPTURED_ITEM,
                CaptureAttemptResolution.Outcome.FAILED_ROLL,
                "capture-probability-failure",
                0.25D,
                false,
                0.5D,
                0.75D,
                -250L
        );
    }

    private CoopResidentStateSnapshot fullState() {
        return new CoopResidentStateSnapshot(
                ALIAS.value(),
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
                0.75D,
                -600L
        );
    }

    private CompanionProfileProjectionState projection(ProfileId profileId) {
        return new CompanionProfileProjectionState(
                profileId,
                ALIAS,
                LifecycleState.ACTIVE,
                null,
                null,
                "tamework_test",
                "Test companion",
                null,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                -500L
        );
    }

    private CompanionProfileReadModel profile(ProfileId profileId) {
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Test companion",
                "tamework_test",
                "{}",
                com.alechilles.alecstamework.persistence.kernel.Sha256Hash
                        .ofUtf8("{}"),
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionAlias alias = new CompanionAlias(
                ALIAS,
                profileId,
                1L,
                CompanionAlias.State.CURRENT,
                null,
                -800L,
                null
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                null,
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(ALIAS.toString(), "world"),
                new LifecycleRevision(3L),
                null,
                -700L,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        return new CompanionProfileReadModel(
                identity,
                alias,
                lifecycle,
                List.of(),
                List.of(),
                null
        );
    }

    private CompanionProfileReadModel profileInWorld(
            ProfileId profileId,
            String worldKey
    ) {
        CompanionProfileReadModel base = profile(profileId);
        CompanionLifecycle current = base.lifecycle();
        return withLifecycle(base, new CompanionLifecycle(
                profileId,
                current.ownerId(),
                LifecycleState.ACTIVE,
                LifecycleLocation.liveEntity(ALIAS.toString(), worldKey),
                current.revision(),
                null,
                current.stateChangedAtMs(),
                current.lastReconciledGeneration(),
                null,
                current.ownerWorldKey()
        ));
    }

    private CompanionProfileReadModel profileWithCurrentAlias(
            ProfileId profileId,
            NpcAlias alias
    ) {
        CompanionProfileReadModel base = profile(profileId);
        CompanionAlias current = base.currentAlias();
        return new CompanionProfileReadModel(
                base.identity(),
                new CompanionAlias(
                        alias,
                        profileId,
                        current.generation(),
                        CompanionAlias.State.CURRENT,
                        current.leaseOperationId(),
                        current.mappedAtMs(),
                        null
                ),
                base.lifecycle(),
                base.toolLinks(),
                base.currentSnapshots(),
                base.currentCoopSlot()
        );
    }

    private CompanionProfileReadModel unloadedProfile(ProfileId profileId) {
        CompanionProfileReadModel base = profile(profileId);
        CompanionLifecycle current = base.lifecycle();
        return withLifecycle(base, new CompanionLifecycle(
                profileId,
                current.ownerId(),
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                current.revision(),
                null,
                current.stateChangedAtMs(),
                current.lastReconciledGeneration(),
                null,
                current.ownerWorldKey()
        ));
    }

    private CompanionProfileReadModel withLifecycle(
            CompanionProfileReadModel base,
            CompanionLifecycle lifecycle
    ) {
        return new CompanionProfileReadModel(
                base.identity(),
                base.currentAlias(),
                lifecycle,
                base.toolLinks(),
                base.currentSnapshots(),
                base.currentCoopSlot()
        );
    }

    private CompanionProfileReadModel observedLiveProfile(ProfileId profileId) {
        long observedAt = -500L;
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Test companion",
                "tamework_test",
                "{}",
                com.alechilles.alecstamework.persistence.kernel.Sha256Hash
                        .ofUtf8("{}"),
                "world",
                observedAt,
                observedAt,
                observedAt,
                0L
        );
        CompanionProfileMutation.AdoptLive adoption =
                new CompanionProfileMutation.AdoptLive(
                        identity,
                        ALIAS,
                        new OwnerId(ACTOR),
                        "world",
                        List.of(),
                        observedAt
                );
        return new CompanionProfileReadModel(
                identity,
                new CompanionAlias(
                        ALIAS,
                        profileId,
                        0L,
                        CompanionAlias.State.CURRENT,
                        null,
                        observedAt,
                        null
                ),
                adoption.initialLifecycle(),
                List.of(),
                List.of(),
                null
        );
    }

    private static OperationWorkflowResult outcome(
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            OperationPhase phase,
            OperationWorkflowResult.Status status
    ) {
        boolean published = phase == OperationPhase.PUBLISHED;
        boolean compensated = phase == OperationPhase.COMPENSATED;
        OperationEnvelope envelope = new OperationEnvelope(
                operationId,
                idempotencyKey,
                new OperationKind("test_spawner_capture"),
                1,
                "{}",
                phase,
                "test",
                null,
                null,
                0L,
                1,
                null,
                null,
                -20L,
                -10L,
                published ? -9L : null,
                published ? -8L : null,
                published || compensated ? -8L : null,
                List.of(OperationScope.operation(operationId))
        );
        return new OperationWorkflowResult(
                status,
                envelope,
                List.of(),
                null
        );
    }

    private enum CaptureMode {
        PUBLISHED,
        COMPENSATED,
        REJECTED
    }

    private static final class FakePersistence
            implements SpawnerCaptureAuthor.PersistencePort {
        private final ProfileId canonicalProfile;
        private CompanionProfileReadModel profile;
        private final CompanionProfileProjectionState projection;
        private boolean absentBeforeAdoption;
        private boolean adopted;
        private int adoptions;
        private int reconciliations;
        private CaptureMode captureMode = CaptureMode.PUBLISHED;
        private ProfileId lastReadProfile;
        private CompanionProfileReadModel profileByAlias;
        private CompanionProfileMutation.AdoptLive adoption;
        private CompanionCaptureRequest capture;

        private FakePersistence(
                ProfileId canonicalProfile,
                CompanionProfileReadModel profile,
                CompanionProfileProjectionState projection
        ) {
            this.canonicalProfile = canonicalProfile;
            this.profile = profile;
            this.projection = projection;
        }

        @Override
        public Optional<CompanionProfileProjectionState> projectedProfile(
                NpcAlias alias
        ) {
            return Optional.ofNullable(projection);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            lastReadProfile = profileId;
            if (!canonicalProfile.equals(profileId)) {
                return CompletableFuture.completedFuture(
                        PersistenceReadResult.absent()
                );
            }
            if (absentBeforeAdoption && !adopted) {
                return CompletableFuture.completedFuture(
                        PersistenceReadResult.absent()
                );
            }
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.found(profile, 1L)
            );
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(NpcAlias alias) {
            return CompletableFuture.completedFuture(
                    profileByAlias == null
                            ? PersistenceReadResult.absent()
                            : PersistenceReadResult.found(
                                    profileByAlias, 1L
                            )
            );
        }

        @Override
        public PublicOperationSubmission adopt(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation.AdoptLive adoption
        ) {
            adoptions++;
            adopted = true;
            this.adoption = adoption;
            return accepted(outcome(
                    operationId,
                    idempotencyKey,
                    OperationPhase.PUBLISHED,
                    OperationWorkflowResult.Status.PUBLISHED
            ));
        }

        @Override
        public PublicOperationSubmission capture(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCaptureRequest capture
        ) {
            this.capture = capture;
            if (captureMode == CaptureMode.REJECTED) {
                return new PublicOperationSubmission(
                        PublicOperationSubmission.Admission.REJECTED,
                        CompletableFuture.failedFuture(
                                new IllegalStateException("rejected")
                        )
                );
            }
            OperationPhase phase = captureMode == CaptureMode.COMPENSATED
                    ? OperationPhase.COMPENSATED
                    : OperationPhase.PUBLISHED;
            OperationWorkflowResult.Status status =
                    captureMode == CaptureMode.COMPENSATED
                            ? OperationWorkflowResult.Status.COMPENSATED
                            : OperationWorkflowResult.Status.PUBLISHED;
            return accepted(outcome(
                    operationId, idempotencyKey, phase, status
            ));
        }

        @Override
        public PublicOperationSubmission reconcile(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation.ReconcileLoaded reconciliation
        ) {
            reconciliations++;
            profile = new CompanionProfileReadModel(
                    profile.identity(),
                    profile.currentAlias(),
                    reconciliation.resolvedLifecycle(profile.lifecycle()),
                    profile.toolLinks(),
                    profile.currentSnapshots(),
                    profile.currentCoopSlot()
            );
            return accepted(outcome(
                    operationId,
                    idempotencyKey,
                    OperationPhase.PUBLISHED,
                    OperationWorkflowResult.Status.PUBLISHED
            ));
        }

        private PublicOperationSubmission accepted(
                OperationWorkflowResult result
        ) {
            return new PublicOperationSubmission(
                    PublicOperationSubmission.Admission.ACCEPTED,
                    CompletableFuture.completedFuture(result)
            );
        }
    }
}
