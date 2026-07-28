package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.snapshot.SnapshotCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotCodec;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import javax.annotation.Nonnull;

/**
 * Adapts the complete resident-state codec to one canonical snapshot kind and version.
 */
public final class FullStateSnapshotCodecAdapter
        implements SnapshotCodec<CoopResidentStateSnapshot> {
    private final SnapshotKind kind;
    private final int version;
    private final CoopResidentStateSnapshotCodec delegate;

    public FullStateSnapshotCodecAdapter(@Nonnull SnapshotKind kind, int version) {
        if (kind == null || version <= 0) {
            throw new IllegalArgumentException("Valid full-state snapshot key is required");
        }
        this.kind = kind;
        this.version = version;
        this.delegate = new CoopResidentStateSnapshotCodec();
    }

    @Override
    @Nonnull
    public SnapshotKind kind() {
        return kind;
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    @Nonnull
    public Class<CoopResidentStateSnapshot> valueType() {
        return CoopResidentStateSnapshot.class;
    }

    @Override
    @Nonnull
    public String encode(@Nonnull CoopResidentStateSnapshot value) {
        if (value == null) {
            throw new IllegalArgumentException("Full-state snapshot is required");
        }
        return delegate.encode(value);
    }

    @Override
    @Nonnull
    public CoopResidentStateSnapshot decode(@Nonnull String payloadJson) {
        CoopResidentStateSnapshotCodec.DecodeResult result = delegate.decode(payloadJson);
        if (result.status() == CoopResidentStateSnapshotCodec.Status.FOUND
                && result.snapshot() != null) {
            return result.snapshot();
        }
        throw new IllegalArgumentException(failureCode(result));
    }

    private String failureCode(CoopResidentStateSnapshotCodec.DecodeResult result) {
        if (result.status() == CoopResidentStateSnapshotCodec.Status.NOT_FOUND) {
            return "full_state_snapshot_missing";
        }
        return "full_state_snapshot_invalid:"
                + (result.failure() == null ? "unknown" : result.failure().name());
    }
}
