package com.alechilles.alecstamework.items;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable prepare-time identity and exact source fence carried through capture completion.
 * Direct integrations reuse {@link #forCaller} with the same namespace/key on retry.
 */
public record CaptureAttemptHandle(@Nonnull UUID attemptId,
                                   @Nullable String callerNamespace,
                                   @Nullable String idempotencyKey,
                                   int hotbarSlot,
                                   @Nonnull String sourceFingerprint) {
    private static final String DIRECT_ID_DOMAIN = "tamework:capture-attempt:v1:";

    public CaptureAttemptHandle {
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        callerNamespace = normalize(callerNamespace, 128, "callerNamespace");
        idempotencyKey = normalize(idempotencyKey, 256, "idempotencyKey");
        if ((callerNamespace == null) != (idempotencyKey == null)) {
            throw new IllegalArgumentException(
                    "callerNamespace and idempotencyKey must both be present or absent");
        }
        if (hotbarSlot < 0) throw new IllegalArgumentException("hotbarSlot must be non-negative");
        sourceFingerprint = requireText(sourceFingerprint, 512, "sourceFingerprint");
    }

    /** Allocates one unpredictable identity before a built-in interaction can defer work. */
    @Nonnull
    public static CaptureAttemptHandle forDispatch(
            int hotbarSlot, @Nonnull ItemStack sourceItem) {
        return new CaptureAttemptHandle(
                UUID.randomUUID(), null, null, hotbarSlot,
                SpawnerSourceFingerprint.of(Objects.requireNonNull(sourceItem, "sourceItem")));
    }

    /** Maps a public caller's stable idempotency key to the same attempt on every retry. */
    @Nonnull
    public static CaptureAttemptHandle forCaller(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey,
            int hotbarSlot,
            @Nonnull ItemStack sourceItem) {
        String namespace = requireText(callerNamespace, 128, "callerNamespace");
        String key = requireText(idempotencyKey, 256, "idempotencyKey");
        UUID attemptId = UUID.nameUUIDFromBytes(
                (DIRECT_ID_DOMAIN + namespace + ':' + key).getBytes(StandardCharsets.UTF_8));
        return new CaptureAttemptHandle(
                attemptId, namespace, key, hotbarSlot,
                SpawnerSourceFingerprint.of(Objects.requireNonNull(sourceItem, "sourceItem")));
    }

    /** Bounded durable evidence used to revalidate the exact source after asynchronous hops. */
    @Nonnull
    public String sourceContextJson(@Nonnull String worldName) {
        JsonObject context = new JsonObject();
        context.addProperty("version", 1);
        context.addProperty("world", requireText(worldName, 256, "worldName"));
        context.addProperty("inventory", "hotbar");
        context.addProperty("slot", hotbarSlot);
        context.addProperty("fingerprint", sourceFingerprint);
        return context.toString();
    }

    /** Preserves the prepare-time source and caller evidence when a caller-key replay is adopted. */
    @Nonnull
    public CaptureAttemptHandle withAttemptId(@Nonnull UUID effectiveAttemptId) {
        UUID normalized = Objects.requireNonNull(effectiveAttemptId, "effectiveAttemptId");
        return normalized.equals(attemptId) ? this : new CaptureAttemptHandle(
                normalized, callerNamespace, idempotencyKey, hotbarSlot, sourceFingerprint);
    }

    @Nullable
    private static String normalize(@Nullable String value, int maximum, String field) {
        if (value == null) return null;
        return requireText(value, maximum, field);
    }

    @Nonnull
    private static String requireText(
            @Nonnull String value, int maximum, @Nonnull String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain 1.." + maximum + " characters");
        }
        return normalized;
    }
}
