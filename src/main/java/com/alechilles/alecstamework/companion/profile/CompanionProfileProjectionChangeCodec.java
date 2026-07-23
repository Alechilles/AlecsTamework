package com.alechilles.alecstamework.companion.profile;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Version-one codec and stream identity for self-contained profile change projections. */
public final class CompanionProfileProjectionChangeCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("companion_profile_changed");

    private CompanionProfileProjectionChangeCodec() {
    }

    /** Returns an aggregate stream isolated from every other profile revision domain. */
    @Nonnull
    public static String aggregateId(@Nonnull CompanionProfileProjectionChange change) {
        if (change == null) {
            throw new IllegalArgumentException("Profile projection change is required");
        }
        return "profile-observer-"
                + change.source().name().toLowerCase()
                + ":"
                + change.profileId();
    }

    @Nonnull
    public static String encode(@Nonnull CompanionProfileProjectionChange change) {
        if (change == null) {
            throw new IllegalArgumentException("Profile projection change is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("source", change.source().name());
        json.addProperty("profileId", change.profileId().toString());
        json.addProperty("sourceRevision", change.sourceRevision());
        json.add("before", encodeState(change.before()));
        json.add("after", encodeState(change.after()));
        json.addProperty("changedAtMs", change.changedAtMs());
        return json.toString();
    }

    @Nonnull
    public static CompanionProfileProjectionChange decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION) {
            throw new IllegalArgumentException(
                    "profile_projection_payload_version_unsupported"
            );
        }
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CompanionProfileProjectionChange(
                CompanionProfileProjectionChange.Source.valueOf(
                        json.get("source").getAsString()
                ),
                ProfileId.parse(json.get("profileId").getAsString()),
                json.get("sourceRevision").getAsLong(),
                decodeState(json.get("before")),
                decodeState(json.get("after")),
                json.get("changedAtMs").getAsLong()
        );
    }

    private static JsonElement encodeState(CompanionProfileProjectionState state) {
        if (state == null) {
            return null;
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", state.profileId().toString());
        nullable(json, "currentAlias", text(state.currentAlias()));
        nullable(json, "ownerId", text(state.ownerId()));
        nullable(json, "ownerName", state.ownerName());
        nullable(json, "roleId", state.roleId());
        nullable(json, "displayName", state.displayName());
        nullable(json, "customName", state.customName());
        json.addProperty("tamed", state.tamed());
        nullable(json, "coopId", state.coopId());
        if (state.coopSlot() == null) {
            json.add("coopSlot", null);
        } else {
            json.addProperty("coopSlot", state.coopSlot());
        }
        JsonArray tools = new JsonArray();
        state.toolIds().stream().map(UUID::toString).sorted().forEach(tools::add);
        json.add("toolIds", tools);
        JsonArray snapshots = new JsonArray();
        state.activeSnapshotKinds().stream()
                .map(SnapshotKind::value)
                .sorted()
                .forEach(snapshots::add);
        json.add("activeSnapshotKinds", snapshots);
        json.addProperty("lastUpdatedAtMs", state.lastUpdatedAtMs());
        return json;
    }

    private static CompanionProfileProjectionState decodeState(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        Set<UUID> tools = new LinkedHashSet<>();
        for (JsonElement value : json.getAsJsonArray("toolIds")) {
            tools.add(UUID.fromString(value.getAsString()));
        }
        Set<SnapshotKind> snapshots = new LinkedHashSet<>();
        for (JsonElement value : json.getAsJsonArray("activeSnapshotKinds")) {
            snapshots.add(new SnapshotKind(value.getAsString()));
        }
        String alias = nullableText(json, "currentAlias");
        String owner = nullableText(json, "ownerId");
        JsonElement coopSlot = json.get("coopSlot");
        return new CompanionProfileProjectionState(
                ProfileId.parse(json.get("profileId").getAsString()),
                alias == null ? null : NpcAlias.parse(alias),
                owner == null ? null : OwnerId.parse(owner),
                nullableText(json, "ownerName"),
                nullableText(json, "roleId"),
                nullableText(json, "displayName"),
                nullableText(json, "customName"),
                json.get("tamed").getAsBoolean(),
                nullableText(json, "coopId"),
                coopSlot == null || coopSlot.isJsonNull() ? null : coopSlot.getAsInt(),
                tools,
                snapshots,
                json.get("lastUpdatedAtMs").getAsLong()
        );
    }

    private static void nullable(JsonObject json, String name, String value) {
        if (value == null) {
            json.add(name, null);
        } else {
            json.addProperty(name, value);
        }
    }

    private static String nullableText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
