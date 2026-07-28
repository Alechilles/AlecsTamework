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
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
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

    PendingRelease freezeSource(
            SpawnerCapturedArtifactReleaseIntent intent
    ) {
        SpawnerCapturedArtifactIdentity.Claim claim =
                SpawnerCapturedArtifactIdentity.parse(
                        intent.sourceArtifactStack()
                );
        if (claim == null) {
            throw new ContextFailure(
                    "capture_release_source_identity_invalid"
            );
        }
        return new PendingRelease(
                intent.frozenContext(),
                intent.intentKey(),
                claim,
                artifacts.toArtifact(intent.sourceArtifactStack()),
                artifacts.toArtifact(intent.receiptArtifactStack()),
                intent.ownerAssignment(),
                intent.ownerAssignmentName(),
                clock.getAsLong()
        );
    }

    FrozenRelease freeze(
            PendingRelease pending,
            ResolvedIdentity resolved,
            @Nullable OwnerId ownerAssignment,
            @Nullable String ownerAssignmentName
    ) {
        SpawnerCapturedArtifactIdentity.Claim claim = pending.claim();
        if (claim == null || resolved == null
                || !claim.sourceAlias().equals(resolved.sourceAlias())
                || (claim.profileId() != null
                        && !claim.profileId().equals(resolved.profileId()))
                || (claim.snapshotId() != null
                        && !claim.snapshotId().equals(
                        resolved.snapshotId()
                ))) {
            throw new ContextFailure(
                    "capture_release_source_identity_mismatch"
            );
        }
        ProfileId profileId = resolved.profileId();
        NpcAlias sourceAlias = resolved.sourceAlias();
        SnapshotId snapshotId = resolved.snapshotId();
        CapturedArtifact source = pending.sourceArtifact();
        String[] parts = {
                pending.intentKey(),
                pending.context().actorUuid().toString(),
                pending.context().worldKey(),
                profileId.toString(),
                sourceAlias.toString(),
                snapshotId.toString(),
                ownerAssignment == null
                        ? "preserve"
                        : ownerAssignment.toString(),
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
        CapturedArtifact receiptArtifact = artifacts.withMetadata(
                pending.receiptArtifact(),
                new BsonDocument(
                        TameworkMetadataKeys.CAPTURE_RELEASE_RECEIPT,
                        new BsonString(inventoryReceipt)
                )
        );
        return new FrozenRelease(
                pending.context(),
                pending.requestedAt(),
                StablePersistenceIds.operationId(RELEASE, parts),
                StablePersistenceIds.idempotencyKey(RELEASE, parts),
                profileId,
                sourceAlias,
                targetAlias,
                snapshotId,
                source,
                receiptArtifact,
                inventoryReceipt,
                spawnReceipt,
                ownerAssignment,
                ownerAssignmentName
        );
    }

    static final class ContextFailure extends RuntimeException {
        ContextFailure(String detail) {
            super(detail);
        }
    }

    record ResolvedIdentity(
            ProfileId profileId,
            NpcAlias sourceAlias,
            SnapshotId snapshotId
    ) {
        ResolvedIdentity {
            if (profileId == null || sourceAlias == null
                    || snapshotId == null) {
                throw new IllegalArgumentException(
                        "Resolved captured-artifact identity is required"
                );
            }
        }
    }

    record PendingRelease(
            SpawnerCaptureReleaseContext context,
            String intentKey,
            SpawnerCapturedArtifactIdentity.Claim claim,
            CapturedArtifact sourceArtifact,
            CapturedArtifact receiptArtifact,
            OwnerId ownerAssignment,
            String ownerAssignmentName,
            long requestedAt
    ) {
        PendingRelease {
            if (context == null || intentKey == null || intentKey.isBlank()
                    || claim == null || sourceArtifact == null
                    || receiptArtifact == null) {
                throw new IllegalArgumentException(
                        "Frozen captured-artifact release source is required"
                );
            }
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
