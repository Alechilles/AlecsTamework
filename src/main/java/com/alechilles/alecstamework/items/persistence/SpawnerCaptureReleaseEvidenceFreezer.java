package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bson.BsonDocument;
import org.bson.BsonString;

/** Freezes exact artifact context and deterministic identities for one captured-spawner release. */
final class SpawnerCaptureReleaseEvidenceFreezer {
    private static final String RELEASE =
            "spawner-captured-artifact-release:v1";
    private static final String INVENTORY_RECEIPT =
            "spawner-captured-artifact-release-inventory:v1";
    private static final String SPAWN_RECEIPT =
            "spawner-captured-artifact-release-spawn:v1";
    private static final String TARGET_ALIAS =
            "spawner-captured-artifact-release-alias:v1";

    private final HytaleCapturedArtifactAdapter artifacts;
    private final LongSupplier clock;

    SpawnerCaptureReleaseEvidenceFreezer(
            HytaleCapturedArtifactAdapter artifacts,
            LongSupplier clock
    ) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    FrozenRelease freeze(SpawnerCapturedArtifactReleaseIntent intent) {
        ItemStack sourceStack = intent.sourceArtifactStack();
        UUID profileUuid = sourceStack.getFromMetadataOrNull(
                TameworkMetadataKeys.COMPANION_PROFILE_ID,
                Codec.UUID_STRING
        );
        UUID sourceUuid = sourceStack.getFromMetadataOrNull(
                TameworkMetadataKeys.TARGET_UUID,
                Codec.UUID_STRING
        );
        String snapshotText = sourceStack.getFromMetadataOrNull(
                TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                Codec.STRING
        );
        if (profileUuid == null || sourceUuid == null
                || snapshotText == null || snapshotText.isBlank()) {
            throw new ContextFailure(
                    "capture_release_source_metadata_missing"
            );
        }
        ProfileId profileId = new ProfileId(profileUuid);
        NpcAlias sourceAlias = new NpcAlias(sourceUuid);
        SnapshotId snapshotId = SnapshotId.parse(snapshotText);
        CapturedArtifact source = artifacts.toArtifact(sourceStack);
        String[] parts = {
                intent.intentKey(),
                intent.actorUuid().toString(),
                intent.worldKey(),
                profileId.toString(),
                sourceAlias.toString(),
                snapshotId.toString(),
                intent.ownerAssignment() == null
                        ? "preserve"
                        : intent.ownerAssignment().toString(),
                source.artifactHash().toString()
        };
        String inventoryReceipt = StablePersistenceIds.receipt(
                INVENTORY_RECEIPT, parts
        );
        String spawnReceipt = StablePersistenceIds.receipt(
                SPAWN_RECEIPT, parts
        );
        NpcAlias targetAlias = StablePersistenceIds.targetAlias(
                TARGET_ALIAS, parts
        );
        if (sourceAlias.equals(targetAlias)
                || inventoryReceipt.equals(spawnReceipt)) {
            throw new ContextFailure(
                    "capture_release_deterministic_identity_collision"
            );
        }
        ItemStack receiptStack = artifacts.withMetadata(
                intent.receiptArtifactStack(),
                new BsonDocument(
                        TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT,
                        new BsonString(inventoryReceipt)
                )
        );
        return new FrozenRelease(
                intent.frozenContext(),
                clock.getAsLong(),
                StablePersistenceIds.operationId(RELEASE, parts),
                StablePersistenceIds.idempotencyKey(RELEASE, parts),
                profileId,
                sourceAlias,
                targetAlias,
                snapshotId,
                source,
                artifacts.toArtifact(receiptStack),
                inventoryReceipt,
                spawnReceipt,
                intent.ownerAssignment(),
                intent.ownerAssignmentName()
        );
    }

    static final class ContextFailure extends RuntimeException {
        private ContextFailure(String detail) {
            super(detail);
        }
    }

    record FrozenRelease(
            SpawnerCaptureReleaseContext context,
            long requestedAt,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            ProfileId profileId,
            NpcAlias sourceAlias,
            NpcAlias targetAlias,
            SnapshotId snapshotId,
            CapturedArtifact sourceArtifact,
            CapturedArtifact receiptArtifact,
            String inventoryReceipt,
            String spawnReceipt,
            OwnerId ownerAssignment,
            String ownerAssignmentName
    ) {
    }
}
