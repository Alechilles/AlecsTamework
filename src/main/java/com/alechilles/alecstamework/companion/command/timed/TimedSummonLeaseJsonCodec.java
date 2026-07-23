package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import javax.annotation.Nonnull;

/** Canonical version-one JSON codec for timed summon lease evidence. */
public final class TimedSummonLeaseJsonCodec {
    public static final int VERSION = 1;

    private TimedSummonLeaseJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull TimedSummonLease lease) {
        if (lease == null) {
            throw new IllegalArgumentException(
                    "Timed summon lease is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", lease.profileId().toString());
        json.addProperty("leaseRevision", lease.leaseRevision());
        nullable(json, "sessionId", lease.sessionId());
        nullable(json, "remainingMs", lease.remainingMs());
        nullable(json, "cooldownUntilMs", lease.cooldownUntilMs());
        json.add("policy", encode(lease.policy()));
        json.add(
                "emittedWarningThresholdsMs",
                longs(lease.emittedWarningThresholdsMs())
        );
        nullable(json, "checkpointedAtMs", lease.checkpointedAtMs());
        json.addProperty("createdAtMs", lease.createdAtMs());
        json.addProperty("updatedAtMs", lease.updatedAtMs());
        return json;
    }

    @Nonnull
    public static TimedSummonLease decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Timed summon lease JSON is required"
            );
        }
        return new TimedSummonLease(
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("leaseRevision").getAsLong(),
                optional(json, "sessionId") == null
                        ? null
                        : TimedSummonSessionId.parse(
                                optional(json, "sessionId").getAsString()
                        ),
                longOrNull(json, "remainingMs"),
                longOrNull(json, "cooldownUntilMs"),
                decodePolicy(json.getAsJsonObject("policy")),
                new HashSet<>(readLongs(
                        json.getAsJsonArray(
                                "emittedWarningThresholdsMs"
                        )
                )),
                longOrNull(json, "checkpointedAtMs"),
                json.get("createdAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }

    private static JsonObject encode(TimedSummonPolicy policy) {
        JsonObject json = new JsonObject();
        nullable(json, "configId", policy.configId());
        nullable(json, "configRevision", policy.configRevision());
        json.addProperty(
                "activeDurationMs", policy.activeDurationMs()
        );
        json.addProperty(
                "resummonCooldownMs", policy.resummonCooldownMs()
        );
        json.addProperty(
                "autoStoreOnOwnerLogout",
                policy.autoStoreOnOwnerLogout()
        );
        json.add(
                "warningThresholdsMs",
                longs(policy.warningThresholdsMs())
        );
        return json;
    }

    private static TimedSummonPolicy decodePolicy(JsonObject json) {
        return new TimedSummonPolicy(
                optional(json, "configId") == null
                        ? null
                        : optional(json, "configId").getAsString(),
                longOrNull(json, "configRevision"),
                json.get("activeDurationMs").getAsLong(),
                json.get("resummonCooldownMs").getAsLong(),
                json.get("autoStoreOnOwnerLogout").getAsBoolean(),
                readLongs(json.getAsJsonArray("warningThresholdsMs"))
        );
    }

    private static JsonArray longs(Iterable<Long> values) {
        JsonArray json = new JsonArray();
        values.forEach(json::add);
        return json;
    }

    private static ArrayList<Long> readLongs(JsonArray json) {
        ArrayList<Long> values = new ArrayList<>();
        for (JsonElement element : json) {
            values.add(element.getAsLong());
        }
        return values;
    }

    private static Long longOrNull(JsonObject json, String name) {
        JsonElement value = optional(json, name);
        return value == null ? null : value.getAsLong();
    }

    private static JsonElement optional(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, com.google.gson.JsonNull.INSTANCE);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else {
            json.addProperty(name, value.toString());
        }
    }
}
