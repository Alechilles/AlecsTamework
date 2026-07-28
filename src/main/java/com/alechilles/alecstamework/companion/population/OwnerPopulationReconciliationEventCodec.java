package com.alechilles.alecstamework.companion.population;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.incidents.IncidentId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Version-one codec for replayable owner-population reconciliation outcomes. */
public final class OwnerPopulationReconciliationEventCodec {
    public static final int VERSION = 1;

    private OwnerPopulationReconciliationEventCodec() {
    }

    @Nonnull
    public static String encode(
            @Nonnull OwnerPopulationReconciliationOutcome outcome
    ) {
        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Population reconciliation outcome is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", outcome.profileId().toString());
        json.addProperty("sourceRevision", outcome.sourceRevision().value());
        json.addProperty(
                "committedRevision",
                outcome.committedRevision().value()
        );
        json.addProperty(
                "evidenceGeneration",
                outcome.evidenceGeneration().value()
        );
        json.addProperty("status", outcome.status().name());
        json.addProperty("reasonCode", outcome.reasonCode());
        if (outcome.quarantineIncidentId() == null) {
            json.add("quarantineIncidentId", null);
        } else {
            json.addProperty(
                    "quarantineIncidentId",
                    outcome.quarantineIncidentId().toString()
            );
        }
        json.addProperty("committedAtMs", outcome.committedAtMs());
        return json.toString();
    }

    @Nonnull
    public static OwnerPopulationReconciliationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported population reconciliation event payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        String incident = text(json, "quarantineIncidentId");
        return new OwnerPopulationReconciliationOutcome(
                ProfileId.parse(json.get("profileId").getAsString()),
                new LifecycleRevision(
                        json.get("sourceRevision").getAsLong()
                ),
                new LifecycleRevision(
                        json.get("committedRevision").getAsLong()
                ),
                new ReconciliationGeneration(
                        json.get("evidenceGeneration").getAsLong()
                ),
                OwnerPopulationReconciliationOutcome.Status.valueOf(
                        json.get("status").getAsString()
                ),
                json.get("reasonCode").getAsString(),
                incident == null ? null : IncidentId.parse(incident),
                json.get("committedAtMs").getAsLong()
        );
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}

