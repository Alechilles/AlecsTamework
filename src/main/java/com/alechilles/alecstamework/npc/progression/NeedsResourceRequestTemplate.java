package com.alechilles.alecstamework.npc.progression;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Compatibility adapter for callers that still construct reusable needs-resource request settings.
 *
 * @deprecated Build {@link NeedsResourceSearchCoordinator.Request} directly when a world position is available.
 */
@Deprecated
public final class NeedsResourceRequestTemplate {
    @Nonnull
    private final NeedsResourceSearchCoordinator.Request normalizedSettings;

    private NeedsResourceRequestTemplate(@Nonnull String resourceKind,
                                         double radius,
                                         int verticalRadius,
                                         double consumeRadius,
                                         @Nullable List<String> itemIds) {
        normalizedSettings = NeedsResourceSearchCoordinator.Request.forArea(
                resourceKind,
                "template",
                0.0,
                0.0,
                0.0,
                radius,
                verticalRadius,
                consumeRadius,
                itemIds
        );
    }

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
                itemIds == null ? null : Arrays.asList(Arrays.copyOf(itemIds, itemIds.length))
        );
    }

    @Nonnull
    public static NeedsResourceRequestTemplate from(@Nonnull String resourceKind,
                                                    double radius,
                                                    int verticalRadius,
                                                    double consumeRadius,
                                                    @Nullable List<String> itemIds) {
        return new NeedsResourceRequestTemplate(resourceKind, radius, verticalRadius, consumeRadius, itemIds);
    }

    @Nonnull
    public String resourceKind() {
        return normalizedSettings.resourceKind();
    }

    public double radius() {
        return normalizedSettings.radius();
    }

    public int verticalRadius() {
        return normalizedSettings.verticalRadius();
    }

    public double consumeRadius() {
        return normalizedSettings.consumeRadius();
    }

    @Nonnull
    public List<String> itemIds() {
        return normalizedSettings.itemIds();
    }

    @Nonnull
    NeedsResourceSearchCoordinator.Request requestFor(@Nonnull String worldName,
                                                      double originX,
                                                      double originY,
                                                      double originZ) {
        return NeedsResourceSearchCoordinator.Request.forArea(
                resourceKind(),
                worldName,
                originX,
                originY,
                originZ,
                radius(),
                verticalRadius(),
                consumeRadius(),
                itemIds()
        );
    }

    /** @deprecated Use a sensor-owned request cache. */
    @Deprecated
    public static final class AreaRequestMemo {
        @Nullable
        private volatile Entry entry;

        @Nonnull
        public NeedsResourceSearchCoordinator.Request resolve(
                @Nonnull NeedsResourceRequestTemplate template,
                @Nonnull String worldName,
                double originX,
                double originY,
                double originZ) {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(worldName, "worldName");
            Entry cached = entry;
            if (cached != null && cached.matches(template, worldName, originX, originY, originZ)) {
                return cached.request();
            }
            synchronized (this) {
                cached = entry;
                if (cached != null && cached.matches(template, worldName, originX, originY, originZ)) {
                    return cached.request();
                }
                NeedsResourceSearchCoordinator.Request candidate = template.requestFor(
                        worldName,
                        originX,
                        originY,
                        originZ
                );
                entry = new Entry(template, candidate);
                return candidate;
            }
        }
    }

    private record Entry(@Nonnull NeedsResourceRequestTemplate template,
                         @Nonnull NeedsResourceSearchCoordinator.Request request) {
        private boolean matches(@Nonnull NeedsResourceRequestTemplate candidateTemplate,
                                @Nonnull String worldName,
                                double originX,
                                double originY,
                                double originZ) {
            return template == candidateTemplate
                    && request.matchesArea(worldName, originX, originY, originZ);
        }
    }
}
