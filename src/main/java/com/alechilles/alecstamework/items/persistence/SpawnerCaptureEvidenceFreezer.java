package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CapturedArtifact;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.StablePersistenceIds;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonString;

/**
 * Freezes all live, inventory, time, identity, and receipt evidence for one capture submission.
 *
 * <p>The component snapshot is a world-thread local only. The returned value retains its hashed
 * JSON encoding and immutable adoption/event facts, never the component graph.</p>
 */
final class SpawnerCaptureEvidenceFreezer {
    private static final String CAPTURE = "spawner-live-capture:v1";

    private final TameworkFullStateSnapshotReader snapshots;
    private final HytaleCapturedArtifactAdapter artifacts;
    private final SpawnerCaptureSnapshotMapper snapshotMapper;
    private final SpawnerFullStateOwnershipNormalizer ownership;
    private final LongSupplier clock;

    SpawnerCaptureEvidenceFreezer(
            TameworkFullStateSnapshotReader snapshots,
            HytaleCapturedArtifactAdapter artifacts,
            SpawnerCaptureSnapshotMapper snapshotMapper,
            LongSupplier clock
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.snapshotMapper = Objects.requireNonNull(
                snapshotMapper, "snapshotMapper"
        );
        this.ownership = new SpawnerFullStateOwnershipNormalizer();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    FrozenCapture freeze(SpawnerCaptureIntent intent) {
        SpawnerCaptureContext context = intent.frozenContext();
        TameworkFullStateSnapshotReader.ReadResult read = snapshots.read(
                intent.sourceRef(), intent.sourceStore(),
                intent.sourceAlias(), intent.roleId()
        );
        if (!read.successful() || read.snapshot() == null) {
            throw new EvidenceFailure(read.failure() == null
                    ? "snapshot_unavailable"
                    : read.failure().name().toLowerCase(java.util.Locale.ROOT));
        }
        CoopResidentStateSnapshot fullState = read.snapshot();
        if (!intent.sourceAlias().value().equals(fullState.npcUuid())) {
            throw new EvidenceFailure("snapshot_alias_mismatch");
        }
        SpawnerCaptureLiveFacts liveFacts =
                SpawnerCaptureLiveFacts.freeze(fullState);
        long requestedAt = clock.getAsLong();
        CapturedArtifact source = artifacts.toArtifact(intent.sourceStack());
        CapturedArtifact remainder = source.quantity() == 1
                ? null
                : artifacts.toArtifact(
                        intent.sourceStack().withQuantity(
                                source.quantity() - 1
                        )
                );
        String[] parts = intentParts(intent, source);
        OperationId operationId =
                StablePersistenceIds.operationId(CAPTURE, parts);
        if (!intent.resolution().successful()) {
            return new FrozenCapture(
                    context,
                    requestedAt,
                    operationId,
                    StablePersistenceIds.idempotencyKey(CAPTURE, parts),
                    null,
                    null,
                    liveFacts,
                    source,
                    remainder,
                    null,
                    intent.resolution()
            );
        }
        if (intent.resolution().successDisposition()
                != CaptureSuccessDisposition.CAPTURED_ITEM) {
            throw new EvidenceFailure(
                    "capture_success_disposition_not_supported"
            );
        }
        CoopResidentStateSnapshot capturedState = ownership.normalize(
                fullState,
                intent.resultingOwnerId(),
                intent.resultingOwnerName()
        );
        SnapshotId snapshotId = new SnapshotId(
                intent.resolution().attemptId()
        );
        BsonDocument metadata = new BsonDocument()
                .append(
                        TameworkMetadataKeys.COMPANION_PROFILE_ID,
                        new BsonString(intent.profileId().toString())
                )
                .append(
                        TameworkMetadataKeys.TARGET_UUID,
                        new BsonString(intent.sourceAlias().toString())
                )
                .append(
                        TameworkMetadataKeys.CAPTURE_SNAPSHOT_ID,
                        new BsonString(snapshotId.toString())
                );
        ItemStack tagged = artifacts.withMetadata(
                intent.filledArtifactStack(),
                metadata
        );
        return new FrozenCapture(
                context,
                requestedAt,
                operationId,
                StablePersistenceIds.idempotencyKey(CAPTURE, parts),
                snapshotId,
                snapshotMapper.encodeCapture(capturedState),
                liveFacts,
                source,
                remainder,
                artifacts.toArtifact(tagged),
                intent.resolution()
        );
    }

    private String[] intentParts(
            SpawnerCaptureIntent intent,
            CapturedArtifact source
    ) {
        return new String[]{
                intent.intentKey(),
                intent.actorUuid().toString(),
                intent.profileId().toString(),
                intent.sourceAlias().toString(),
                intent.worldKey(),
                Integer.toString(intent.sourceSlot()),
                source.artifactHash().toString()
        };
    }

    static final class EvidenceFailure extends RuntimeException {
        private EvidenceFailure(String detail) {
            super(detail);
        }
    }

    /** Complete engine-neutral value allowed to survive into asynchronous continuations. */
    record FrozenCapture(
            SpawnerCaptureContext context,
            long requestedAt,
            OperationId operationId,
            IdempotencyKey idempotencyKey,
            @Nullable SnapshotId snapshotId,
            @Nullable SnapshotCodecRegistry.EncodedSnapshot encoded,
            SpawnerCaptureLiveFacts liveFacts,
            CapturedArtifact source,
            @Nullable CapturedArtifact remainder,
            @Nullable CapturedArtifact artifact,
            CaptureAttemptResolution resolution
    ) {
    }
}
