package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Maps complete spawner state between the capture-v1 artifact and the source-neutral projection.
 */
final class SpawnerCaptureSnapshotMapper {
    static final int CAPTURE_VERSION = 1;

    private final SnapshotCodecRegistry codecs = new SnapshotCodecRegistry(
            List.of(
                    new FullStateSnapshotCodecAdapter(
                            CompanionCaptureRequest.SNAPSHOT_KIND,
                            CAPTURE_VERSION
                    ),
                    new FullStateSnapshotCodecAdapter(
                            CompanionFullStateProjection.KIND,
                            CompanionFullStateProjection.VERSION
                    )
            )
    );

    /** Freezes complete live state into the sole capture payload version. */
    @Nonnull
    SnapshotCodecRegistry.EncodedSnapshot encodeCapture(
            @Nonnull CoopResidentStateSnapshot state
    ) {
        return codecs.encode(
                CompanionCaptureRequest.SNAPSHOT_KIND,
                CAPTURE_VERSION,
                CoopResidentStateSnapshot.class,
                state
        );
    }

    /** Decodes one exact capture snapshot without collapsing failure into absence. */
    @Nonnull
    SnapshotDecodeResult<CoopResidentStateSnapshot> decodeCapture(
            @Nonnull CompanionSnapshot snapshot
    ) {
        return codecs.decode(snapshot, CoopResidentStateSnapshot.class);
    }

    /** Re-encodes decoded capture state as the source-neutral spawn projection. */
    @Nonnull
    SnapshotCodecRegistry.EncodedSnapshot encodeProjection(
            @Nonnull CoopResidentStateSnapshot state
    ) {
        return codecs.encode(
                CompanionFullStateProjection.KIND,
                CompanionFullStateProjection.VERSION,
                CoopResidentStateSnapshot.class,
                state
        );
    }
}
