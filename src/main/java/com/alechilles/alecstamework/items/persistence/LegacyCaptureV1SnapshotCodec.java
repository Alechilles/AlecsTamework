package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureRequest;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodec;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import javax.annotation.Nonnull;

/** Registry codec for the minimal capture payload shipped through public Tamework 2.16.1. */
final class LegacyCaptureV1SnapshotCodec
        implements SnapshotCodec<LegacyCaptureV1Payload> {
    @Override
    @Nonnull
    public SnapshotKind kind() {
        return CompanionCaptureRequest.SNAPSHOT_KIND;
    }

    @Override
    public int version() {
        return LegacyCaptureV1Payload.VERSION;
    }

    @Override
    @Nonnull
    public Class<LegacyCaptureV1Payload> valueType() {
        return LegacyCaptureV1Payload.class;
    }

    @Override
    @Nonnull
    public String encode(@Nonnull LegacyCaptureV1Payload value) {
        return value.encodePayloadJson();
    }

    @Override
    @Nonnull
    public LegacyCaptureV1Payload decode(@Nonnull String payloadJson) {
        return LegacyCaptureV1Payload.decodePayloadJson(payloadJson);
    }
}
