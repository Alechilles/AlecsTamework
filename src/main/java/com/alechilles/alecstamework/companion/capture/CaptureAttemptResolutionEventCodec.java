package com.alechilles.alecstamework.companion.capture;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Projection payload codec for one terminal durable capture roll. */
public final class CaptureAttemptResolutionEventCodec {
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("capture_attempt_resolved");
    public static final int VERSION = 2;

    private CaptureAttemptResolutionEventCodec() {
    }

    @Nonnull
    public static String encode(
            @Nonnull UUID actorUuid,
            @Nonnull CaptureAttemptResolution resolution
    ) {
        if (actorUuid == null || resolution == null) {
            throw new IllegalArgumentException(
                    "Complete resolved capture event is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("actorUuid", actorUuid.toString());
        json.add(
                "resolution",
                CaptureAttemptResolutionJsonCodec.encode(resolution)
        );
        return json.toString();
    }

    @Nonnull
    public static CaptureAttemptResolution decode(
            int version,
            @Nonnull String json
    ) {
        return decodeEvent(version, json).resolution();
    }

    @Nonnull
    public static CaptureAttemptResolvedEvent decodeEvent(
            int version,
            @Nonnull String json
    ) {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "capture_attempt_resolution_event_version_unsupported"
            );
        }
        JsonObject encoded = JsonParser.parseString(json).getAsJsonObject();
        return new CaptureAttemptResolvedEvent(
                UUID.fromString(
                        encoded.get("actorUuid").getAsString()
                ),
                CaptureAttemptResolutionJsonCodec.decode(
                        encoded.getAsJsonObject("resolution")
                )
        );
    }
}
