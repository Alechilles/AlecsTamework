package com.alechilles.alecstamework.items.coop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureRequest;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemInventoryPosition;
import com.alechilles.alecstamework.companion.coop.CoopCapturedItemSourceEvidence;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistration;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/** Regression coverage for canonical captured-item intake authoring. */
class CapturedItemCoopAuthorTest {
    private static final long NOW = -62135596800000L;
    private static final UUID ACTOR = UUID.fromString(
            "00000000-0000-0000-0000-000000000801"
    );
    private static final UUID NPC = UUID.fromString(
            "00000000-0000-0000-0000-000000000802"
    );
    private static final UUID PROFILE = UUID.fromString(
            "00000000-0000-0000-0000-000000000803"
    );
    private static final UUID CAPTURE_SNAPSHOT = UUID.fromString(
            "00000000-0000-0000-0000-000000000804"
    );

    @Test
    void exactCurrentArtifactUsesFirstProjectedFreeSlotAndSectionLocalPosition() {
        FakePersistence persistence = new FakePersistence();
        CompanionProfileReadModel profile = capturedProfile(
                true, new OwnerId(ACTOR)
        );
        persistence.profiles.put(
                profile.identity().profileId(), profile
        );
        CapturedItemCoopTarget target = target();
        persistence.occupancies.put(
                target.slots().get(0),
                occupancy(target.slots().get(0))
        );
        CapturedItemCoopAuthor.Source source = source(
                CoopCapturedItemInventoryPosition.Section.STORAGE, 3
        );

        CapturedItemCoopAuthor.Outcome outcome =
                new CapturedItemCoopAuthor(persistence)
                        .capture(source, target)
                        .toCompletableFuture()
                        .join();

        assertEquals(
                CapturedItemCoopAuthor.Outcome.CAPTURE_SUBMITTED,
                outcome
        );
        assertEquals(1, persistence.registrationCalls);
        assertEquals(1, persistence.captureCalls);
        CompanionCoopCaptureRequest request = persistence.lastCapture;
        assertNotNull(request);
        assertEquals(target.slots().get(1), request.targetSlot());
        assertTrue(request.source()
                instanceof CoopCapturedItemSourceEvidence);
        CoopCapturedItemSourceEvidence evidence =
                (CoopCapturedItemSourceEvidence) request.source();
        assertEquals(
                new CoopCapturedItemInventoryPosition(
                        CoopCapturedItemInventoryPosition.Section.STORAGE,
                        3
                ),
                evidence.inventoryPosition()
        );
        assertEquals(source.sourceArtifact(), evidence.sourceArtifact());
        assertNotEquals(
                evidence.sourceArtifact(),
                evidence.receiptArtifact()
        );
        assertTrue(CapturedItemCoopArtifactClaim.receiptMarked(
                evidence.receiptArtifact()
        ));
        CoopResidentStateSnapshot housed =
                decoded(request.snapshot());
        assertEquals(target.coopId(), housed.coopId());
        assertEquals(1, housed.residentSlot());
        CoopResidentStateSnapshot portable =
                decoded(evidence.captureSnapshot());
        assertNull(portable.coopId());
        assertEquals(-1, portable.residentSlot());
    }

    @Test
    void retryReusesOperationIdempotencySnapshotAndRetirementReceipt() {
        FakePersistence persistence = new FakePersistence();
        CompanionProfileReadModel profile = capturedProfile(
                true, new OwnerId(ACTOR)
        );
        persistence.profiles.put(
                profile.identity().profileId(), profile
        );
        CapturedItemCoopAuthor author =
                new CapturedItemCoopAuthor(persistence);
        CapturedItemCoopAuthor.Source source = source(
                CoopCapturedItemInventoryPosition.Section.BACKPACK, 5
        );

        assertEquals(
                CapturedItemCoopAuthor.Outcome.CAPTURE_SUBMITTED,
                author.capture(source, target())
                        .toCompletableFuture().join()
        );
        assertEquals(
                CapturedItemCoopAuthor.Outcome.CAPTURE_SUBMITTED,
                author.capture(source, target())
                        .toCompletableFuture().join()
        );

        assertEquals(2, persistence.captureRequests.size());
        assertEquals(
                persistence.captureOperationIds.get(0),
                persistence.captureOperationIds.get(1)
        );
        assertEquals(
                persistence.captureKeys.get(0),
                persistence.captureKeys.get(1)
        );
        CompanionCoopCaptureRequest first =
                persistence.captureRequests.get(0);
        CompanionCoopCaptureRequest retry =
                persistence.captureRequests.get(1);
        assertEquals(
                first.snapshot().snapshotId(),
                retry.snapshot().snapshotId()
        );
        assertEquals(
                first.source().retirementReceiptKey(),
                retry.source().retirementReceiptKey()
        );
    }

    @Test
    void admissionUsesCanonicalRoleTameAndOwnerPolicy() {
        assertPolicyConflict(
                capturedProfile(false, new OwnerId(ACTOR)),
                target()
        );
        assertPolicyConflict(
                capturedProfile(true, new OwnerId(UUID.fromString(
                        "00000000-0000-0000-0000-000000000899"
                ))),
                target()
        );
        assertPolicyConflict(
                capturedProfile(true, new OwnerId(ACTOR)),
                new CapturedItemCoopTarget(
                        "world", "hen_coop", 10, 64, 10, 2,
                        Set.of("cow_role"), true, true, true
                )
        );
    }

    @Test
    void aliasOnlyMalformedAndReceiptMarkedArtifactsNeverAuthor() {
        CapturedArtifact aliasOnly = artifact(false);
        CapturedArtifact malformedCurrent = CapturedArtifact.create(
                "capture_crate",
                1,
                0,
                0,
                new BsonDocument()
                        .append(
                                TameworkMetadataKeys.TARGET_UUID,
                                new BsonString(NPC.toString())
                        )
                        .append(
                                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                                new BsonString(PROFILE.toString())
                        )
                        .toJson()
        );
        BsonDocument markedMetadata = BsonDocument.parse(
                artifact(true).metadataExtendedJson()
        );
        markedMetadata.put(
                CoopCapturedItemSourceEvidence.RECEIPT_METADATA_KEY,
                new BsonString("receipt")
        );
        CapturedArtifact marked = CapturedArtifact.create(
                "capture_crate", 1, 0, 0, markedMetadata.toJson()
        );

        assertNull(CapturedItemCoopArtifactClaim.parse(aliasOnly));
        assertNull(CapturedItemCoopArtifactClaim.parse(
                malformedCurrent
        ));
        assertNull(CapturedItemCoopArtifactClaim.parse(marked));
        assertFalse(CapturedItemCoopArtifactClaim.receiptMarked(
                aliasOnly
        ));
        assertTrue(CapturedItemCoopArtifactClaim.receiptMarked(marked));

        FakePersistence persistence = new FakePersistence();
        assertEquals(
                CapturedItemCoopAuthor.Outcome.UNMANAGED_ARTIFACT,
                new CapturedItemCoopAuthor(persistence)
                        .capture(
                                new CapturedItemCoopAuthor.Source(
                                        ACTOR,
                                        "world",
                                        new CoopCapturedItemInventoryPosition(
                                                CoopCapturedItemInventoryPosition
                                                        .Section.HOTBAR,
                                                0
                                        ),
                                        marked
                                ),
                                target()
                        )
                        .toCompletableFuture()
                        .join()
        );
        assertEquals(0, persistence.registrationCalls);
        assertEquals(0, persistence.captureCalls);
    }

    private void assertPolicyConflict(
            CompanionProfileReadModel profile,
            CapturedItemCoopTarget target
    ) {
        FakePersistence persistence = new FakePersistence();
        persistence.profiles.put(
                profile.identity().profileId(), profile
        );

        CapturedItemCoopAuthor.Outcome outcome =
                new CapturedItemCoopAuthor(persistence)
                        .capture(
                                source(
                                        CoopCapturedItemInventoryPosition
                                                .Section.HOTBAR,
                                        0
                                ),
                                target
                        )
                        .toCompletableFuture()
                        .join();

        assertEquals(
                CapturedItemCoopAuthor.Outcome.PROFILE_CONFLICT,
                outcome
        );
        assertEquals(0, persistence.captureCalls);
    }

    private CapturedItemCoopTarget target() {
        return new CapturedItemCoopTarget(
                "world",
                "hen_coop",
                10,
                64,
                10,
                2,
                Set.of("hen_role"),
                true,
                true,
                true
        );
    }

    private CapturedItemCoopAuthor.Source source(
            CoopCapturedItemInventoryPosition.Section section,
            int slot
    ) {
        return new CapturedItemCoopAuthor.Source(
                ACTOR,
                "world",
                new CoopCapturedItemInventoryPosition(section, slot),
                artifact(true)
        );
    }

    private CapturedArtifact artifact(boolean current) {
        BsonDocument metadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(NPC.toString())
                );
        if (current) {
            metadata.append(
                    TameworkMetadataKeys.COMPANION_PROFILE_ID,
                    new BsonString(PROFILE.toString())
            ).append(
                    TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                    new BsonString(CAPTURE_SNAPSHOT.toString())
            );
        }
        return CapturedArtifact.create(
                "capture_crate", 1, 0, 0, metadata.toJson()
        );
    }

    private CompanionProfileReadModel capturedProfile(
            boolean tamed,
            OwnerId owner
    ) {
        ProfileId profileId = new ProfileId(PROFILE);
        NpcAlias alias = new NpcAlias(NPC);
        SnapshotId snapshotId = new SnapshotId(CAPTURE_SNAPSHOT);
        LifecycleRevision revision = new LifecycleRevision(4);
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                TameworkSnapshotCodecs.create().encode(
                        CompanionCaptureRequest.SNAPSHOT_KIND,
                        CompanionCaptureRequest.SNAPSHOT_VERSION,
                        CoopResidentStateSnapshot.class,
                        portableState(tamed)
                );
        CompanionSnapshot snapshot = new CompanionSnapshot(
                snapshotId,
                profileId,
                encoded.kind(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                new LifecycleRevision(3),
                true,
                NOW
        );
        String metadata = "{}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Hen",
                "hen_role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                NOW,
                NOW,
                NOW,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                owner,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        snapshotId.toString()
                ),
                revision,
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                owner == null ? null : "world"
        );
        return new CompanionProfileReadModel(
                identity,
                new CompanionAlias(
                        alias,
                        profileId,
                        1,
                        CompanionAlias.State.CURRENT,
                        null,
                        NOW,
                        null
                ),
                lifecycle,
                List.of(),
                List.of(snapshot),
                null
        );
    }

    private CoopResidentStateSnapshot portableState(boolean tamed) {
        return new CoopResidentStateSnapshot(
                NPC,
                null,
                -1,
                "hen_role",
                null,
                null,
                new TameworkTamedComponent(tamed),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1.0D,
                NOW
        );
    }

    private CoopResidentStateSnapshot decoded(
            CompanionSnapshot snapshot
    ) {
        CoopResidentStateSnapshotCodec.DecodeResult result =
                new CoopResidentStateSnapshotCodec().decode(
                        snapshot.payloadJson()
                );
        assertEquals(
                CoopResidentStateSnapshotCodec.Status.FOUND,
                result.status()
        );
        return result.snapshotOrNull();
    }

    private CoopOccupancy occupancy(CoopSlotKey slot) {
        ProfileId profileId = new ProfileId(UUID.fromString(
                "00000000-0000-0000-0000-000000000888"
        ));
        CoopSlot structural = CoopSlot.unoccupied(slot);
        CoopResidency residency = new CoopResidency(
                slot,
                profileId,
                new NpcAlias(UUID.fromString(
                        "00000000-0000-0000-0000-000000000887"
                )),
                new SnapshotId(UUID.fromString(
                        "00000000-0000-0000-0000-000000000886"
                )),
                NOW,
                NOW
        );
        return new CoopOccupancy(structural, residency);
    }

    private static final class FakePersistence
            implements DirectLiveCoopPersistencePort {
        private final Map<CoopSlotKey, CoopSlot> slots =
                new HashMap<>();
        private final Map<CoopSlotKey, CoopOccupancy> occupancies =
                new HashMap<>();
        private final Map<ProfileId, CompanionProfileReadModel> profiles =
                new HashMap<>();
        private int registrationCalls;
        private int captureCalls;
        private CompanionCoopCaptureRequest lastCapture;
        private final List<OperationId> captureOperationIds =
                new ArrayList<>();
        private final List<IdempotencyKey> captureKeys =
                new ArrayList<>();
        private final List<CompanionCoopCaptureRequest> captureRequests =
                new ArrayList<>();

        @Override
        public Map<CoopSlotKey, CoopOccupancy> projectedCoopSnapshot() {
            return Map.copyOf(occupancies);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CoopSlot>>
        findCoopSlot(CoopSlotKey slot) {
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
            return completed(PersistenceReadResult.absent());
        }

        @Override
        public CompletionStage<
                PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(NpcAlias alias) {
            return completed(PersistenceReadResult.absent());
        }

        @Override
        public CompletionStage<
                PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            CompanionProfileReadModel found = profiles.get(profileId);
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
            slots.putIfAbsent(
                    registration.slot().key(), registration.slot()
            );
            return published(operationId);
        }

        @Override
        public PublicOperationSubmission mutateProfile(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionProfileMutation mutation
        ) {
            throw new AssertionError(
                    "Captured-item intake must not adopt a profile"
            );
        }

        @Override
        public PublicOperationSubmission captureToCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCoopCaptureRequest capture
        ) {
            captureCalls++;
            lastCapture = capture;
            captureOperationIds.add(operationId);
            captureKeys.add(idempotencyKey);
            captureRequests.add(capture);
            return published(operationId);
        }

        @Override
        public PublicOperationSubmission releaseFromCoop(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                com.alechilles.alecstamework.companion.coop
                        .CompanionCoopReleaseRequest release
        ) {
            throw new AssertionError(
                    "Captured-item intake must not release a resident"
            );
        }

        private <T> CompletionStage<PersistenceReadResult<T>> completed(
                PersistenceReadResult<T> result
        ) {
            return CompletableFuture.completedFuture(result);
        }

        private PublicOperationSubmission published(
                OperationId operationId
        ) {
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
