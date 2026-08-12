package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseRequest;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService
        .CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
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
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bson.BsonDocument;
import org.bson.BsonBoolean;
import org.bson.BsonDouble;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact owner and full-state projection tests for captured-artifact release authoring. */
class SpawnerCapturedArtifactReleaseAuthorTest {
    private static final ProfileId PROFILE = ProfileId.parse(
            "82000000-0000-0000-0000-000000000001"
    );
    private static final NpcAlias SOURCE = NpcAlias.parse(
            "82000000-0000-0000-0000-000000000002"
    );
    private static final SnapshotId SNAPSHOT = SnapshotId.parse(
            "82000000-0000-0000-0000-000000000003"
    );
    private static final NpcAlias NEWER_SOURCE = NpcAlias.parse(
            "82000000-0000-0000-0000-000000000009"
    );
    private static final SnapshotId NEWER_SNAPSHOT = SnapshotId.parse(
            "82000000-0000-0000-0000-000000000010"
    );
    private static final OwnerId CANONICAL_OWNER = OwnerId.parse(
            "82000000-0000-0000-0000-000000000004"
    );
    private static final OwnerId ASSIGNED_OWNER = OwnerId.parse(
            "82000000-0000-0000-0000-000000000005"
    );
    private static final OwnerId STALE_OWNER = OwnerId.parse(
            "82000000-0000-0000-0000-000000000006"
    );
    private static final UUID ACTOR = UUID.fromString(
            "82000000-0000-0000-0000-000000000007"
    );
    private static final UUID TOOL = UUID.fromString(
            "82000000-0000-0000-0000-000000000008"
    );
    private final SpawnerCaptureSnapshotMapper snapshots =
            new SpawnerCaptureSnapshotMapper();

    @Test
    void newAssignmentReplacesStaleOwnerAndCommandLinkProjection() {
        FakePersistence persistence = new FakePersistence(profile(
                null,
                "{\"owner_name\":null}"
        ));
        SpawnerPersistenceAuthorResult result = author(persistence).release(
                intent(ASSIGNED_OWNER, "Assigned owner"),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(
                ASSIGNED_OWNER,
                persistence.request.ownerAssignment()
        );
        CoopResidentStateSnapshot projected = projection(
                persistence.request
        );
        assertEquals(
                ASSIGNED_OWNER.value(),
                projected.owner().getOwnerId()
        );
        assertEquals("Assigned owner", projected.owner().getOwnerName());
        assertEquals(
                ASSIGNED_OWNER.value(),
                projected.commandLinks().getOwnerId()
        );
        assertArrayEquals(
                new String[]{"tool-a"},
                projected.commandLinks().getToolIds()
        );
    }

    /**
     * Protects the 2026-08-12 Soul Collector report where stored time was
     * charged as needs damage after the companion was placed.
     */
    @Test
    void captureReleasePausesNeedsTimersAndClearsPendingDamage() {
        FakePersistence persistence = new FakePersistence(profile(
                null,
                "{\"owner_name\":null}"
        ));

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                intent(ASSIGNED_OWNER, "Assigned owner"),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        TameworkNeedsComponent needs = projection(
                persistence.request
        ).needs();
        assertEquals("needs", needs.getConfigId());
        assertEquals(35.0D, needs.getHunger());
        assertEquals(45.0D, needs.getThirst());
        assertEquals(12.0D, needs.getAppliedHappinessPenalty());
        assertEquals(0.0D, needs.getPendingNeedsDamage());
        assertEquals(0L, needs.getLastUpdateMs());
        assertEquals(0L, needs.getLastPassiveSweepMs());
        assertEquals(-1.0D, needs.getRegenSuppressionBaselineHealth());
        assertEquals(0.0D, needs.getRegenSuppressionAllowedHeal());
        assertEquals(-1.0D, needs.getLastManagedHealth());
    }

    @Test
    void nullAssignmentPreservesCanonicalOwnerAndRepairsRawSnapshot() {
        FakePersistence persistence = new FakePersistence(profile(
                CANONICAL_OWNER,
                "{\"owner_name\":\"Canonical owner\"}"
        ));
        SpawnerPersistenceAuthorResult result = author(persistence).release(
                intent(null, null),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertNull(persistence.request.ownerAssignment());
        CoopResidentStateSnapshot projected = projection(
                persistence.request
        );
        assertEquals(
                CANONICAL_OWNER.value(),
                projected.owner().getOwnerId()
        );
        assertEquals("Canonical owner", projected.owner().getOwnerName());
        assertEquals(
                CANONICAL_OWNER.value(),
                projected.commandLinks().getOwnerId()
        );
    }

    @Test
    void assignmentCannotOverwriteAnOwnedCapturedProfile() {
        FakePersistence persistence = new FakePersistence(profile(
                CANONICAL_OWNER,
                "{\"owner_name\":\"Canonical owner\"}"
        ));

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                intent(ASSIGNED_OWNER, "Assigned owner"),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.PROFILE_CONFLICT,
                result.status()
        );
        assertNull(persistence.request);
    }

    /**
     * Protects the public-save case logged on 2026-07-24: capture-clears-owner
     * items omit OwnerUuid even though their imported profile remains owned.
     */
    @Test
    void redundantSameOwnerAssignmentPreservesImportedCanonicalOwner() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile()
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                releasedPublicIntent(
                        CANONICAL_OWNER,
                        "Current player",
                        false
                ),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertNull(persistence.request.ownerAssignment());
        CoopResidentStateSnapshot projected = projection(
                persistence.request
        );
        assertEquals(
                CANONICAL_OWNER.value(),
                projected.owner().getOwnerId()
        );
        assertEquals("Canonical owner", projected.owner().getOwnerName());
    }

    @Test
    void releasedPublicItemResolvesByAliasAndMapsItsSplitState() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile()
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                releasedPublicIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(1, persistence.aliasReads);
        assertEquals(0, persistence.profileReads);
        CoopResidentStateSnapshot projected = projection(
                persistence.request
        );
        assertEquals(SOURCE.value(), projected.npcUuid());
        assertEquals("tamework_test", projected.roleId());
        assertTrue(projected.tamed().isTamed());
        assertEquals("Legacy name", projected.npcName().getName());
        assertEquals(0.5D, projected.healthPercent());
        assertEquals(-777L, projected.happiness().getLastUpdateMs());
        assertArrayEquals(
                new String[]{TOOL.toString()},
                projected.commandLinks().getToolIds()
        );
        BsonDocument frozenSource = BsonDocument.parse(
                persistence.request.source().sourceArtifact()
                        .metadataExtendedJson()
        );
        assertFalse(frozenSource.containsKey(
                TameworkMetadataKeys.COMPANION_PROFILE_ID
        ));
        assertFalse(frozenSource.containsKey(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
        ));
    }

    /**
     * Protects the 2026-08-08 fresh v2.16.1 migration where both public and
     * replacement databases contained zero profiles after capture.
     */
    @Test
    void releasedPublicItemWithoutImportedProfileMaterializesAndReleases() {
        FakePersistence persistence = new FakePersistence(null);

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                orphanedReleasedPublicIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(new ProfileId(SOURCE.value()),
                persistence.request.profileId());
        assertEquals(SOURCE, persistence.request.sourceAlias());
        assertEquals(SnapshotId.parse(SOURCE.toString()),
                persistence.request.sourceSnapshot().snapshotId());
        assertEquals(ASSIGNED_OWNER, persistence.request.ownerAssignment());
        assertEquals("tamework_test", projection(persistence.request).roleId());
        assertTrue(projection(persistence.request).tamed().isTamed());
    }

    /**
     * Protects v2.16.1 captured items whose public profile omitted the optional tamed field.
     * The filled item remains the only retained evidence that the companion was tamed.
     */
    @Test
    void releasedPublicItemRestoresTamingWhenProfileOmitsTamedState() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile("{\"owner_name\":\"Canonical owner\"}")
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                releasedPublicIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertTrue(projection(persistence.request).tamed().isTamed());
    }

    @Test
    void alreadyMigratedUnloadedItemUsesCaptureHistoryRecovery() {
        CompanionProfileReadModel migrated = alreadyMigratedProfile();
        CompanionSnapshot historical = new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                LegacyCaptureV1Payload.VERSION,
                releasedPublicProfile().currentSnapshots().getFirst()
                        .payloadJson(),
                releasedPublicProfile().currentSnapshots().getFirst()
                        .payloadHash(),
                LifecycleRevision.INITIAL,
                false,
                -900L
        );
        FakePersistence persistence = new FakePersistence(
                migrated,
                List.of(historical)
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                releasedPublicIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(1, persistence.historyReads);
        assertEquals(historical, persistence.request.legacyRecovery()
                .historicalSnapshot());
        assertEquals(SOURCE.value(), projection(persistence.request).npcUuid());
    }

    @Test
    void newerSameProfileItemSupersedesOlderCanonicalCapture() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile()
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                modernSupersededIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertTrue(result.published());
        assertEquals(NEWER_SOURCE, persistence.request.sourceAlias());
        assertEquals(NEWER_SNAPSHOT, persistence.request.sourceSnapshot()
                .snapshotId());
        assertEquals(SNAPSHOT, persistence.request.modernRecovery()
                .supersededSnapshot().snapshotId());
    }

    @Test
    void releasedPublicItemRejectsTamingWhenProfileExplicitlyRecordsFalse() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile(
                        "{\"owner_name\":\"Canonical owner\",\"tamed\":false}"
                )
        );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                releasedPublicIntent(),
                ignored -> placement()
        ).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.SNAPSHOT_DECODE_FAILED,
                result.status()
        );
        assertNull(persistence.request);
    }

    @Test
    void mixedLegacyAndCurrentIdentityMetadataFailsBeforeRead() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile()
        );
        BsonDocument metadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        new BsonString(PROFILE.toString())
                );
        SpawnerCapturedArtifactReleaseIntent malformed =
                new SpawnerCapturedArtifactReleaseIntent(
                        "release-click-mixed",
                        ACTOR,
                        "world",
                        2,
                        stack("capture-device-filled", metadata),
                        stack(
                                "capture-device-empty",
                                new BsonDocument()
                        ),
                        null,
                        null,
                        null
                );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                malformed,
                ignored -> placement()
        ).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.INVALID_CONTEXT,
                result.status()
        );
        assertEquals(0, persistence.aliasReads);
        assertEquals(0, persistence.profileReads);
        assertNull(persistence.request);
    }

    @Test
    void incompleteReleasedPublicProgressionGroupFailsClosed() {
        FakePersistence persistence = new FakePersistence(
                releasedPublicProfile()
        );
        BsonDocument metadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.HAPPINESS_CONFIG_ID,
                        new BsonString("happiness")
                );
        SpawnerCapturedArtifactReleaseIntent malformed =
                new SpawnerCapturedArtifactReleaseIntent(
                        "release-click-partial-state",
                        ACTOR,
                        "world",
                        2,
                        stack("capture-device-filled", metadata),
                        stack(
                                "capture-device-empty",
                                new BsonDocument()
                        ),
                        null,
                        null,
                        null
                );

        SpawnerPersistenceAuthorResult result = author(persistence).release(
                malformed,
                ignored -> placement()
        ).toCompletableFuture().join();

        assertEquals(
                SpawnerPersistenceAuthorResult.Status.SNAPSHOT_DECODE_FAILED,
                result.status()
        );
        assertEquals(1, persistence.aliasReads);
        assertNull(persistence.request);
    }

    private SpawnerCapturedArtifactReleaseAuthor author(
            FakePersistence persistence
    ) {
        return new SpawnerCapturedArtifactReleaseAuthor(
                persistence,
                new SpawnerCaptureReleaseEvidenceFreezer(
                        new HytaleCapturedArtifactAdapter(
                                HytaleItemStackTestFixture::stack
                        ),
                        () -> -500L
                ),
                snapshots,
                (world, actor, effect, result) -> {
                }
        );
    }

    private SpawnerCapturedArtifactReleaseIntent intent(
            OwnerId ownerAssignment,
            String ownerName
    ) {
        BsonDocument sourceMetadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        new BsonString(PROFILE.toString())
                )
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                        new BsonString(SNAPSHOT.toString())
                );
        return new SpawnerCapturedArtifactReleaseIntent(
                "release-click-1",
                ACTOR,
                "world",
                2,
                stack("capture-device-filled", sourceMetadata),
                stack("capture-device-empty", new BsonDocument()),
                ownerAssignment,
                ownerName,
                null
        );
    }

    private SpawnerCapturedArtifactReleaseIntent modernSupersededIntent() {
        BsonDocument sourceMetadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        new BsonString(PROFILE.toString())
                )
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(NEWER_SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                        new BsonString(NEWER_SNAPSHOT.toString())
                )
                .append(
                        TameworkMetadataKeys.OWNER_UUID,
                        new BsonString(CANONICAL_OWNER.toString())
                )
                .append(TameworkMetadataKeys.TAMED, BsonBoolean.TRUE);
        return new SpawnerCapturedArtifactReleaseIntent(
                "release-click-superseded",
                ACTOR,
                "world",
                2,
                stack("capture-device-filled", sourceMetadata),
                stack("capture-device-empty", new BsonDocument()),
                null,
                null,
                null
        );
    }

    private SpawnerCapturedArtifactReleaseIntent releasedPublicIntent() {
        return releasedPublicIntent(null, null, true);
    }

    private SpawnerCapturedArtifactReleaseIntent
    orphanedReleasedPublicIntent() {
        BsonDocument sourceMetadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_ROLE_ID,
                        new BsonString("tamework_test")
                )
                .append(TameworkMetadataKeys.CAPTURED, BsonBoolean.TRUE)
                .append(TameworkMetadataKeys.TAMED, BsonBoolean.TRUE)
                .append(
                        TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                        new BsonString(ASSIGNED_OWNER.toString())
                );
        return new SpawnerCapturedArtifactReleaseIntent(
                "release-click-orphan",
                ACTOR,
                "world",
                2,
                stack("capture-device-filled", sourceMetadata),
                stack("capture-device-empty", new BsonDocument()),
                ASSIGNED_OWNER,
                "Assigned owner",
                null
        );
    }

    private SpawnerCapturedArtifactReleaseIntent releasedPublicIntent(
            OwnerId ownerAssignment,
            String ownerAssignmentName,
            boolean includeItemOwner
    ) {
        BsonDocument sourceMetadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(SOURCE.toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID,
                        new BsonString(STALE_OWNER.toString())
                )
                .append(
                        TameworkMetadataKeys.TAMED,
                        BsonBoolean.TRUE
                )
                .append(
                        TameworkMetadataKeys.NPC_NAME,
                        new BsonString("Legacy name")
                )
                .append(
                        TameworkMetadataKeys.NPC_NAME_UPDATED_MS,
                        new BsonInt64(-700L)
                )
                .append(
                        TameworkMetadataKeys.HEALTH_PERCENT,
                        new BsonDouble(0.5D)
                )
                .append(
                        TameworkMetadataKeys.HAPPINESS_CONFIG_ID,
                        new BsonString("happiness")
                )
                .append(
                        TameworkMetadataKeys.HAPPINESS_VALUE,
                        new BsonDouble(75.0D)
                )
                .append(
                        TameworkMetadataKeys.HAPPINESS_LAST_UPDATE_MS,
                        new BsonInt64(-777L)
                );
        if (includeItemOwner) {
            sourceMetadata.append(
                    TameworkMetadataKeys.OWNER_UUID,
                    new BsonString(CANONICAL_OWNER.toString())
            );
        }
        return new SpawnerCapturedArtifactReleaseIntent(
                "release-click-public",
                ACTOR,
                "world",
                2,
                stack("capture-device-filled", sourceMetadata),
                stack("capture-device-empty", new BsonDocument()),
                ownerAssignment,
                ownerAssignmentName,
                null
        );
    }

    private CompanionProfileReadModel profile(
            OwnerId ownerId,
            String metadata
    ) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Captured companion",
                "tamework_test",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionAlias alias = new CompanionAlias(
                SOURCE,
                PROFILE,
                1L,
                CompanionAlias.State.CURRENT,
                null,
                -800L,
                null
        );
        CompanionSnapshot snapshot = sourceSnapshot();
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                ownerId,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        SNAPSHOT.toString()
                ),
                new LifecycleRevision(5L),
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
                List.of(snapshot),
                null
        );
    }

    private CompanionProfileReadModel releasedPublicProfile() {
        String metadata = """
                {"owner_name":"Canonical owner","custom_name":"Legacy name","tamed":true}
                """.trim();
        return releasedPublicProfile(metadata);
    }

    private CompanionProfileReadModel releasedPublicProfile(String metadata) {
        CompanionIdentity identity = new CompanionIdentity(
                PROFILE,
                "Captured companion",
                "tamework_test",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -1_000L,
                -900L,
                -800L,
                1L
        );
        CompanionAlias alias = new CompanionAlias(
                SOURCE,
                PROFILE,
                1L,
                CompanionAlias.State.CURRENT,
                null,
                -800L,
                null
        );
        String payload = """
                {"lastKnownPosition":{"x":1.0,"y":2.0,"z":3.0},"homePosition":{"x":4.0,"y":5.0,"z":6.0},"capturedAtMs":-650,"roleId":"tamework_test","displayName":"Captured companion"}
                """.trim();
        CompanionSnapshot snapshot = new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                LegacyCaptureV1Payload.VERSION,
                payload,
                Sha256Hash.ofUtf8(payload),
                new LifecycleRevision(4L),
                true,
                -600L
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                PROFILE,
                CANONICAL_OWNER,
                LifecycleState.CAPTURED,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.CAPTURE_ITEM,
                        SNAPSHOT.toString()
                ),
                new LifecycleRevision(5L),
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
                List.of(new CompanionToolLink(
                        PROFILE,
                        TOOL,
                        "capture",
                        -700L,
                        -700L
                )),
                List.of(snapshot),
                null
        );
    }

    private CompanionProfileReadModel alreadyMigratedProfile() {
        CompanionProfileReadModel captured = releasedPublicProfile();
        return new CompanionProfileReadModel(
                captured.identity(),
                captured.currentAlias(),
                new CompanionLifecycle(
                        PROFILE,
                        CANONICAL_OWNER,
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        new LifecycleRevision(1),
                        null,
                        -500L,
                        new ReconciliationGeneration(1),
                        null,
                        null
                ),
                captured.toolLinks(),
                List.of(),
                null
        );
    }

    private CompanionSnapshot sourceSnapshot() {
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                snapshots.encodeCapture(rawState());
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                encoded.payloadVersion(),
                encoded.payloadJson(),
                encoded.payloadHash(),
                new LifecycleRevision(4L),
                true,
                -600L
        );
    }

    private CoopResidentStateSnapshot rawState() {
        TameworkNeedsComponent needs = new TameworkNeedsComponent(
                "needs", 35.0D, 45.0D, 12.0D, 18.0D,
                1_000L, 2_000L
        );
        needs.setRegenSuppressionBaselineHealth(20.0D);
        needs.setRegenSuppressionAllowedHeal(5.0D);
        needs.setLastManagedHealth(15.0D);
        return new CoopResidentStateSnapshot(
                SOURCE.value(),
                null,
                -1,
                "tamework_test",
                new TameworkCommandLinksComponent(
                        STALE_OWNER.value(),
                        new String[]{"tool-a"},
                        new org.joml.Vector3d(1.0D, 2.0D, 3.0D)
                ),
                new TameworkOwnerComponent(
                        STALE_OWNER.value(),
                        "Stale owner"
                ),
                null,
                null,
                null,
                needs,
                null,
                null,
                null,
                null,
                null,
                null,
                0.75D,
                -650L
        );
    }

    private CoopResidentStateSnapshot projection(
            CompanionCaptureReleaseRequest request
    ) {
        SnapshotCodecRegistry codecs = new SnapshotCodecRegistry(List.of(
                new FullStateSnapshotCodecAdapter(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                )
        ));
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                codecs.decode(
                        request.projection(),
                        CoopResidentStateSnapshot.class
                );
        return ((SnapshotDecodeResult.Decoded<
                CoopResidentStateSnapshot>) decoded).value();
    }

    private CompanionSpawnPlacement placement() {
        return new CompanionSpawnPlacement(
                "world",
                1.0D,
                2.0D,
                3.0D,
                0.1F,
                0.2F,
                0.3F
        );
    }

    private ItemStack stack(String itemId, BsonDocument metadata) {
        return HytaleItemStackTestFixture.stack(itemId, metadata);
    }

    private static OperationWorkflowResult published(
            OperationId operationId,
            IdempotencyKey idempotencyKey
    ) {
        OperationEnvelope envelope = new OperationEnvelope(
                operationId,
                idempotencyKey,
                new OperationKind("test_spawner_release"),
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
                -20L,
                -10L,
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

    private static final class FakePersistence
            implements SpawnerCapturedArtifactReleaseAuthor.PersistencePort {
        private final CompanionProfileReadModel profile;
        private CompanionCaptureReleaseRequest request;
        private final List<CompanionSnapshot> history;
        private int profileReads;
        private int aliasReads;
        private int historyReads;

        private FakePersistence(CompanionProfileReadModel profile) {
            this(profile, List.of());
        }

        private FakePersistence(
                CompanionProfileReadModel profile,
                List<CompanionSnapshot> history
        ) {
            this.profile = profile;
            this.history = List.copyOf(history);
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(ProfileId profileId) {
            profileReads++;
            return CompletableFuture.completedFuture(
                    profile == null
                            ? PersistenceReadResult.absent()
                            : PersistenceReadResult.found(profile, 1L)
            );
        }

        @Override
        public CompletionStage<PersistenceReadResult<CompanionProfileReadModel>>
        findProfile(NpcAlias alias) {
            aliasReads++;
            return CompletableFuture.completedFuture(
                    profile != null && profile.currentAlias() != null
                            && profile.currentAlias().alias().equals(alias)
                            ? PersistenceReadResult.found(profile, 1L)
                            : PersistenceReadResult.absent()
            );
        }

        @Override
        public CompletionStage<PersistenceReadResult<List<CompanionSnapshot>>>
        findSnapshotHistory(
                ProfileId profileId,
                com.alechilles.alecstamework.companion.snapshot.SnapshotKind kind
        ) {
            historyReads++;
            return CompletableFuture.completedFuture(
                    PersistenceReadResult.found(history, history.size())
            );
        }

        @Override
        public PublicOperationSubmission release(
                OperationId operationId,
                IdempotencyKey idempotencyKey,
                CompanionCaptureReleaseRequest release
        ) {
            request = release;
            return new PublicOperationSubmission(
                    PublicOperationSubmission.Admission.ACCEPTED,
                    CompletableFuture.completedFuture(published(
                            operationId, idempotencyKey
                    ))
            );
        }
    }
}
