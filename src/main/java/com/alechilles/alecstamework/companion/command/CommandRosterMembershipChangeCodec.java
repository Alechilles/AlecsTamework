package com.alechilles.alecstamework.companion.command;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Self-contained roster membership change codec and outbox identity. */
public final class CommandRosterMembershipChangeCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("command_roster_membership_changed");

    private CommandRosterMembershipChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull CommandRosterMutationOutcome outcome,
            long changedAtMs
    ) {
        if (operationId == null || outcome == null) {
            throw new IllegalArgumentException(
                    "Command roster event evidence is required"
            );
        }
        CommandRosterMembership membership = outcome.after() == null
                ? outcome.before()
                : outcome.after();
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                membership.profileId().toString(),
                outcome.currentRosterRevision(),
                VERSION,
                encode(outcome),
                changedAtMs
        );
    }

    @Nonnull
    public static String encode(
            @Nonnull CommandRosterMutationOutcome outcome
    ) {
        JsonObject json = new JsonObject();
        json.add(
                "familyKey", family(outcome.familyKey())
        );
        json.addProperty(
                "previousRosterRevision",
                outcome.previousRosterRevision()
        );
        json.addProperty(
                "currentRosterRevision",
                outcome.currentRosterRevision()
        );
        json.add("before", membership(outcome.before()));
        json.add("after", membership(outcome.after()));
        return json.toString();
    }

    @Nonnull
    public static CommandRosterMutationOutcome decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported command roster change payload"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        return new CommandRosterMutationOutcome(
                readFamily(json.getAsJsonObject("familyKey")),
                json.get("previousRosterRevision").getAsLong(),
                json.get("currentRosterRevision").getAsLong(),
                readMembership(json.get("before")),
                readMembership(json.get("after"))
        );
    }

    private static JsonObject family(CommandFamilyKey key) {
        JsonObject json = new JsonObject();
        json.addProperty("ownerId", key.ownerId().toString());
        json.addProperty("familyId", key.familyId());
        return json;
    }

    private static CommandFamilyKey readFamily(JsonObject json) {
        return new CommandFamilyKey(
                OwnerId.parse(json.get("ownerId").getAsString()),
                json.get("familyId").getAsString()
        );
    }

    private static JsonElement membership(
            CommandRosterMembership membership
    ) {
        if (membership == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("slotId", membership.slotId().toString());
        json.add("familyKey", family(membership.familyKey()));
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
        json.add("home", home(membership.home()));
        json.addProperty("createdAtMs", membership.createdAtMs());
        json.addProperty("updatedAtMs", membership.updatedAtMs());
        return json;
    }

    private static CommandRosterMembership readMembership(
            JsonElement element
    ) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        return new CommandRosterMembership(
                CommandRosterSlotId.parse(
                        json.get("slotId").getAsString()
                ),
                readFamily(json.getAsJsonObject("familyKey")),
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("membershipRevision").getAsLong(),
                text(json, "groupId"),
                json.get("activeForBulkCommands").getAsBoolean(),
                readHome(json.get("home")),
                json.get("createdAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
        );
    }

    private static JsonElement home(CommandRosterHome home) {
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

