package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionRequest;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Rechecks one synthesized Recall recovery snapshot inside its write transaction. */
final class SqliteRecallRecoverySnapshotFence {
    static final String UNKNOWN_WORLD = "unknown-recall-source";

    private SqliteRecallRecoverySnapshotFence() {
    }

    static boolean matches(
            SqlitePersistenceTransactionContext transaction,
            CompanionDormantTransitionRequest dormant,
            CompanionLifecycle lifecycle
    ) {
        CompanionIdentity identity = transaction.identities()
                .findProfile(dormant.profileId())
                .orElse(null);
        if (identity == null || identity.roleId() == null
                || lifecycle.ownerId() == null
                || dormant.snapshot().payloadVersion() != 2
                || !dormant.snapshot().payloadHash().matchesUtf8(
                dormant.snapshot().payloadJson()
        ) || !matchesWorld(identity, lifecycle, dormant)) {
            return false;
        }
        try {
            JsonObject payload = JsonParser.parseString(
                    dormant.snapshot().payloadJson()
            ).getAsJsonObject();
            UUID owner = lifecycle.ownerId().value();
            return dormant.source().sourceAlias().toString().equals(
                    text(payload, "npcUuid")
            )
                    && identity.roleId().equalsIgnoreCase(
                    text(payload, "roleId")
            )
                    && owner.toString().equals(
                    nestedText(payload, "owner", "ownerId")
            )
                    && owner.toString().equals(
                    nestedText(payload, "commandLinks", "ownerId")
            )
                    && Boolean.TRUE.equals(
                    nestedBoolean(payload, "tamed", "tamed")
            )
                    && matchesMetadata(identity, payload)
                    && matchesToolIds(transaction, dormant, payload);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean matchesMetadata(
            CompanionIdentity identity,
            JsonObject payload
    ) {
        JsonObject metadata = metadata(identity.metadataJson());
        String ownerName = optionalString(metadata, "owner_name");
        String customName = optionalString(metadata, "custom_name");
        Boolean tamed = optionalBoolean(metadata, "tamed");
        return Objects.equals(
                ownerName,
                nestedText(payload, "owner", "ownerName")
        ) && Objects.equals(
                customName,
                optionalNestedText(payload, "npcName", "name")
        ) && (tamed == null || tamed) == Boolean.TRUE.equals(
                nestedBoolean(payload, "tamed", "tamed")
        );
    }

    private static JsonObject metadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(raw);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Metadata must be an object");
        }
        return parsed.getAsJsonObject();
    }

    private static String optionalString(
            JsonObject root,
            String field
    ) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Metadata text is invalid");
        }
        String decoded = value.getAsString().trim();
        return decoded.isEmpty() ? null : decoded;
    }

    private static Boolean optionalBoolean(
            JsonObject root,
            String field
    ) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Metadata boolean is invalid");
        }
        return value.getAsBoolean();
    }

    private static boolean matchesWorld(
            CompanionIdentity identity,
            CompanionLifecycle lifecycle,
            CompanionDormantTransitionRequest dormant
    ) {
        String expected = normalize(identity.lastKnownWorldKey());
        if (expected == null) {
            expected = normalize(lifecycle.ownerWorldKey());
        }
        if (expected == null) {
            expected = UNKNOWN_WORLD;
        }
        return expected.equals(dormant.source().sourceWorldKey());
    }

    private static boolean matchesToolIds(
            SqlitePersistenceTransactionContext transaction,
            CompanionDormantTransitionRequest dormant,
            JsonObject payload
    ) {
        JsonObject links = object(payload, "commandLinks");
        JsonElement encoded = links.get("toolIds");
        if (encoded == null || !encoded.isJsonArray()) {
            return false;
        }
        Set<UUID> actual = new LinkedHashSet<>();
        for (JsonElement value : encoded.getAsJsonArray()) {
            actual.add(UUID.fromString(value.getAsString()));
        }
        Set<UUID> expected = new LinkedHashSet<>();
        for (CompanionToolLink link : transaction.toolLinks().findByProfile(
                dormant.profileId()
        )) {
            expected.add(link.toolId());
        }
        return actual.equals(expected);
    }

    private static String nestedText(
            JsonObject root,
            String object,
            String field
    ) {
        return text(SqliteRecallRecoverySnapshotFence.object(root, object), field);
    }

    private static String optionalNestedText(
            JsonObject root,
            String object,
            String field
    ) {
        JsonElement nested = root.get(object);
        return nested == null || nested.isJsonNull()
                ? null : text(nested.getAsJsonObject(), field);
    }

    private static Boolean nestedBoolean(
            JsonObject root,
            String object,
            String field
    ) {
        JsonElement value = SqliteRecallRecoverySnapshotFence
                .object(root, object).get(field);
        return value == null || value.isJsonNull()
                ? null : value.getAsBoolean();
    }

    private static JsonObject object(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException("Missing object " + field);
        }
        return value.getAsJsonObject();
    }

    private static String text(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.getAsString();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
