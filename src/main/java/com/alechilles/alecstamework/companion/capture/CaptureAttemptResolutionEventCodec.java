package com.alechilles.alecstamework.companion.capture;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Projection payload codec for one terminal durable capture roll. */
public final class CaptureAttemptResolutionEventCodec {
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("capture_attempt_resolved");
    public static final int LEGACY_VERSION = 2;
    public static final int VERSION = 3;

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

    /** Encodes every immutable request/result fact needed by recovery-time public mapping. */
    @Nonnull
    public static String encode(
            @Nonnull OperationId operationId,
            @Nonnull IdempotencyKey operationIdempotencyKey,
            @Nonnull CompanionCaptureRequest request,
            long resolvedAtMs
    ) {
        if (operationId == null || operationIdempotencyKey == null
                || request == null) {
            throw new IllegalArgumentException(
                    "Complete resolved capture replay evidence is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("operationId", operationId.toString());
        json.addProperty(
                "operationIdempotencyKey",
                operationIdempotencyKey.toString()
        );
        json.addProperty(
                "requestPayloadVersion",
                CompanionCaptureDefinition.INSTANCE.payloadVersion()
        );
        json.addProperty(
                "requestJson",
                CompanionCaptureDefinition.INSTANCE.encode(request)
        );
        json.addProperty("resolvedAtMs", resolvedAtMs);
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
        if (version != VERSION && version != LEGACY_VERSION) {
            throw new IllegalArgumentException(
                    "capture_attempt_resolution_event_version_unsupported"
            );
        }
        JsonObject encoded = JsonParser.parseString(json).getAsJsonObject();
        if (version == LEGACY_VERSION) {
            return CaptureAttemptResolvedEvent.legacy(
                    UUID.fromString(
                            encoded.get("actorUuid").getAsString()
                    ),
                    CaptureAttemptResolutionJsonCodec.decode(
                            encoded.getAsJsonObject("resolution")
                    )
            );
        }
        int requestVersion =
                encoded.get("requestPayloadVersion").getAsInt();
        if (requestVersion
                != CompanionCaptureDefinition.INSTANCE.payloadVersion()) {
            throw new IllegalArgumentException(
                    "capture_attempt_request_version_unsupported"
            );
        }
        return CaptureAttemptResolvedEvent.complete(
                OperationId.parse(
                        encoded.get("operationId").getAsString()
                ),
                new IdempotencyKey(
                        encoded.get("operationIdempotencyKey").getAsString()
                ),
                CompanionCaptureDefinition.INSTANCE.decode(
                        encoded.get("requestJson").getAsString()
                ),
                encoded.get("resolvedAtMs").getAsLong()
        );
    }
}
