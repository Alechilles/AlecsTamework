package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one operation definition for command roster membership mutations. */
public final class CommandRosterMembershipDefinition
        implements OperationDefinition<CommandRosterMembershipRequest> {
    public static final CommandRosterMembershipDefinition INSTANCE =
            new CommandRosterMembershipDefinition();
    public static final OperationKind KIND =
            new OperationKind("command_roster_membership");

    private CommandRosterMembershipDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 1;
    }

    @Override
    public Class<CommandRosterMembershipRequest> payloadType() {
        return CommandRosterMembershipRequest.class;
    }

    @Override
    public String encode(CommandRosterMembershipRequest payload) {
        JsonObject json = new JsonObject();
        json.addProperty("action", payload.action().name());
        json.addProperty("profileId", payload.profileId().toString());
        json.addProperty(
                "ownerId", payload.familyKey().ownerId().toString()
        );
        json.addProperty("familyId", payload.familyKey().familyId());
        json.addProperty("slotId", payload.slotId().toString());
        json.addProperty(
                "expectedRosterRevision",
                payload.expectedRosterRevision()
        );
        nullable(
                json,
                "expectedMembershipRevision",
                payload.expectedMembershipRevision()
        );
        json.addProperty(
                "expectedMetadataRevision",
                payload.expectedMetadataRevision()
        );
        json.addProperty("expectedRoleId", payload.expectedRoleId());
        json.addProperty(
                "expectedLifecycleRevision",
                payload.expectedLifecycleRevision().value()
        );
        nullable(
                json,
                "expectedOwnerWorldKey",
                payload.expectedOwnerWorldKey()
        );
        nullable(json, "groupId", payload.groupId());
        json.addProperty(
                "activeForBulkCommands",
                payload.activeForBulkCommands()
        );
        json.add("home", home(payload.home()));
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CommandRosterMembershipRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        return new CommandRosterMembershipRequest(
                CommandRosterMembershipRequest.Action.valueOf(
                        json.get("action").getAsString()
                ),
                ProfileId.parse(json.get("profileId").getAsString()),
                new CommandFamilyKey(
                        OwnerId.parse(
                                json.get("ownerId").getAsString()
                        ),
                        json.get("familyId").getAsString()
                ),
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                json.get("expectedRosterRevision").getAsLong(),
                nullableLong(json, "expectedMembershipRevision"),
                json.get("expectedMetadataRevision").getAsLong(),
                json.get("expectedRoleId").getAsString(),
                new LifecycleRevision(
                        json.get("expectedLifecycleRevision").getAsLong()
                ),
                nullableText(json, "expectedOwnerWorldKey"),
                nullableText(json, "groupId"),
                json.get("activeForBulkCommands").getAsBoolean(),
                readHome(json.get("home")),
                json.get("requestedAtMs").getAsLong()
        );
    }

    private JsonElement home(CommandRosterHome home) {
        if (home == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("worldKey", home.worldKey());
        json.addProperty("x", home.x());
        json.addProperty("y", home.y());
        json.addProperty("z", home.z());
        return json;
    }

    private CommandRosterHome readHome(JsonElement element) {
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

    private void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private String nullableText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }

    private Long nullableLong(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsLong();
    }
}

