package com.alechilles.alecstamework.npc.progression;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable normalized settings for one needs-resource search family.
 *
 * <p>The template owns the values that stay constant while a sensor scans
 * nearby positions. A memo can then reuse one immutable area request while
 * the world and the four-block search cell stay unchanged.</p>
 */
public final class NeedsResourceRequestTemplate {
    @Nonnull
    private final String resourceKind;
    private final double radius;
    private final int verticalRadius;
    private final double consumeRadius;
    @Nonnull
    private final List<String> itemIds;

    private NeedsResourceRequestTemplate(@Nonnull String resourceKind,
                                         double radius,
                                         int verticalRadius,
                                         double consumeRadius,
                                         @Nonnull List<String> itemIds) {
        this.resourceKind = NeedsResourceSearchCoordinator.normalizeResourceKind(resourceKind);
        this.radius = requirePositiveFinite(
                NeedsResourceSearchCachePolicy.boundedSearchRadius(radius),
                "radius"
        );
        this.verticalRadius = NeedsResourceSearchCachePolicy.boundedVerticalScanRadius(verticalRadius);
        this.consumeRadius = requireNonNegativeFinite(
                NeedsResourceSearchCachePolicy.boundedConsumeRadius(consumeRadius),
                "consumeRadius"
        );
        this.itemIds = this.resourceKind.equals(NeedsResourceSearchCoordinator.RESOURCE_KIND_WATER)
                ? List.of()
                : List.copyOf(itemIds);
    }

    /**
     * Creates a template from caller-owned item IDs. The array is copied and
     * canonicalized before it is retained.
     */
    @Nonnull
    public static NeedsResourceRequestTemplate from(@Nonnull String resourceKind,
                                                    double radius,
                                                    int verticalRadius,
                                                    double consumeRadius,
                                                    @Nullable String[] itemIds) {
        return new NeedsResourceRequestTemplate(
                resourceKind,
                radius,
                verticalRadius,
                consumeRadius,
                canonicalItemIds(itemIds)
        );
    }

    /**
     * Creates a template from a caller collection. Null and unusable IDs are
     * treated as an empty list for compatibility with request construction.
     */
    @Nonnull
    public static NeedsResourceRequestTemplate from(@Nonnull String resourceKind,
                                                    double radius,
                                                    int verticalRadius,
                                                    double consumeRadius,
                                                    @Nullable List<String> itemIds) {
        return new NeedsResourceRequestTemplate(
                resourceKind,
                radius,
                verticalRadius,
                consumeRadius,
                canonicalItemIds(itemIds)
        );
    }

    @Nonnull
    public String resourceKind() {
        return resourceKind;
    }

    public double radius() {
        return radius;
    }

    public int verticalRadius() {
        return verticalRadius;
    }

    public double consumeRadius() {
        return consumeRadius;
    }

    /** Returns the canonical immutable food-ID list. */
    @Nonnull
    public List<String> itemIds() {
        return itemIds;
    }

    /**
     * Builds one request for a world position. The area-key factory trusts the
     * template's canonical list and does not normalize it again.
     */
    @Nonnull
    NeedsResourceSearchCoordinator.Request requestFor(@Nonnull String worldName,
                                                      double originX,
                                                      double originY,
                                                      double originZ) {
        return NeedsResourceSearchCoordinator.Request.fromTemplate(
                this,
                worldName,
                originX,
                originY,
                originZ
        );
    }

    private static List<String> canonicalItemIds(@Nullable String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return List.of();
        }
        TreeSet<String> canonical = new TreeSet<>();
        for (String itemId : itemIds) {
            addCanonicalItemId(canonical, itemId);
        }
        return List.copyOf(canonical);
    }

    @Nonnull
    private static List<String> canonicalItemIds(@Nullable List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        TreeSet<String> canonical = new TreeSet<>();
        for (String itemId : itemIds) {
            addCanonicalItemId(canonical, itemId);
        }
        return List.copyOf(canonical);
    }

    private static void addCanonicalItemId(@Nonnull TreeSet<String> canonical,
                                           @Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        canonical.add(itemId.trim().toLowerCase(Locale.ROOT));
    }

    private static double requirePositiveFinite(double value, @Nonnull String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
        return value;
    }

    private static double requireNonNegativeFinite(double value, @Nonnull String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }

    /**
     * Thread-safe one-entry request memo used by one sensor instance. The
     * entry is volatile for warm reads; synchronization is only needed when a
     * key changes and a new immutable request must be constructed.
     */
    public static final class AreaRequestMemo {
        @Nullable
        private volatile Entry entry;

        /**
         * Returns the cached request for the same template, normalized world,
         * and four-block X/Y/Z cell, or creates the next entry.
         */
        @Nonnull
        public NeedsResourceSearchCoordinator.Request resolve(
                @Nonnull NeedsResourceRequestTemplate template,
                @Nonnull String worldName,
                double originX,
                double originY,
                double originZ) {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(worldName, "worldName");
            String normalizedWorld = NeedsResourceAreaSearchCache.AreaKey.normalizeWorldName(worldName);
            if (normalizedWorld.isBlank()) {
                throw new IllegalArgumentException("worldName cannot be blank");
            }
            if (!NeedsResourceSearchCachePolicy.hasSafeOrigin(
                    originX,
                    originY,
                    originZ,
                    Math.max(1, (int) Math.ceil(template.radius())),
                    template.verticalRadius()
            )) {
                throw new IllegalArgumentException("request bounds are invalid");
            }
            int cellX = NeedsResourceAreaSearchCache.AreaKey.cellFor(originX);
            int cellY = NeedsResourceAreaSearchCache.AreaKey.cellFor(originY);
            int cellZ = NeedsResourceAreaSearchCache.AreaKey.cellFor(originZ);
            Entry cached = entry;
            if (cached != null && cached.matches(template, normalizedWorld, cellX, cellY, cellZ)) {
                return cached.request();
            }
            synchronized (this) {
                cached = entry;
                if (cached != null && cached.matches(template, normalizedWorld, cellX, cellY, cellZ)) {
                    return cached.request();
                }
                NeedsResourceSearchCoordinator.Request request = template.requestFor(
                        normalizedWorld,
                        originX,
                        originY,
                        originZ
                );
                entry = new Entry(template, normalizedWorld, cellX, cellY, cellZ, request);
                return request;
            }
        }
    }

    private record Entry(@Nonnull NeedsResourceRequestTemplate template,
                         @Nonnull String worldName,
                         int cellX,
                         int cellY,
                         int cellZ,
                         @Nonnull NeedsResourceSearchCoordinator.Request request) {
        private boolean matches(@Nonnull NeedsResourceRequestTemplate candidateTemplate,
                                @Nonnull String candidateWorldName,
                                int candidateCellX,
                                int candidateCellY,
                                int candidateCellZ) {
            return template == candidateTemplate
                    && worldName.equals(candidateWorldName)
                    && cellX == candidateCellX
                    && cellY == candidateCellY
                    && cellZ == candidateCellZ;
        }
    }
}
