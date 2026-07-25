package com.alechilles.alecstamework.companion.capture;

import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Projection payload codec for one terminal durable capture roll. */
public final class CaptureAttemptResolutionEventCodec {
    public static final int VERSION = 1;

    private CaptureAttemptResolutionEventCodec() {
    }

    @Nonnull
    public static String encode(
            @Nonnull CaptureAttemptResolution resolution
    ) {
        return CaptureAttemptResolutionJsonCodec.encode(resolution)
                .toString();
    }

    @Nonnull
    public static CaptureAttemptResolution decode(
            int version,
            @Nonnull String json
    ) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "capture_attempt_resolution_event_version_unsupported"
            );
        }
        return CaptureAttemptResolutionJsonCodec.decode(
                JsonParser.parseString(json).getAsJsonObject()
        );
    }
}
