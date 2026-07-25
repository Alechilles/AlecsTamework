package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Canonical JSON shape shared by roster operation and semantic event codecs. */
public final class CommandRosterMembershipJsonCodec {
    private CommandRosterMembershipJsonCodec() {
    }

    @Nonnull
    public static JsonObject encodeFamily(@Nonnull CommandFamilyKey key) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "Command family key is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("ownerId", key.ownerId().toString());
        json.addProperty("familyId", key.familyId());
        return json;
    }

    @Nonnull
    public static CommandFamilyKey decodeFamily(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Command family JSON is required"
            );
        }
        return new CommandFamilyKey(
                OwnerId.parse(json.get("ownerId").getAsString()),
                json.get("familyId").getAsString()
        );
    }

    @Nonnull
    public static JsonElement encode(
            @Nullable CommandRosterMembership membership
    ) {
        if (membership == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("slotId", membership.slotId().toString());
        json.add(
                "familyKey", encodeFamily(membership.familyKey())
        );
        json.addProperty(
                "profileId", membership.profileId().toString()
        );
        json.addProperty(
                "membershipRevision",
                membership.membershipRevision()
        );
        nullable(json, "groupId", membership.groupId());
        json.addProperty(
                "activeForBulkCommands",
                membership.activeForBulkCommands()
        );
        json.add("home", encodeHome(membership.home()));
        json.addProperty("createdAtMs", membership.createdAtMs());
        json.addProperty("updatedAtMs", membership.updatedAtMs());
        return json;
    }

    @Nullable
    public static CommandRosterMembership decode(
            @Nullable JsonElement element
    ) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        return new CommandRosterMembership(
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                decodeFamily(json.getAsJsonObject("familyKey")),
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("membershipRevision").getAsLong(),
                text(json, "groupId"),
                json.get("activeForBulkCommands").getAsBoolean(),
                decodeHome(json.get("home")),
                json.get("createdAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }

    private static JsonElement encodeHome(CommandRosterHome home) {
        if (home == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("worldKey", home.worldKey());
        json.addProperty("x", home.x());
        json.addProperty("y", home.y());
        json.addProperty("z", home.z());
        return json;
    }

    private static CommandRosterHome decodeHome(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        return new CommandRosterHome(
                json.get("worldKey").getAsString(),
                json.get("x").getAsDouble(),
                json.get("y").getAsDouble(),
                json.get("z").getAsDouble()
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            String value
    ) {
        if (value == null) {
            json.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            json.addProperty(name, value);
        }
    }

    private static String text(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }
}
