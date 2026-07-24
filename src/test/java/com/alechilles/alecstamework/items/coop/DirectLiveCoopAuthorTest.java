package com.alechilles.alecstamework.items.coop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        CompanionProfileMutation.AdoptLive adoption = source.adoption();
        return new CompanionProfileReadModel(
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
    }

    private CoopOccupancy coopedOccupancy(CoopSlotKey slot) {
        ProfileId profileId = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000202"
        ));
        SnapshotId snapshotId = new SnapshotId(UUID.fromString(
                "00000000-0000-0000-0000-000000000203"
        ));
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
            slots.putIfAbsent(registration.slot().key(), registration.slot());
            return published(operationId);
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
            CompanionProfileReadModel profile = new CompanionProfileReadModel(
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
            return published(operationId);
        }

        @Override
        public PublicOperationSubmission captureToCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCoopCaptureRequest capture
        ) {
            captureCalls++;
            lastCapture = capture;
            return published(operationId);
        }

        @Override
        public PublicOperationSubmission releaseFromCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCoopReleaseRequest release
        ) {
            releaseCalls++;
            lastRelease = release;
            return published(operationId);
        }

        private <T> CompletionStage<PersistenceReadResult<T>> completed(
                PersistenceReadResult<T> result
        ) {
            return CompletableFuture.completedFuture(result);
        }

        private PublicOperationSubmission published(OperationId operationId) {
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
