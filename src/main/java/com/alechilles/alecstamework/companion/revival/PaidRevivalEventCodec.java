package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import javax.annotation.Nonnull;

/** Versioned outbox codec for successful paid revival. */
public final class PaidRevivalEventCodec {
    public static final int VERSION = 2;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("paid_companion_revived");

    private PaidRevivalEventCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull PaidRevivalOutcome outcome
    ) {
        if (operationId == null || outcome == null) {
            throw new IllegalArgumentException(
                    "Paid revival event evidence is required"
            );
        }
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                "paid-revival-result:" + outcome.profileId(),
                outcome.lifecycleRevision().value(),
                VERSION,
                encode(outcome),
                outcome.revivedAtMs()
        );
    }

    @Nonnull
    public static String encode(@Nonnull PaidRevivalOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Paid revival outcome is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty(
                "callerNamespace", outcome.callerNamespace()
        );
        json.addProperty(
                "callerIdempotencyKey",
                outcome.callerIdempotencyKey()
        );
        json.addProperty("ownerId", outcome.ownerId().toString());
        json.addProperty(
                "commandFamilyId", outcome.commandFamilyId()
        );
        json.addProperty("slotId", outcome.slotId().toString());
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty(
                "sourceSnapshotId",
                outcome.sourceSnapshotId().toString()
        );
        json.addProperty("liveAlias", outcome.liveAlias().toString());
        json.addProperty("worldKey", outcome.worldKey());
        json.addProperty(
                "lifecycleRevision", outcome.lifecycleRevision().value()
        );
        if (outcome.configId() == null) {
            json.add("configId", JsonNull.INSTANCE);
        } else {
            json.addProperty("configId", outcome.configId());
        }
        json.addProperty("configRevision", outcome.configRevision());
        JsonArray cost = new JsonArray();
        for (RevivalCostItem item : outcome.exactCost()) {
            JsonObject row = new JsonObject();
            row.addProperty("itemId", item.itemId());
            row.addProperty("quantity", item.quantity());
            cost.add(row);
        }
        json.add("exactCost", cost);
        json.addProperty(
                "chargeReceiptKey", outcome.chargeReceiptKey()
        );
        json.addProperty("spawnReceiptKey", outcome.spawnReceiptKey());
        json.add(
                "timedSessionId",
                outcome.timedSessionId() == null
                        ? JsonNull.INSTANCE
                        : new com.google.gson.JsonPrimitive(
                                outcome.timedSessionId().toString()
                        )
        );
        json.addProperty("revivedAtMs", outcome.revivedAtMs());
        return json.toString();
    }

    @Nonnull
    public static PaidRevivalOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "paid_revival_event_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        ArrayList<RevivalCostItem> cost = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("exactCost")) {
            JsonObject row = element.getAsJsonObject();
            cost.add(new RevivalCostItem(
                    row.get("itemId").getAsString(),
                    row.get("quantity").getAsInt()
            ));
        }
        JsonElement session = json.get("timedSessionId");
        JsonElement configId = json.get("configId");
        return new PaidRevivalOutcome(
                json.get("callerNamespace").getAsString(),
                json.get("callerIdempotencyKey").getAsString(),
                OwnerId.parse(json.get("ownerId").getAsString()),
                json.get("commandFamilyId").getAsString(),
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                ProfileId.parse(json.get("profileId").getAsString()),
                SnapshotId.parse(
                        json.get("sourceSnapshotId").getAsString()
                ),
                NpcAlias.parse(json.get("liveAlias").getAsString()),
                json.get("worldKey").getAsString(),
                new LifecycleRevision(
                        json.get("lifecycleRevision").getAsLong()
                ),
                configId == null || configId.isJsonNull()
                        ? null
                        : configId.getAsString(),
                json.get("configRevision").getAsString(),
                cost,
                json.get("chargeReceiptKey").getAsString(),
                json.get("spawnReceiptKey").getAsString(),
                session == null || session.isJsonNull()
                        ? null
                        : TimedSummonSessionId.parse(
                                session.getAsString()
                        ),
                json.get("revivedAtMs").getAsLong()
        );
    }
}
