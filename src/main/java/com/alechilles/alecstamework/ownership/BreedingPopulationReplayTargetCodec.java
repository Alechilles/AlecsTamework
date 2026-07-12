package com.alechilles.alecstamework.ownership;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Decodes durable breeding target metadata while retaining legacy exact-attempt rows. */
final class BreedingPopulationReplayTargetCodec {
    private BreedingPopulationReplayTargetCodec() {
    }

    @Nullable
    static Target decode(@Nullable String targetContextJson) {
        if (targetContextJson == null || targetContextJson.isBlank()) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(targetContextJson).getAsJsonObject();
            String attemptKey = requiredText(json, "idempotencyKey");
            String childKey = requiredText(json, "childKey");
            UUID npcUuid = UUID.fromString(requiredText(json, "plannedNpcUuid"));
            String worldName = optionalText(json, "world");
            List<String> parentProfileIds = parseParentProfileIds(json);
            if (!parentProfileIds.isEmpty() && worldName == null) {
                throw new IllegalArgumentException(
                        "Pair-indexed breeding evidence requires a world."
                );
            }
            return new Target(
                    attemptKey,
                    childKey,
                    npcUuid,
                    json.get("birthPlan"),
                    parentProfileIds,
                    worldName
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nonnull
    private static List<String> parseParentProfileIds(@Nonnull JsonObject json) {
        JsonElement element = json.get("parentProfileIds");
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<String> profileIds = new ArrayList<>(array.size());
        for (JsonElement profileId : array) {
            profileIds.add(profileId.getAsString());
        }
        List<String> normalized = BreedingPopulationAdmissionRequest.normalizeParentProfileIds(
                profileIds
        );
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Parent profile metadata cannot be empty.");
        }
        return normalized;
    }

    @Nullable
    private static String optionalText(@Nonnull JsonObject json, @Nonnull String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull()
                ? null
                : requireText(element.getAsString(), key);
    }

    @Nonnull
    private static String requiredText(@Nonnull JsonObject json, @Nonnull String key) {
        String value = optionalText(json, key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    @Nonnull
    private static String requireText(@Nonnull String value, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " cannot be blank.");
        }
        return normalized;
    }

    record Target(
            @Nonnull String attemptKey,
            @Nonnull String childKey,
            @Nonnull UUID plannedNpcUuid,
            @Nullable JsonElement planElement,
            @Nonnull List<String> parentProfileIds,
            @Nullable String worldName
    ) {
    }
}
