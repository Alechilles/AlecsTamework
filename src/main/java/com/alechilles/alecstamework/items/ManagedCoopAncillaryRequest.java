package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable managed-coop produce and interaction request safe to retain across an async boundary.
 *
 * <p>The request deliberately copies config scalars and role mappings. It never retains the live
 * {@link ManagedCoopContext}, world, store, block component, or item container discovered during a
 * sweep.</p>
 */
public record ManagedCoopAncillaryRequest(
        @Nonnull ManagedCoopAuthorityKey authorityKey,
        @Nonnull String coopId,
        int maxResidents,
        @Nonnull Map<String, String> dropsByRole,
        int intervalGameHours,
        int itemsPerTick,
        long gameTimeMs) {

    public ManagedCoopAncillaryRequest {
        Objects.requireNonNull(authorityKey, "authorityKey");
        coopId = normalizeRequired(coopId, "coopId");
        if (maxResidents < 1) {
            throw new IllegalArgumentException("maxResidents must be positive");
        }
        dropsByRole = normalizedDrops(dropsByRole);
        if (intervalGameHours < 1) {
            throw new IllegalArgumentException("intervalGameHours must be positive");
        }
        if (itemsPerTick < 1) {
            throw new IllegalArgumentException("itemsPerTick must be positive");
        }
    }

    /** Copies all ancillary inputs while the managed-coop context is still on its owning thread. */
    @Nonnull
    public static ManagedCoopAncillaryRequest copyOf(
            @Nonnull ManagedCoopContext context,
            long gameTimeMs) {
        Objects.requireNonNull(context, "context");
        TwCoopConfig.LifecycleRules lifecycle = context.config().getLifecycleRules();
        TwCoopConfig.ProduceRules produce = context.config().getProduceRules();
        return new ManagedCoopAncillaryRequest(
                context.authorityKey(),
                context.coopId(),
                lifecycle.getMaxResidents(),
                produce.getDropsByRole(),
                produce.getIntervalGameHours(),
                produce.getItemsPerTick(),
                gameTimeMs
        );
    }

    @Nonnull
    public String coopKey() {
        return authorityKey.authorityId() + "|coop=" + coopId;
    }

    @Nonnull
    private static Map<String, String> normalizedDrops(@Nullable Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String roleId = normalizeNullable(entry.getKey());
            String dropId = entry.getValue();
            if (roleId != null && dropId != null && !dropId.isBlank()) {
                result.put(roleId, dropId.trim());
            }
        }
        return Map.copyOf(result);
    }

    @Nonnull
    private static String normalizeRequired(@Nullable String value, String field) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    @Nullable
    static String normalizeNullable(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
