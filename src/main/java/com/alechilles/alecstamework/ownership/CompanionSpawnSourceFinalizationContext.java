package com.alechilles.alecstamework.ownership;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Describes destructive source cleanup that must finish after a replacement spawn is counted.
 *
 * <p>The descriptor is intentionally evidence-only: runtime code owns the exact CAS mutation,
 * while startup recovery uses the retained descriptor to quarantine an {@code APPLIED} operation
 * instead of silently treating an unconsumed source as complete.</p>
 */
public final class CompanionSpawnSourceFinalizationContext {
    public static final String FIELD = "spawnSourceFinalization";
    private static final int VERSION = 1;

    private CompanionSpawnSourceFinalizationContext() {
    }

    /** Builds a journal-context extension for one replay-safe source finalizer. */
    @Nonnull
    public static String extensionJson(
            @Nonnull Kind kind,
            @Nonnull String finalizationKey,
            @Nonnull UUID sourceNpcUuid,
            @Nullable UUID playerUuid,
            @Nullable Integer hotbarSlot,
            @Nullable String expectedFingerprint,
            @Nullable String replacementFingerprint
    ) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("version", VERSION);
        descriptor.addProperty("kind", Objects.requireNonNull(kind, "kind")
                .name().toLowerCase(Locale.ROOT));
        descriptor.addProperty("finalizationKey", requireText(finalizationKey, "finalizationKey"));
        descriptor.addProperty("sourceNpcUuid", Objects.requireNonNull(sourceNpcUuid, "sourceNpcUuid").toString());
        put(descriptor, "playerUuid", playerUuid);
        if (hotbarSlot == null) {
            descriptor.add("hotbarSlot", null);
        } else {
            if (hotbarSlot < 0) {
                throw new IllegalArgumentException("hotbarSlot must be non-negative.");
            }
            descriptor.addProperty("hotbarSlot", hotbarSlot);
        }
        put(descriptor, "expectedFingerprint", normalize(expectedFingerprint));
        put(descriptor, "replacementFingerprint", normalize(replacementFingerprint));
        JsonObject root = new JsonObject();
        root.add(FIELD, descriptor);
        return root.toString();
    }

    /** Returns the validated descriptor from a full operation context, when present. */
    @Nullable
    public static Descriptor descriptor(@Nullable String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(contextJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Spawn operation context must be an object.");
        }
        JsonElement value = parsed.getAsJsonObject().get(FIELD);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException("Spawn source finalization context must be an object.");
        }
        JsonObject source = value.getAsJsonObject();
        int version = requiredInt(source, "version");
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported spawn source finalization version: " + version);
        }
        Kind kind = Kind.valueOf(requiredString(source, "kind").toUpperCase(Locale.ROOT));
        Integer slot = nullableInt(source, "hotbarSlot");
        if (slot != null && slot < 0) {
            throw new IllegalArgumentException("hotbarSlot must be non-negative.");
        }
        return new Descriptor(
                kind,
                requiredString(source, "finalizationKey"),
                UUID.fromString(requiredString(source, "sourceNpcUuid")),
                nullableUuid(source, "playerUuid"),
                slot,
                nullableString(source, "expectedFingerprint"),
                nullableString(source, "replacementFingerprint")
        );
    }

    public static boolean required(@Nullable String contextJson) {
        return descriptor(contextJson) != null;
    }

    /** Validates an extension before it is merged with reserved spawn fields. */
    public static void validateExtension(@Nullable String extensionJson) {
        if (extensionJson == null || extensionJson.isBlank()) {
            return;
        }
        JsonElement parsed = JsonParser.parseString(extensionJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Durable spawn context must be an object.");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (root.has(FIELD)) {
            descriptor(extensionJson);
        }
    }

    private static int requiredInt(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing spawn source field: " + field);
        }
        return value.getAsInt();
    }

    @Nullable
    private static Integer nullableInt(JsonObject source, String field) {
        JsonElement value = source.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsInt();
    }

    @Nonnull
    private static String requiredString(JsonObject source, String field) {
        String value = nullableString(source, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing spawn source field: " + field);
        }
        return value;
    }

    @Nullable
    private static String nullableString(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return normalize(value.getAsString());
    }

    @Nullable
    private static UUID nullableUuid(JsonObject source, String field) {
        String value = nullableString(source, field);
        return value == null ? null : UUID.fromString(value);
    }

    private static void put(JsonObject target, String field, @Nullable Object value) {
        if (value == null) {
            target.add(field, null);
        } else {
            target.addProperty(field, value.toString());
        }
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nonnull
    private static String requireText(String value, String field) {
        String normalized = normalize(Objects.requireNonNull(value, field));
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return normalized;
    }

    public enum Kind {
        SPAWNER_ITEM,
        DEATH_RECORD,
        LOST_RECORD
    }

    public record Descriptor(@Nonnull Kind kind,
                             @Nonnull String finalizationKey,
                             @Nonnull UUID sourceNpcUuid,
                             @Nullable UUID playerUuid,
                             @Nullable Integer hotbarSlot,
                             @Nullable String expectedFingerprint,
                             @Nullable String replacementFingerprint) {
    }
}
