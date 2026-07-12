package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Encodes and merges the extensible JSON portion of a canonical NPC profile. */
final class NpcProfileStateJsonCodec {
    private NpcProfileStateJsonCodec() {
    }

    @Nullable
    static String merge(@Nullable String existingJson,
                        @Nonnull NpcProfileRepository.ProfileUpdate update,
                        @Nonnull ProfileOwnerMutation ownerMutation,
                        boolean ownerNameMatchesAuthoritativeOwner) {
        JsonObject merged = parse(existingJson);
        if (merged == null) {
            merged = new JsonObject();
        }
        JsonObject changes = new JsonObject();
        if (ownerMutation.kind() == ProfileOwnerMutation.Kind.SET) {
            // A transfer must not retain the previous owner's display name when the new name is
            // unknown. Remove it first, then add the replacement only when one was supplied.
            merged.remove("owner_name");
            putString(changes, "owner_name", update.ownerName());
        } else if (ownerNameMatchesAuthoritativeOwner) {
            putString(changes, "owner_name", update.ownerName());
        } else if (ownerMutation.kind() == ProfileOwnerMutation.Kind.CLEAR) {
            merged.remove("owner_name");
        }
        putString(changes, "custom_name", update.customName());
        if (update.tamed() != null) {
            changes.addProperty("tamed", update.tamed());
        }
        putString(changes, "coop_id", update.coopId());
        if (update.coopSlot() != null) {
            changes.addProperty("coop_slot", update.coopSlot());
        }
        putString(changes, "profile_json", update.profileJson());
        for (String key : changes.keySet()) {
            merged.add(key, changes.get(key));
        }
        return merged.size() == 0 ? null : merged.toString();
    }

    @Nullable
    static JsonObject parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    static String string(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = value(object, key);
        if (value == null) {
            return null;
        }
        try {
            return trimToNull(value.getAsString());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    static Boolean bool(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = value(object, key);
        if (value == null) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    static Integer integer(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = value(object, key);
        if (value == null) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static JsonElement value(@Nullable JsonObject object, @Nonnull String key) {
        return object == null || !object.has(key) || object.get(key).isJsonNull()
                ? null
                : object.get(key);
    }

    private static void putString(@Nonnull JsonObject object,
                                  @Nonnull String key,
                                  @Nullable String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            object.addProperty(key, normalized);
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
