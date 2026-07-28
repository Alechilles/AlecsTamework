package com.alechilles.alecstamework.companion.identity;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Validating JSON translation for identity operation and projection payloads. */
public final class CompanionIdentityJsonCodec {
    private CompanionIdentityJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull CompanionIdentity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("Companion identity is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", identity.profileId().toString());
        nullable(json, "displayName", identity.displayName());
        nullable(json, "roleId", identity.roleId());
        nullable(json, "metadataJson", identity.metadataJson());
        nullable(json, "metadataHash", text(identity.metadataHash()));
        nullable(json, "lastKnownWorldKey", identity.lastKnownWorldKey());
        json.addProperty("createdAtMs", identity.createdAtMs());
        json.addProperty("updatedAtMs", identity.updatedAtMs());
        json.addProperty("lastActiveAtMs", identity.lastActiveAtMs());
        json.addProperty("metadataRevision", identity.metadataRevision());
        return json;
    }

    @Nonnull
    public static CompanionIdentity decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("Companion identity JSON is required");
        }
        String metadataHash = nullableText(json, "metadataHash");
        return new CompanionIdentity(
                ProfileId.parse(json.get("profileId").getAsString()),
                nullableText(json, "displayName"),
                nullableText(json, "roleId"),
                nullableText(json, "metadataJson"),
                metadataHash == null ? null : Sha256Hash.parse(metadataHash),
                nullableText(json, "lastKnownWorldKey"),
                json.get("createdAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong(),
                json.get("lastActiveAtMs").getAsLong(),
                json.get("metadataRevision").getAsLong()
        );
    }

    @Nonnull
    public static JsonObject encodeToolLink(@Nonnull CompanionToolLink link) {
        if (link == null) {
            throw new IllegalArgumentException("Companion tool link is required");
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", link.profileId().toString());
        json.addProperty("toolId", link.toolId().toString());
        json.addProperty("linkType", link.linkType());
        json.addProperty("createdAtMs", link.createdAtMs());
        json.addProperty("updatedAtMs", link.updatedAtMs());
        return json;
    }

    @Nonnull
    public static CompanionToolLink decodeToolLink(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("Companion tool-link JSON is required");
        }
        return new CompanionToolLink(
                ProfileId.parse(json.get("profileId").getAsString()),
                java.util.UUID.fromString(json.get("toolId").getAsString()),
                json.get("linkType").getAsString(),
                json.get("createdAtMs").getAsLong(),
                json.get("updatedAtMs").getAsLong()
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
