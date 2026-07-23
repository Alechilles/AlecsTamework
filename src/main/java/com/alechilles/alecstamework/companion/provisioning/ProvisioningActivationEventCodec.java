package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned result event for confirmed initial live activation. */
public final class ProvisioningActivationEventCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("provisioning_activated");

    private ProvisioningActivationEventCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull ProvisioningActivationOutcome outcome
    ) {
        if (operationId == null || outcome == null) {
            throw new IllegalArgumentException(
                    "Operation and provisioning activation outcome are required"
            );
        }
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                "provisioning-activation:" + outcome.profileId(),
                outcome.lifecycleRevision().value(),
                VERSION,
                encode(outcome),
                outcome.activatedAtMs()
        );
    }

    @Nonnull
    public static String encode(
            @Nonnull ProvisioningActivationOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Provisioning activation outcome is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty(
                "profileId", outcome.profileId().toString()
        );
        json.addProperty(
                "liveAlias", outcome.liveAlias().toString()
        );
        json.addProperty("worldKey", outcome.worldKey());
        json.addProperty(
                "lifecycleRevision",
                outcome.lifecycleRevision().value()
        );
        json.addProperty("receiptKey", outcome.receiptKey());
        if (outcome.timedSessionId() == null) {
            json.add("timedSessionId", null);
        } else {
            json.addProperty(
                    "timedSessionId",
                    outcome.timedSessionId().toString()
            );
        }
        json.addProperty("activatedAtMs", outcome.activatedAtMs());
        return json.toString();
    }

    @Nonnull
    public static ProvisioningActivationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported provisioning activation payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        JsonElement session = json.get("timedSessionId");
        return new ProvisioningActivationOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                NpcAlias.parse(json.get("liveAlias").getAsString()),
                json.get("worldKey").getAsString(),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                json.get("receiptKey").getAsString(),
                session == null || session.isJsonNull()
                        ? null
                        : TimedSummonSessionId.parse(
                                session.getAsString()
                        ),
                json.get("activatedAtMs").getAsLong()
        );
    }
}
