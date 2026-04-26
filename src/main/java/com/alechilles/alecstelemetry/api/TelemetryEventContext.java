package com.alechilles.alecstelemetry.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional typed context attached to non-crash telemetry events.
 */
public record TelemetryEventContext(@Nullable String detail,
                                    @Nullable String severity,
                                    @Nullable String fingerprint,
                                    @Nullable String subsystem,
                                    @Nullable String phase,
                                    @Nullable String operation,
                                    @Nullable String target,
                                    @Nullable String featureKey,
                                    @Nullable String entryPoint,
                                    @Nullable String runtimeSide,
                                    @Nullable String entityType,
                                    @Nullable String itemId,
                                    @Nullable String blockId,
                                    @Nullable String biomeId,
                                    @Nullable String commandName,
                                    @Nullable String worldName,
                                    @Nonnull Map<String, Object> details) {

    private static final int MAX_DETAIL_FIELDS = 20;
    private static final TelemetryEventContext EMPTY = builder().build();

    @Nonnull
    public static TelemetryEventContext empty() {
        return EMPTY;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public static Builder error() {
        return builder().severity("error");
    }

    @Nonnull
    public static Builder lifecycle() {
        return builder();
    }

    @Nonnull
    public static Builder performance() {
        return builder();
    }

    @Nonnull
    public static Builder usage() {
        return builder();
    }

    @Nonnull
    public TelemetryEventContext normalize() {
        return new TelemetryEventContext(
                normalizeNullable(detail, 500),
                normalizeNullable(severity, 32),
                normalizeNullable(fingerprint, 200),
                normalizeNullable(subsystem, 120),
                normalizeNullable(phase, 120),
                normalizeNullable(operation, 120),
                normalizeNullable(target, 200),
                normalizeNullable(featureKey, 120),
                normalizeNullable(entryPoint, 120),
                normalizeNullable(runtimeSide, 80),
                normalizeNullable(entityType, 120),
                normalizeNullable(itemId, 160),
                normalizeNullable(blockId, 160),
                normalizeNullable(biomeId, 160),
                normalizeNullable(commandName, 120),
                normalizeNullable(worldName, 200),
                normalizeDetails(details)
        );
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    @Nonnull
    private static Map<String, Object> normalizeDetails(@Nullable Map<String, Object> rawDetails) {
        if (rawDetails == null || rawDetails.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawDetails.entrySet()) {
            if (normalized.size() >= MAX_DETAIL_FIELDS || entry == null) {
                break;
            }
            String key = normalizeNullable(entry.getKey(), 80);
            Object value = normalizeDetailValue(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return Map.copyOf(normalized);
    }

    @Nullable
    private static Object normalizeDetailValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        if (value instanceof CharSequence text) {
            return normalizeNullable(text.toString(), 500);
        }
        return null;
    }

    public static final class Builder {
        private final LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        private String detail;
        private String severity;
        private String fingerprint;
        private String subsystem;
        private String phase;
        private String operation;
        private String target;
        private String featureKey;
        private String entryPoint;
        private String runtimeSide;
        private String entityType;
        private String itemId;
        private String blockId;
        private String biomeId;
        private String commandName;
        private String worldName;

        private Builder() {
        }

        @Nonnull
        public Builder detail(@Nullable String detail) {
            this.detail = detail;
            return this;
        }

        @Nonnull
        public Builder detail(@Nonnull String key, @Nullable Object value) {
            details.put(key, value);
            return this;
        }

        @Nonnull
        public Builder severity(@Nullable String severity) {
            this.severity = severity;
            return this;
        }

        @Nonnull
        public Builder fingerprint(@Nullable String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        @Nonnull
        public Builder subsystem(@Nullable String subsystem) {
            this.subsystem = subsystem;
            return this;
        }

        @Nonnull
        public Builder phase(@Nullable String phase) {
            this.phase = phase;
            return this;
        }

        @Nonnull
        public Builder operation(@Nullable String operation) {
            this.operation = operation;
            return this;
        }

        @Nonnull
        public Builder target(@Nullable String target) {
            this.target = target;
            return this;
        }

        @Nonnull
        public Builder featureKey(@Nullable String featureKey) {
            this.featureKey = featureKey;
            return this;
        }

        @Nonnull
        public Builder entryPoint(@Nullable String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }

        @Nonnull
        public Builder runtimeSide(@Nullable String runtimeSide) {
            this.runtimeSide = runtimeSide;
            return this;
        }

        @Nonnull
        public Builder entityType(@Nullable String entityType) {
            this.entityType = entityType;
            return this;
        }

        @Nonnull
        public Builder itemId(@Nullable String itemId) {
            this.itemId = itemId;
            return this;
        }

        @Nonnull
        public Builder blockId(@Nullable String blockId) {
            this.blockId = blockId;
            return this;
        }

        @Nonnull
        public Builder biomeId(@Nullable String biomeId) {
            this.biomeId = biomeId;
            return this;
        }

        @Nonnull
        public Builder commandName(@Nullable String commandName) {
            this.commandName = commandName;
            return this;
        }

        @Nonnull
        public Builder worldName(@Nullable String worldName) {
            this.worldName = worldName;
            return this;
        }

        @Nonnull
        public TelemetryEventContext build() {
            return new TelemetryEventContext(
                    detail,
                    severity,
                    fingerprint,
                    subsystem,
                    phase,
                    operation,
                    target,
                    featureKey,
                    entryPoint,
                    runtimeSide,
                    entityType,
                    itemId,
                    blockId,
                    biomeId,
                    commandName,
                    worldName,
                    Map.copyOf(details)
            ).normalize();
        }
    }
}
