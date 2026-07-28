package com.alechilles.alecstamework.items.coop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.PublicOperationSubmission;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class DirectLiveCoopAuthorTest {
    private static final long NOW = -62135596800000L;

    @Test
    void directLiveCaptureRegistersAdoptsThenSubmitsExactCapture() {
        FakePersistence persistence = new FakePersistence();
        DirectLiveCoopAuthor author = author(persistence);
        CoopSlotKey slot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(slot);

        DirectLiveCoopAuthor.Outcome outcome =
                author.captureLive(slot, source).toCompletableFuture().join();

        assertEquals(DirectLiveCoopAuthor.Outcome.CAPTURE_SUBMITTED, outcome);
        assertEquals(1, persistence.registrationCalls);
        assertEquals(1, persistence.adoptionCalls);
        assertEquals(1, persistence.captureCalls);
        CompanionCoopCaptureRequest capture = persistence.lastCapture;
        assertNotNull(capture);
        assertEquals(source.profileId(), capture.profileId());
        assertEquals(source.alias(), capture.source().sourceAlias());
        assertEquals(slot, capture.targetSlot());
        assertEquals(LifecycleRevision.INITIAL, capture.expectedLifecycleRevision());
        assertEquals(LifecycleRevision.INITIAL.next(),
                capture.snapshot().sourceLifecycleRevision());
        assertEquals(
                source.encodedSnapshot().payloadJson(),
                capture.snapshot().payloadJson()
        );
        assertEquals(
                source.encodedSnapshot().payloadHash(),
                capture.snapshot().payloadHash()
        );
        assertTrue(capture.source().retirementReceiptKey()
                .startsWith("coop-retire:"));
    }

    @Test
    void observedLiveAdoptionIsCapturedWithoutACompetingAdoption() {
        FakePersistence persistence = new FakePersistence();
        CoopSlotKey slot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(slot);
        CompanionProfileReadModel observed = observedLiveProfile(source);
        persistence.profilesByAlias.put(source.alias(), observed);
        persistence.profilesById.put(source.profileId(), observed);

        DirectLiveCoopAuthor.Outcome outcome =
                author(persistence).captureLive(slot, source)
                        .toCompletableFuture()
                        .join();

        assertEquals(DirectLiveCoopAuthor.Outcome.CAPTURE_SUBMITTED, outcome);
        assertEquals(0, persistence.adoptionCalls);
        assertEquals(1, persistence.captureCalls);
        assertEquals(
                LifecycleRevision.INITIAL,
                persistence.lastCapture.expectedLifecycleRevision()
        );
    }

    @Test
    void captureRejectsMismatchedAliasProfileWorldAndSlotEvidenceBeforeMutation() {
        CoopSlotKey slot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource valid = liveSource(slot);
        NpcAlias otherAlias = new NpcAlias(UUID.fromString(
                "00000000-0000-0000-0000-000000000111"
        ));
        ProfileId otherProfile = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000112"
        ));

        assertInvalidSourceRejectedWithoutMutation(
                slot,
                new DirectLiveCoopAuthor.LiveNpcSource(
                        valid.profileId(),
                        otherAlias,
                        valid.worldKey(),
                        valid.adoption(),
                        valid.observedSlot(),
                        valid.encodedSnapshot()
                )
        );
        assertInvalidSourceRejectedWithoutMutation(
                slot,
                new DirectLiveCoopAuthor.LiveNpcSource(
                        otherProfile,
                        valid.alias(),
                        valid.worldKey(),
                        valid.adoption(),
                        valid.observedSlot(),
                        valid.encodedSnapshot()
                )
        );
        assertInvalidSourceRejectedWithoutMutation(
                slot,
                new DirectLiveCoopAuthor.LiveNpcSource(
                        valid.profileId(),
                        valid.alias(),
                        "other-world",
                        valid.adoption(),
                        valid.observedSlot(),
                        valid.encodedSnapshot()
                )
        );
        assertInvalidSourceRejectedWithoutMutation(
                slot,
                new DirectLiveCoopAuthor.LiveNpcSource(
                        valid.profileId(),
                        valid.alias(),
                        valid.worldKey(),
                        valid.adoption(),
                        slot(1),
                        valid.encodedSnapshot()
                )
        );
    }

    @Test
    void captureRejectsPersistedAliasProfileWorldAndLifecycleDisagreement() {
        CoopSlotKey slot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(slot);
        ProfileId otherProfile = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000113"
        ));
        NpcAlias otherAlias = new NpcAlias(UUID.fromString(
                "00000000-0000-0000-0000-000000000114"
        ));

        assertProfileDisagreementRejected(
                slot,
                source,
                liveProfile(
                        otherProfile,
                        source.alias(),
                        source.worldKey(),
                        LifecycleState.ACTIVE
                )
        );
        assertProfileDisagreementRejected(
                slot,
                source,
                liveProfile(
                        source.profileId(),
                        otherAlias,
                        source.worldKey(),
                        LifecycleState.ACTIVE
                )
        );
        assertProfileDisagreementRejected(
                slot,
                source,
                liveProfile(
                        source.profileId(),
                        source.alias(),
                        "other-world",
                        LifecycleState.ACTIVE
                )
        );
        assertProfileDisagreementRejected(
                slot,
                source,
                liveProfile(
                        source.profileId(),
                        source.alias(),
                        source.worldKey(),
                        LifecycleState.DEAD_REVIVABLE
                )
        );
    }

    @Test
    void authoritativeReleaseSubmitsExactResidencySnapshotAndFrozenPlacement() {
        FakePersistence persistence = new FakePersistence();
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        persistence.occupancies.put(slot, occupancy);
        persistence.residencies.put(
                occupancy.residency().profileId(), occupancy.residency()
        );
        persistence.profilesById.put(
                occupancy.residency().profileId(), coopedProfile(occupancy)
        );
        CompanionSpawnPlacement placement = new CompanionSpawnPlacement(
                "world", 10.5, 65.0, 13.5, 0.0f, 0.0f, 0.0f
        );

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .releaseOccupied(slot, placement)
                .toCompletableFuture()
                .join();

        assertEquals(DirectLiveCoopAuthor.Outcome.RELEASE_SUBMITTED, outcome);
        assertEquals(1, persistence.releaseCalls);
        CompanionCoopReleaseRequest release = persistence.lastRelease;
        assertEquals(occupancy.residency(), release.sourceResidency());
        assertEquals(
                occupancy.residency().snapshotId(),
                release.sourceSnapshot().snapshotId()
        );
        assertSame(placement, release.placement());
        assertTrue(release.spawnReceiptKey().startsWith("coop-spawn:"));
    }

    @Test
    void releaseRejectsOccupancyResidencyDisagreementWithoutSubmission() {
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        CompanionProfileReadModel profile = coopedProfile(occupancy);
        CoopResidency projectedResidency = occupancy.residency();
        CoopResidency disagreeingResidency = new CoopResidency(
                projectedResidency.slotKey(),
                projectedResidency.profileId(),
                projectedResidency.housedNpcAlias(),
                new SnapshotId(UUID.fromString(
                        "00000000-0000-0000-0000-000000000215"
                )),
                projectedResidency.capturedAtMs(),
                projectedResidency.updatedAtMs()
        );

        FakePersistence persistence = releasePersistence(
                occupancy, profile, disagreeingResidency
        );

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .releaseOccupied(slot, placement())
                .toCompletableFuture()
                .join();

        assertEquals(
                DirectLiveCoopAuthor.Outcome.RESIDENCY_UNAVAILABLE,
                outcome
        );
        assertEquals(0, persistence.releaseCalls);
        assertNull(persistence.lastRelease);
    }

    @Test
    void releaseRejectsOccupancyProfileDisagreementWithoutSubmission() {
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        CoopSlotKey otherSlot = slot(2);
        CoopOccupancy otherOccupancy = occupancyFor(
                otherSlot,
                occupancy.residency().profileId(),
                occupancy.residency().snapshotId()
        );
        CompanionProfileReadModel disagreeingProfile =
                coopedProfile(otherOccupancy);
        FakePersistence persistence = releasePersistence(
                occupancy, disagreeingProfile, occupancy.residency()
        );

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .releaseOccupied(slot, placement())
                .toCompletableFuture()
                .join();

        assertEquals(
                DirectLiveCoopAuthor.Outcome.RESIDENCY_UNAVAILABLE,
                outcome
        );
        assertEquals(0, persistence.releaseCalls);
        assertNull(persistence.lastRelease);
    }

    @Test
    void releaseRejectsResidencyProfileDisagreementWithoutSubmission() {
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        ProfileId otherProfile = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000216"
        ));
        CoopResidency disagreeingResidency = new CoopResidency(
                slot,
                otherProfile,
                occupancy.residency().housedNpcAlias(),
                occupancy.residency().snapshotId(),
                occupancy.residency().capturedAtMs(),
                occupancy.residency().updatedAtMs()
        );
        FakePersistence persistence = releasePersistence(
                occupancy, coopedProfile(occupancy), disagreeingResidency
        );

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .releaseOccupied(slot, placement())
                .toCompletableFuture()
                .join();

        assertEquals(
                DirectLiveCoopAuthor.Outcome.RESIDENCY_UNAVAILABLE,
                outcome
        );
        assertEquals(0, persistence.releaseCalls);
        assertNull(persistence.lastRelease);
    }

    @Test
    void releaseRejectsSnapshotDisagreementWithoutSubmission() {
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        CompanionProfileReadModel valid = coopedProfile(occupancy);
        SnapshotId otherSnapshotId = new SnapshotId(UUID.fromString(
                "00000000-0000-0000-0000-000000000217"
        ));
        CompanionProfileReadModel disagreeingProfile =
                new CompanionProfileReadModel(
                        valid.identity(),
                        valid.currentAlias(),
                        valid.lifecycle(),
                        valid.toolLinks(),
                        List.of(canonicalSnapshot(
                                otherSnapshotId,
                                valid.identity().profileId(),
                                slot,
                                valid.lifecycle().revision()
                        )),
                        valid.currentCoopSlot()
                );
        FakePersistence persistence = releasePersistence(
                occupancy, disagreeingProfile, occupancy.residency()
        );

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .releaseOccupied(slot, placement())
                .toCompletableFuture()
                .join();

        assertEquals(
                DirectLiveCoopAuthor.Outcome.SNAPSHOT_UNAVAILABLE,
                outcome
        );
        assertEquals(0, persistence.releaseCalls);
        assertNull(persistence.lastRelease);
    }

    @Test
    void rejectedSubmissionsReturnExplicitFailureOutcomes() {
        CoopSlotKey captureSlot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(captureSlot);

        FakePersistence registrationRejected = new FakePersistence();
        registrationRejected.registrationSubmission =
                SubmissionBehavior.REJECTED;
        assertEquals(
                DirectLiveCoopAuthor.Outcome.REGISTRATION_FAILED,
                author(registrationRejected).captureLive(captureSlot, source)
                        .toCompletableFuture()
                        .join()
        );

        FakePersistence adoptionRejected = new FakePersistence();
        adoptionRejected.slots.put(
                captureSlot, CoopSlot.unoccupied(captureSlot)
        );
        adoptionRejected.adoptionSubmission = SubmissionBehavior.REJECTED;
        assertEquals(
                DirectLiveCoopAuthor.Outcome.PROFILE_UNAVAILABLE,
                author(adoptionRejected).captureLive(captureSlot, source)
                        .toCompletableFuture()
                        .join()
        );

        FakePersistence captureRejected = capturePersistence(source);
        captureRejected.captureSubmission = SubmissionBehavior.REJECTED;
        assertEquals(
                DirectLiveCoopAuthor.Outcome.CAPTURE_FAILED,
                author(captureRejected).captureLive(captureSlot, source)
                        .toCompletableFuture()
                        .join()
        );

        CoopSlotKey releaseSlot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(releaseSlot);
        FakePersistence releaseRejected = releasePersistence(
                occupancy, coopedProfile(occupancy), occupancy.residency()
        );
        releaseRejected.releaseSubmission = SubmissionBehavior.REJECTED;
        assertEquals(
                DirectLiveCoopAuthor.Outcome.RELEASE_FAILED,
                author(releaseRejected)
                        .releaseOccupied(releaseSlot, placement())
                        .toCompletableFuture()
                        .join()
        );
    }

    @Test
    void exceptionalSubmissionsReturnExplicitFailureOutcomes() {
        CoopSlotKey captureSlot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(captureSlot);

        FakePersistence registrationExceptional = new FakePersistence();
        registrationExceptional.registrationSubmission =
                SubmissionBehavior.EXCEPTIONAL;

        FakePersistence adoptionExceptional = new FakePersistence();
        adoptionExceptional.slots.put(
                captureSlot, CoopSlot.unoccupied(captureSlot)
        );
        adoptionExceptional.adoptionSubmission =
                SubmissionBehavior.EXCEPTIONAL;

        FakePersistence captureExceptional = capturePersistence(source);
        captureExceptional.captureSubmission =
                SubmissionBehavior.EXCEPTIONAL;

        CoopSlotKey releaseSlot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(releaseSlot);
        FakePersistence releaseExceptional = releasePersistence(
                occupancy, coopedProfile(occupancy), occupancy.residency()
        );
        releaseExceptional.releaseSubmission =
                SubmissionBehavior.EXCEPTIONAL;
        assertAll(
                () -> assertEquals(
                        DirectLiveCoopAuthor.Outcome.REGISTRATION_FAILED,
                        author(registrationExceptional)
                                .captureLive(captureSlot, source)
                                .toCompletableFuture()
                                .join()
                ),
                () -> assertEquals(
                        DirectLiveCoopAuthor.Outcome.PROFILE_UNAVAILABLE,
                        author(adoptionExceptional)
                                .captureLive(captureSlot, source)
                                .toCompletableFuture()
                                .join()
                ),
                () -> assertEquals(
                        DirectLiveCoopAuthor.Outcome.CAPTURE_FAILED,
                        author(captureExceptional)
                                .captureLive(captureSlot, source)
                                .toCompletableFuture()
                                .join()
                ),
                () -> assertEquals(
                        DirectLiveCoopAuthor.Outcome.RELEASE_FAILED,
                        author(releaseExceptional)
                                .releaseOccupied(releaseSlot, placement())
                                .toCompletableFuture()
                                .join()
                )
        );
    }

    @Test
    void captureRetryPreservesIdempotencySnapshotAndRetirementReceipt() {
        CoopSlotKey slot = slot(0);
        DirectLiveCoopAuthor.LiveNpcSource source = liveSource(slot);
        FakePersistence persistence = capturePersistence(source);
        DirectLiveCoopAuthor author = author(persistence);

        DirectLiveCoopAuthor.Outcome first = author.captureLive(slot, source)
                .toCompletableFuture()
                .join();
        DirectLiveCoopAuthor.Outcome retried = author.captureLive(slot, source)
                .toCompletableFuture()
                .join();

        assertEquals(DirectLiveCoopAuthor.Outcome.CAPTURE_SUBMITTED, first);
        assertEquals(DirectLiveCoopAuthor.Outcome.CAPTURE_SUBMITTED, retried);
        assertEquals(2, persistence.captureKeys.size());
        assertEquals(persistence.captureKeys.get(0),
                persistence.captureKeys.get(1));
        CompanionCoopCaptureRequest firstRequest =
                persistence.captureRequests.get(0);
        CompanionCoopCaptureRequest retriedRequest =
                persistence.captureRequests.get(1);
        assertEquals(firstRequest.snapshot().snapshotId(),
                retriedRequest.snapshot().snapshotId());
        assertEquals(firstRequest.snapshot().payloadHash(),
                retriedRequest.snapshot().payloadHash());
        assertEquals(
                firstRequest.source().retirementReceiptKey(),
                retriedRequest.source().retirementReceiptKey()
        );
    }

    @Test
    void releaseRetryPreservesIdempotencySnapshotTargetAliasAndSpawnReceipt() {
        CoopSlotKey slot = slot(1);
        CoopOccupancy occupancy = coopedOccupancy(slot);
        FakePersistence persistence = releasePersistence(
                occupancy, coopedProfile(occupancy), occupancy.residency()
        );
        DirectLiveCoopAuthor author = author(persistence);
        CompanionSpawnPlacement placement = placement();

        DirectLiveCoopAuthor.Outcome first = author
                .releaseOccupied(slot, placement)
                .toCompletableFuture()
                .join();
        DirectLiveCoopAuthor.Outcome retried = author
                .releaseOccupied(slot, placement)
                .toCompletableFuture()
                .join();

        assertEquals(DirectLiveCoopAuthor.Outcome.RELEASE_SUBMITTED, first);
        assertEquals(DirectLiveCoopAuthor.Outcome.RELEASE_SUBMITTED, retried);
        assertEquals(2, persistence.releaseKeys.size());
        assertEquals(persistence.releaseKeys.get(0),
                persistence.releaseKeys.get(1));
        CompanionCoopReleaseRequest firstRequest =
                persistence.releaseRequests.get(0);
        CompanionCoopReleaseRequest retriedRequest =
                persistence.releaseRequests.get(1);
        assertEquals(firstRequest.sourceSnapshot().snapshotId(),
                retriedRequest.sourceSnapshot().snapshotId());
        assertEquals(firstRequest.targetAlias(), retriedRequest.targetAlias());
        assertEquals(
                firstRequest.spawnReceiptKey(),
                retriedRequest.spawnReceiptKey()
        );
    }

    @Test
    void loadedSlotsRegisterInCanonicalOrderWithoutOverwritingImportedOccupancy() {
        FakePersistence persistence = new FakePersistence();
        CoopSlotKey first = slot(0);
        CoopSlotKey second = slot(1);
        CoopSlotKey importedSlot = slot(2);
        CoopOccupancy imported = coopedOccupancy(importedSlot);
        persistence.occupancies.put(importedSlot, imported);

        List<DirectLiveCoopAuthor.Outcome> outcomes = author(persistence)
                .registerLoadedSlots(List.of(importedSlot, second, first))
                .toCompletableFuture()
                .join();

        assertEquals(
                List.of(
                        DirectLiveCoopAuthor.Outcome.REGISTERED,
                        DirectLiveCoopAuthor.Outcome.REGISTERED,
                        DirectLiveCoopAuthor.Outcome.OCCUPIED_PRESERVED
                ),
                outcomes
        );
        assertEquals(List.of(first, second), persistence.registrationOrder);
        assertSame(imported, persistence.occupancies.get(importedSlot));
    }

    @Test
    void capturedItemsHaveNoAuthoringEntryPoint() {
        boolean capturedArtifactParameter = false;
        boolean itemStackParameter = false;
        for (Method method : DirectLiveCoopAuthor.class.getMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                capturedArtifactParameter |= CapturedArtifact.class
                        .isAssignableFrom(parameter);
                itemStackParameter |= parameter.getName().equals(
                        "com.hypixel.hytale.server.core.inventory.ItemStack"
                );
            }
        }
        assertFalse(capturedArtifactParameter);
        assertFalse(itemStackParameter);
        assertTrue(java.util.Arrays.stream(
                DirectLiveCoopAuthor.class.getMethods()
        ).filter(method -> method.getName().equals("captureLive"))
                .allMatch(method -> java.util.Arrays.asList(
                        method.getParameterTypes()
                ).contains(DirectLiveCoopAuthor.LiveNpcSource.class)));
    }

    private DirectLiveCoopAuthor author(FakePersistence persistence) {
        return new DirectLiveCoopAuthor(
                persistence, OperationId::create
        );
    }

    private void assertInvalidSourceRejectedWithoutMutation(
            CoopSlotKey slot,
            DirectLiveCoopAuthor.LiveNpcSource source
    ) {
        FakePersistence persistence = new FakePersistence();

        assertThrows(
                IllegalArgumentException.class,
                () -> author(persistence).captureLive(slot, source)
        );

        assertEquals(0, persistence.registrationCalls);
        assertEquals(0, persistence.adoptionCalls);
        assertEquals(0, persistence.captureCalls);
        assertNull(persistence.lastCapture);
    }

    private void assertProfileDisagreementRejected(
            CoopSlotKey slot,
            DirectLiveCoopAuthor.LiveNpcSource source,
            CompanionProfileReadModel disagreeingProfile
    ) {
        FakePersistence persistence = new FakePersistence();
        persistence.slots.put(slot, CoopSlot.unoccupied(slot));
        persistence.profilesByAlias.put(source.alias(), disagreeingProfile);

        DirectLiveCoopAuthor.Outcome outcome = author(persistence)
                .captureLive(slot, source)
                .toCompletableFuture()
                .join();

        assertEquals(DirectLiveCoopAuthor.Outcome.PROFILE_UNAVAILABLE, outcome);
        assertEquals(0, persistence.registrationCalls);
        assertEquals(0, persistence.adoptionCalls);
        assertEquals(0, persistence.captureCalls);
        assertNull(persistence.lastCapture);
    }

    private FakePersistence capturePersistence(
            DirectLiveCoopAuthor.LiveNpcSource source
    ) {
        FakePersistence persistence = new FakePersistence();
        persistence.slots.put(
                source.observedSlot(),
                CoopSlot.unoccupied(source.observedSlot())
        );
        CompanionProfileReadModel profile = observedLiveProfile(source);
        persistence.profilesByAlias.put(source.alias(), profile);
        persistence.profilesById.put(source.profileId(), profile);
        return persistence;
    }

    private FakePersistence releasePersistence(
            CoopOccupancy occupancy,
            CompanionProfileReadModel profile,
            CoopResidency residency
    ) {
        FakePersistence persistence = new FakePersistence();
        persistence.occupancies.put(occupancy.slot().key(), occupancy);
        persistence.profilesById.put(
                occupancy.residency().profileId(), profile
        );
        persistence.residencies.put(
                occupancy.residency().profileId(), residency
        );
        return persistence;
    }

    private CompanionSpawnPlacement placement() {
        return new CompanionSpawnPlacement(
                "world", 10.5, 65.0, 13.5, 0.0f, 0.0f, 0.0f
        );
    }

    private DirectLiveCoopAuthor.LiveNpcSource liveSource(CoopSlotKey slot) {
        UUID npcId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        ProfileId profileId = new ProfileId(npcId);
        NpcAlias alias = new NpcAlias(npcId);
        String metadata = "{}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Hen",
                "hen_role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                slot.worldKey(),
                NOW,
                NOW,
                NOW,
                0
        );
        CompanionProfileMutation.AdoptLive adoption =
                new CompanionProfileMutation.AdoptLive(
                        identity,
                        alias,
                        null,
                        slot.worldKey(),
                        List.of(),
                        NOW
                );
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                TameworkSnapshotCodecs.create().encode(
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                        CoopResidentStateSnapshot.class,
                        fullState(npcId, slot)
                );
        return new DirectLiveCoopAuthor.LiveNpcSource(
                profileId,
                alias,
                slot.worldKey(),
                adoption,
                slot,
                encoded
        );
    }

    private CompanionProfileReadModel observedLiveProfile(
            DirectLiveCoopAuthor.LiveNpcSource source
    ) {
        return liveProfile(
                source.profileId(),
                source.alias(),
                source.worldKey(),
                LifecycleState.ACTIVE
        );
    }

    private CompanionProfileReadModel liveProfile(
            ProfileId profileId,
            NpcAlias alias,
            String worldKey,
            LifecycleState lifecycleState
    ) {
        String metadata = "{}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Hen",
                "hen_role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                worldKey,
                NOW,
                NOW,
                NOW,
                0
        );
        LifecycleLocation location = lifecycleState == LifecycleState.ACTIVE
                ? LifecycleLocation.liveEntity(alias.toString(), worldKey)
                : LifecycleLocation.none();
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                null,
                lifecycleState,
                location,
                LifecycleRevision.INITIAL,
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        return new CompanionProfileReadModel(
                identity,
                new CompanionAlias(
                        alias,
                        profileId,
                        0,
                        CompanionAlias.State.CURRENT,
                        null,
                        NOW,
                        null
                ),
                lifecycle,
                List.of(),
                List.of(),
                null
        );
    }

    private CoopOccupancy coopedOccupancy(CoopSlotKey slot) {
        ProfileId profileId = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000202"
        ));
        SnapshotId snapshotId = new SnapshotId(UUID.fromString(
                "00000000-0000-0000-0000-000000000203"
        ));
        return occupancyFor(slot, profileId, snapshotId);
    }

    private CoopOccupancy occupancyFor(
            CoopSlotKey slot,
            ProfileId profileId,
            SnapshotId snapshotId
    ) {
        CoopSlot structural = new CoopSlot(slot, 3, null, null);
        CoopResidency residency = new CoopResidency(
                slot,
                profileId,
                new NpcAlias(UUID.fromString(
                        "00000000-0000-0000-0000-000000000204"
                )),
                snapshotId,
                NOW,
                NOW + 1
        );
        return new CoopOccupancy(structural, residency);
    }

    private CompanionProfileReadModel coopedProfile(CoopOccupancy occupancy) {
        ProfileId profileId = occupancy.residency().profileId();
        String metadata = "{}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Hen",
                "hen_role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                occupancy.slot().key().worldKey(),
                NOW,
                NOW,
                NOW,
                0
        );
        LifecycleRevision revision = new LifecycleRevision(3);
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                null,
                LifecycleState.COOP,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.COOP_SLOT,
                        occupancy.slot().key().toString()
                ),
                revision,
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                null
        );
        CompanionSnapshot snapshot = canonicalSnapshot(
                occupancy.residency().snapshotId(),
                profileId,
                occupancy.slot().key(),
                revision
        );
        return new CompanionProfileReadModel(
                identity,
                null,
                lifecycle,
                List.of(),
                List.of(snapshot),
                occupancy.slot()
        );
    }

    private CompanionSnapshot canonicalSnapshot(
            SnapshotId snapshotId,
            ProfileId profileId,
            CoopSlotKey slot,
            LifecycleRevision revision
    ) {
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                TameworkSnapshotCodecs.create().encode(
                        CompanionCoopCaptureRequest.SNAPSHOT_KIND,
                        CompanionCoopCaptureRequest.SNAPSHOT_VERSION,
                        CoopResidentStateSnapshot.class,
                        fullState(profileId.value(), slot)
                );
        return new CompanionSnapshot(
                snapshotId,
                profileId,
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                revision,
                true,
                NOW
        );
    }

    private CoopResidentStateSnapshot fullState(
            UUID npcId,
            CoopSlotKey slot
    ) {
        return new CoopResidentStateSnapshot(
                npcId,
                slot.coopId(),
                slot.residentSlot(),
                "hen_role",
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
                1.0,
                NOW
        );
    }

    private CoopSlotKey slot(int index) {
        return new CoopSlotKey("world", "hen_coop", 10, 64, 10, index);
    }

    private enum SubmissionBehavior {
        PUBLISHED,
        REJECTED,
        EXCEPTIONAL
    }

    private static final class FakePersistence
            implements DirectLiveCoopPersistencePort {
        private final Map<CoopSlotKey, CoopSlot> slots = new HashMap<>();
        private final Map<CoopSlotKey, CoopOccupancy> occupancies =
                new HashMap<>();
        private final Map<NpcAlias, CompanionProfileReadModel> profilesByAlias =
                new HashMap<>();
        private final Map<ProfileId, CompanionProfileReadModel> profilesById =
                new HashMap<>();
        private final Map<ProfileId, CoopResidency> residencies =
                new HashMap<>();
        private int registrationCalls;
        private int adoptionCalls;
        private int captureCalls;
        private int releaseCalls;
        private final List<CoopSlotKey> registrationOrder =
                new java.util.ArrayList<>();
        private final List<IdempotencyKey> captureKeys =
                new java.util.ArrayList<>();
        private final List<CompanionCoopCaptureRequest> captureRequests =
                new java.util.ArrayList<>();
        private final List<IdempotencyKey> releaseKeys =
                new java.util.ArrayList<>();
        private final List<CompanionCoopReleaseRequest> releaseRequests =
                new java.util.ArrayList<>();
        private SubmissionBehavior registrationSubmission =
                SubmissionBehavior.PUBLISHED;
        private SubmissionBehavior adoptionSubmission =
                SubmissionBehavior.PUBLISHED;
        private SubmissionBehavior captureSubmission =
                SubmissionBehavior.PUBLISHED;
        private SubmissionBehavior releaseSubmission =
                SubmissionBehavior.PUBLISHED;
        private CompanionCoopCaptureRequest lastCapture;
        private CompanionCoopReleaseRequest lastRelease;

        @Override
        public Map<CoopSlotKey, CoopOccupancy> projectedCoopSnapshot() {
            return Map.copyOf(occupancies);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CoopSlot>> findCoopSlot(
                CoopSlotKey slot
        ) {
            CoopSlot found = slots.get(slot);
            return completed(found == null
                    ? PersistenceReadResult.absent()
                    : PersistenceReadResult.found(
                            found, found.residencyRevision()
                    ));
        }

        @Override
        public CompletionStage<PersistenceReadResult<CoopResidency>>
        findCoopResidency(ProfileId profileId) {
            CoopResidency found = residencies.get(profileId);
            return completed(found == null
                    ? PersistenceReadResult.absent()
                    : PersistenceReadResult.found(found, 0));
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(NpcAlias alias) {
            CompanionProfileReadModel found = profilesByAlias.get(alias);
            return completed(found == null
                    ? PersistenceReadResult.absent()
                    : PersistenceReadResult.found(found, 0));
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            CompanionProfileReadModel found = profilesById.get(profileId);
            return completed(found == null
                    ? PersistenceReadResult.absent()
                    : PersistenceReadResult.found(found, 0));
        }

        @Override
        public PublicOperationSubmission registerCoopSlot(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CoopSlotRegistration registration
        ) {
            registrationCalls++;
            registrationOrder.add(registration.slot().key());
            if (registrationSubmission == SubmissionBehavior.PUBLISHED) {
                slots.putIfAbsent(
                        registration.slot().key(), registration.slot()
                );
            }
            return submission(operationId, registrationSubmission);
        }

        @Override
        public PublicOperationSubmission mutateProfile(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation mutation
        ) {
            adoptionCalls++;
            CompanionProfileMutation.AdoptLive adoption =
                    (CompanionProfileMutation.AdoptLive) mutation;
            if (adoptionSubmission == SubmissionBehavior.PUBLISHED) {
                CompanionProfileReadModel profile =
                        new CompanionProfileReadModel(
                                adoption.identity(),
                                new CompanionAlias(
                                        adoption.alias(),
                                        adoption.profileId(),
                                        0,
                                        CompanionAlias.State.CURRENT,
                                        null,
                                        adoption.requestedAtMs(),
                                        null
                                ),
                                adoption.initialLifecycle(),
                                adoption.toolLinks(),
                                List.of(),
                                null
                        );
                profilesByAlias.put(adoption.alias(), profile);
                profilesById.put(adoption.profileId(), profile);
            }
            return submission(operationId, adoptionSubmission);
        }

        @Override
        public PublicOperationSubmission captureToCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCoopCaptureRequest capture
        ) {
            captureCalls++;
            lastCapture = capture;
            captureKeys.add(idempotencyKey);
            captureRequests.add(capture);
            return submission(operationId, captureSubmission);
        }

        @Override
        public PublicOperationSubmission releaseFromCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCoopReleaseRequest release
        ) {
            releaseCalls++;
            lastRelease = release;
            releaseKeys.add(idempotencyKey);
            releaseRequests.add(release);
            return submission(operationId, releaseSubmission);
        }

        private <T> CompletionStage<PersistenceReadResult<T>> completed(
                PersistenceReadResult<T> result
        ) {
            return CompletableFuture.completedFuture(result);
        }

        private PublicOperationSubmission submission(
                OperationId operationId,
                SubmissionBehavior behavior
        ) {
            if (behavior == SubmissionBehavior.REJECTED) {
                return new PublicOperationSubmission(
                        PublicOperationSubmission.Admission.REJECTED,
                        CompletableFuture.completedFuture(
                                new OperationWorkflowResult(
                                        OperationWorkflowResult.Status
                                                .PREPARE_FAILED,
                                        null,
                                        List.of(),
                                        new IllegalStateException(
                                                "test rejection"
                                        )
                                )
                        )
                );
            }
            if (behavior == SubmissionBehavior.EXCEPTIONAL) {
                CompletableFuture<OperationWorkflowResult> completion =
                        new CompletableFuture<>();
                completion.completeExceptionally(
                        new IllegalStateException("test completion failure")
                );
                return new PublicOperationSubmission(
                        PublicOperationSubmission.Admission.ACCEPTED,
                        completion
                );
            }
            OperationEnvelope envelope = new OperationEnvelope(
                    operationId,
                    new IdempotencyKey("test"),
                    new OperationKind("test_operation"),
                    1,
                    "{}",
                    OperationPhase.PUBLISHED,
                    "test",
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    NOW,
                    NOW,
                    NOW,
                    NOW,
                    NOW,
                    List.of(OperationScope.operation(operationId))
            );
            return new PublicOperationSubmission(
                    PublicOperationSubmission.Admission.ACCEPTED,
                    CompletableFuture.completedFuture(
                            new OperationWorkflowResult(
                                    OperationWorkflowResult.Status.PUBLISHED,
                                    envelope,
                                    List.of(),
                                    null
                            )
                    )
            );
        }
    }
}
