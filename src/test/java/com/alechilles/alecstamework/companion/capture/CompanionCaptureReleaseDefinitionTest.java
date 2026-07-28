package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Durable codec and invariant tests for captured-artifact release evidence. */
class CompanionCaptureReleaseDefinitionTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("10000000-0000-0000-0000-000000000001");
    private static final NpcAlias SOURCE =
            NpcAlias.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias TARGET =
            NpcAlias.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000001");
    private static final SnapshotId SNAPSHOT =
            SnapshotId.parse("50000000-0000-0000-0000-000000000001");

    @Test
    void roundTripPreservesBothIndependentReceiptsAndExactArtifacts() {
        CompanionCaptureReleaseRequest request = request(
                projection(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                ),
                "inventory-receipt",
                "spawn-receipt"
        );

        CompanionCaptureReleaseRequest decoded =
                CompanionCaptureReleaseDefinition.INSTANCE.decode(
                        CompanionCaptureReleaseDefinition.INSTANCE.encode(
                                request
                        )
                );

        assertEquals(request, decoded);
        assertEquals(
                "inventory-receipt",
                decoded.inventoryReceiptKey()
        );
        assertEquals("spawn-receipt", decoded.spawnReceiptKey());
        assertEquals(OWNER, decoded.ownerAssignment());
    }

    @Test
    void versionOnePayloadWithoutOwnerAssignmentRemainsReadable() {
        JsonObject legacy = JsonParser.parseString(
                CompanionCaptureReleaseDefinition.INSTANCE.encode(request(
                        projection(
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION
                        ),
                        "inventory-receipt",
                        "spawn-receipt"
                ))
        ).getAsJsonObject();
        legacy.remove("ownerAssignment");

        CompanionCaptureReleaseRequest decoded =
                CompanionCaptureReleaseDefinition.INSTANCE.decode(
                        legacy.toString()
                );

        assertNull(decoded.ownerAssignment());
    }

    @Test
    void projectionMustUseSharedFullStateVersionOneCodec() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        projection(new SnapshotKind("capture"), 1),
                        "inventory-receipt",
                        "spawn-receipt"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        projection(
                                CompanionFullStateProjection.KIND,
                                2
                        ),
                        "inventory-receipt",
                        "spawn-receipt"
                )
        );
    }

    @Test
    void inventoryAndSpawnReceiptsCannotCollapseIntoOneProof() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(
                        projection(
                                CompanionFullStateProjection.KIND,
                                1
                        ),
                        "same-receipt",
                        "same-receipt"
                )
        );
    }

    @Test
    void sourceArtifactMustCarryExactProfileSnapshotAndAliasReceipts() {
        CaptureReleaseSourceEvidence wrongSource =
                new CaptureReleaseSourceEvidence(
                        UUID.fromString(
                                "40000000-0000-0000-0000-000000000001"
                        ),
                        "world",
                        2,
                        artifact(
                                "filled",
                                "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                                        + "\":\"" + SNAPSHOT + "\","
                                        + "\"" + TameworkMetadataKeys
                                        .COMPANION_PROFILE_ID
                                        + "\":\"wrong-profile\","
                                        + "\"" + TameworkMetadataKeys.TARGET_UUID
                                        + "\":\"" + SOURCE + "\""
                        ),
                        receiptArtifact("inventory-receipt")
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> request(wrongSource)
        );
    }

    @Test
    void sourceRevisionAcceptsNativeAndImportedCaptureShapes() {
        new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                sourceSnapshot(new LifecycleRevision(1)),
                SOURCE,
                projection(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                ),
                source("inventory-receipt"),
                TARGET,
                null,
                new CompanionSpawnPlacement(
                        "world", 1, 2, 3, 0, 0, 0
                ),
                "inventory-receipt",
                "spawn-receipt",
                -800
        );
        new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(1),
                sourceSnapshot(new LifecycleRevision(1)),
                SOURCE,
                projection(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                ),
                source("inventory-receipt"),
                TARGET,
                null,
                new CompanionSpawnPlacement(
                        "world", 1, 2, 3, 0, 0, 0
                ),
                "inventory-receipt",
                "spawn-receipt",
                -800
        );
    }

    @Test
    void sourceRevisionRejectsEvidenceOlderThanOneLifecycleStep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionCaptureReleaseRequest(
                        PROFILE,
                        new LifecycleRevision(2),
                        sourceSnapshot(LifecycleRevision.INITIAL),
                        SOURCE,
                        projection(
                                CompanionFullStateProjection.KIND,
                                CompanionFullStateProjection.VERSION
                        ),
                        source("inventory-receipt"),
                        TARGET,
                        null,
                        new CompanionSpawnPlacement(
                                "world", 1, 2, 3, 0, 0, 0
                        ),
                        "inventory-receipt",
                        "spawn-receipt",
                        -800
                )
        );
    }

    private CompanionCaptureReleaseRequest request(
            SnapshotCodecRegistry.EncodedSnapshot projection,
            String inventoryReceipt,
            String spawnReceipt
    ) {
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                sourceSnapshot(),
                SOURCE,
                projection,
                source(inventoryReceipt),
                TARGET,
                OWNER,
                new CompanionSpawnPlacement(
                        "world", 1, 2, 3, 0, 0, 0
                ),
                inventoryReceipt,
                spawnReceipt,
                -800
        );
    }

    private CompanionCaptureReleaseRequest request(
            CaptureReleaseSourceEvidence source
    ) {
        return new CompanionCaptureReleaseRequest(
                PROFILE,
                new LifecycleRevision(2),
                sourceSnapshot(),
                SOURCE,
                projection(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION
                ),
                source,
                TARGET,
                OWNER,
                new CompanionSpawnPlacement(
                        "world", 1, 2, 3, 0, 0, 0
                ),
                "inventory-receipt",
                "spawn-receipt",
                -800
        );
    }

    private CompanionSnapshot sourceSnapshot() {
        return sourceSnapshot(new LifecycleRevision(1));
    }

    private CompanionSnapshot sourceSnapshot(
            LifecycleRevision sourceRevision
    ) {
        String payload = "{\"capture\":\"envelope\"}";
        return new CompanionSnapshot(
                SNAPSHOT,
                PROFILE,
                CompanionCaptureRequest.SNAPSHOT_KIND,
                1,
                payload,
                Sha256Hash.ofUtf8(payload),
                sourceRevision,
                true,
                -900
        );
    }

    private SnapshotCodecRegistry.EncodedSnapshot projection(
            SnapshotKind kind,
            int version
    ) {
        String payload = "{\"state\":\"frozen\"}";
        return new SnapshotCodecRegistry.EncodedSnapshot(
                kind,
                version,
                payload,
                Sha256Hash.ofUtf8(payload)
        );
    }

    private CaptureReleaseSourceEvidence source(String receipt) {
        return new CaptureReleaseSourceEvidence(
                UUID.fromString(
                        "40000000-0000-0000-0000-000000000001"
                ),
                "world",
                2,
                artifact(
                        "filled",
                        "\"" + TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID
                                + "\":\"" + SNAPSHOT + "\","
                                + "\"" + TameworkMetadataKeys
                                .COMPANION_PROFILE_ID
                                + "\":\"" + PROFILE + "\","
                                + "\"" + TameworkMetadataKeys.TARGET_UUID
                                + "\":\"" + SOURCE + "\""
                ),
                receiptArtifact(receipt)
        );
    }

    private CapturedArtifact receiptArtifact(String receipt) {
        return artifact(
                "empty",
                "\"" + TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT
                        + "\":\"" + receipt + "\""
        );
    }

    private CapturedArtifact artifact(String itemId, String metadata) {
        return CapturedArtifact.create(
                itemId,
                1,
                0.0D,
                0.0D,
                "{" + metadata + "}"
        );
    }
}
