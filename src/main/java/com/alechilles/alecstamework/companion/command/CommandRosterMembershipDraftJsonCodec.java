package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Shared validating JSON translation for desired command membership. */
public final class CommandRosterMembershipDraftJsonCodec {
    private CommandRosterMembershipDraftJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull CommandRosterMembershipDraft draft
    ) {
        if (draft == null) {
            throw new IllegalArgumentException(
                    "Command roster membership draft is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("slotId", draft.slotId().toString());
        json.addProperty(
                "ownerId", draft.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", draft.familyKey().familyId());
        json.addProperty("profileId", draft.profileId().toString());
        nullable(json, "groupId", draft.groupId());
        json.addProperty(
                "activeForBulkCommands",
                draft.activeForBulkCommands()
        );
        json.add("home", home(draft.home()));
        json.addProperty("changedAtMs", draft.changedAtMs());
        return json;
    }

    @Nonnull
    public static CommandRosterMembershipDraft decode(
            @Nonnull JsonObject json
    ) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Command roster membership draft JSON is required"
            );
        }
        return new CommandRosterMembershipDraft(
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                new CommandFamilyKey(
                        OwnerId.parse(
                                json.get("ownerId").getAsString()
                        ),
                        json.get("familyId").getAsString()
                ),
                ProfileId.parse(json.get("profileId").getAsString()),
                text(json, "groupId"),
                json.get("activeForBulkCommands").getAsBoolean(),
                readHome(json.get("home")),
                json.get("changedAtMs").getAsLong()
        );
    }

    private static JsonElement home(CommandRosterHome value) {
        if (value == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("worldKey", value.worldKey());
        json.addProperty("x", value.x());
        json.addProperty("y", value.y());
        json.addProperty("z", value.z());
        return json;
    }

    private static CommandRosterHome readHome(JsonElement element) {
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
            json.add(name, null);
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

