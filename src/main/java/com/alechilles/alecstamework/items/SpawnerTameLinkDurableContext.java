package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonPolicySnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable roster and timed-lease evidence embedded in capture source context. */
final class SpawnerTameLinkDurableContext {
    private static final String KEY = "tameAndCommandLink";

    private SpawnerTameLinkDurableContext() {
    }

    @Nonnull
    static String merge(@Nonnull String sourceContextJson, @Nonnull Evidence evidence) {
        JsonElement parsed = JsonParser.parseString(sourceContextJson);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("source context must be an object");
        JsonObject root = parsed.getAsJsonObject().deepCopy();
        JsonObject object = new JsonObject();
        object.addProperty("commandFamilyId", evidence.commandFamilyId());
        addNullable(object, "requiredCommandConfigId", evidence.requiredCommandConfigId());
        addNullable(object, "accessItemId", evidence.accessItemId());
        object.addProperty("targetRoleId", evidence.targetRoleId());
        addNullable(object, "timedConfigId", evidence.timedConfigId());
        if (evidence.timedConfigRevision() != null) {
            object.addProperty("timedConfigRevision", evidence.timedConfigRevision());
        }
        object.addProperty("activeDurationMs", evidence.policy().activeDurationMs());
        object.addProperty("resummonCooldownMs", evidence.policy().resummonCooldownMs());
        object.addProperty("autoStoreOnOwnerLogout", evidence.policy().autoStoreOnOwnerLogout());
        JsonArray warnings = new JsonArray();
        for (long threshold : evidence.policy().expiryWarningThresholdsMs()) warnings.add(threshold);
        object.add("expiryWarningThresholdsMs", warnings);
        root.add(KEY, object);
        return root.toString();
    }

    @Nullable
    static Evidence parse(@Nullable String sourceContextJson) {
        try {
            JsonElement parsed = JsonParser.parseString(sourceContextJson);
            JsonObject root = parsed != null && parsed.isJsonObject()
                    ? parsed.getAsJsonObject() : null;
            JsonObject object = root == null || !root.has(KEY)
                    || !root.get(KEY).isJsonObject() ? null : root.getAsJsonObject(KEY);
            if (object == null) return null;
            JsonArray warningArray = object.getAsJsonArray("expiryWarningThresholdsMs");
            long[] warnings = new long[warningArray == null ? 0 : warningArray.size()];
            for (int index = 0; index < warnings.length; index++) {
                warnings[index] = warningArray.get(index).getAsLong();
            }
            return new Evidence(
                    required(object, "commandFamilyId"), nullable(object, "requiredCommandConfigId"),
                    nullable(object, "accessItemId"), required(object, "targetRoleId"),
                    nullable(object, "timedConfigId"), nullableLong(object, "timedConfigRevision"),
                    new CommandTimedSummonPolicySnapshot(
                            object.get("activeDurationMs").getAsLong(),
                            object.get("resummonCooldownMs").getAsLong(),
                            object.get("autoStoreOnOwnerLogout").getAsBoolean(), warnings));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void addNullable(JsonObject object, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) object.addProperty(key, value.trim());
    }

    private static String required(JsonObject object, String key) {
        String value = nullable(object, key);
        if (value == null) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    @Nullable
    private static String nullable(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || value.getAsString().isBlank()
                ? null : value.getAsString().trim();
    }

    @Nullable
    private static Long nullableLong(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    record Evidence(@Nonnull String commandFamilyId,
                    @Nullable String requiredCommandConfigId,
                    @Nullable String accessItemId,
                    @Nonnull String targetRoleId,
                    @Nullable String timedConfigId,
                    @Nullable Long timedConfigRevision,
                    @Nonnull CommandTimedSummonPolicySnapshot policy) {
        Evidence {
            commandFamilyId = require(commandFamilyId, "commandFamilyId");
            requiredCommandConfigId = normalize(requiredCommandConfigId);
            accessItemId = normalize(accessItemId);
            targetRoleId = require(targetRoleId, "targetRoleId");
            timedConfigId = normalize(timedConfigId);
            policy = Objects.requireNonNull(policy, "policy");
            if (timedConfigRevision != null && timedConfigRevision < 0L) {
                throw new IllegalArgumentException("timedConfigRevision cannot be negative");
            }
        }

        private static String require(String value, String field) {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
            return normalized;
        }

        @Nullable
        private static String normalize(@Nullable String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
