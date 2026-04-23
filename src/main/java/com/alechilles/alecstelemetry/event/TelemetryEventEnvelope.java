package com.alechilles.alecstelemetry.event;

import com.alechilles.alecstelemetry.crash.CrashReportEnvelope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Serializable generic telemetry event payload persisted to local storage.
 */
public record TelemetryEventEnvelope(int schemaVersion,
                                     @Nonnull String eventType,
                                     @Nonnull String eventName,
                                     @Nonnull String eventId,
                                     @Nonnull String projectId,
                                     @Nonnull String projectDisplayName,
                                     @Nonnull String source,
                                     @Nonnull String sessionId,
                                     @Nonnull String capturedAtUtc,
                                     @Nonnull String pluginIdentifier,
                                     @Nonnull String pluginVersion,
                                     @Nullable String worldName,
                                     @Nonnull String severity,
                                     @Nullable Integer durationMs,
                                     @Nullable Double metricValue,
                                     @Nullable CrashReportEnvelope.EnvironmentSnapshot environment,
                                     @Nonnull Map<String, Object> attributes,
                                     @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_LIFECYCLE = "lifecycle";
    public static final String TYPE_PERFORMANCE = "performance";
    public static final String TYPE_USAGE = "usage";
    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_ERROR = "error";

    @Nonnull
    public static TelemetryEventEnvelope error(@Nonnull String projectId,
                                               @Nonnull String projectDisplayName,
                                               @Nonnull String source,
                                               @Nonnull String sessionId,
                                               @Nonnull String eventName,
                                               @Nonnull String pluginIdentifier,
                                               @Nonnull String pluginVersion,
                                               @Nullable String worldName,
                                               @Nonnull String severity,
                                               @Nonnull CrashReportEnvelope.EnvironmentSnapshot environment,
                                               @Nonnull Map<String, Object> attributes,
                                               @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {
        return create(
                TYPE_ERROR,
                eventName,
                projectId,
                projectDisplayName,
                source,
                sessionId,
                pluginIdentifier,
                pluginVersion,
                worldName,
                severity,
                null,
                null,
                environment,
                attributes,
                runtime
        );
    }

    @Nonnull
    public static TelemetryEventEnvelope lifecycle(@Nonnull String projectId,
                                                   @Nonnull String projectDisplayName,
                                                   @Nonnull String source,
                                                   @Nonnull String sessionId,
                                                   @Nonnull String eventName,
                                                   @Nonnull String pluginIdentifier,
                                                   @Nonnull String pluginVersion,
                                                   @Nullable String worldName,
                                                   boolean success,
                                                   int durationMs,
                                                   @Nonnull CrashReportEnvelope.EnvironmentSnapshot environment,
                                                   @Nonnull Map<String, Object> attributes,
                                                   @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {
        LinkedHashMap<String, Object> withSuccess = new LinkedHashMap<>(attributes);
        withSuccess.putIfAbsent("success", success);
        return create(
                TYPE_LIFECYCLE,
                eventName,
                projectId,
                projectDisplayName,
                source,
                sessionId,
                pluginIdentifier,
                pluginVersion,
                worldName,
                success ? SEVERITY_INFO : SEVERITY_WARNING,
                Math.max(0, durationMs),
                null,
                environment,
                withSuccess,
                runtime
        );
    }

    @Nonnull
    public static TelemetryEventEnvelope performance(@Nonnull String projectId,
                                                     @Nonnull String projectDisplayName,
                                                     @Nonnull String source,
                                                     @Nonnull String sessionId,
                                                     @Nonnull String eventName,
                                                     @Nonnull String pluginIdentifier,
                                                     @Nonnull String pluginVersion,
                                                     @Nullable String worldName,
                                                     int durationMs,
                                                     @Nullable Double metricValue,
                                                     @Nonnull CrashReportEnvelope.EnvironmentSnapshot environment,
                                                     @Nonnull Map<String, Object> attributes,
                                                     @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {
        return create(
                TYPE_PERFORMANCE,
                eventName,
                projectId,
                projectDisplayName,
                source,
                sessionId,
                pluginIdentifier,
                pluginVersion,
                worldName,
                SEVERITY_INFO,
                Math.max(0, durationMs),
                metricValue,
                environment,
                attributes,
                runtime
        );
    }

    @Nonnull
    public static TelemetryEventEnvelope usage(@Nonnull String projectId,
                                               @Nonnull String projectDisplayName,
                                               @Nonnull String source,
                                               @Nonnull String sessionId,
                                               @Nonnull String eventName,
                                               @Nonnull String pluginIdentifier,
                                               @Nonnull String pluginVersion,
                                               @Nullable String worldName,
                                               @Nonnull CrashReportEnvelope.EnvironmentSnapshot environment,
                                               @Nonnull Map<String, Object> attributes,
                                               @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {
        return create(
                TYPE_USAGE,
                eventName,
                projectId,
                projectDisplayName,
                source,
                sessionId,
                pluginIdentifier,
                pluginVersion,
                worldName,
                SEVERITY_INFO,
                null,
                null,
                environment,
                attributes,
                runtime
        );
    }

    @Nonnull
    public static TelemetryEventEnvelope fromJson(@Nonnull String json) {
        TelemetryEventEnvelope parsed = GSON.fromJson(json, TelemetryEventEnvelope.class);
        return parsed == null ? nullEvent() : parsed.normalize();
    }

    @Nonnull
    public String toJson() {
        return GSON.toJson(this) + System.lineSeparator();
    }

    @Nonnull
    private static TelemetryEventEnvelope create(@Nonnull String eventType,
                                                 @Nonnull String eventName,
                                                 @Nonnull String projectId,
                                                 @Nonnull String projectDisplayName,
                                                 @Nonnull String source,
                                                 @Nonnull String sessionId,
                                                 @Nonnull String pluginIdentifier,
                                                 @Nonnull String pluginVersion,
                                                 @Nullable String worldName,
                                                 @Nonnull String severity,
                                                 @Nullable Integer durationMs,
                                                 @Nullable Double metricValue,
                                                 @Nonnull CrashReportEnvelope.EnvironmentSnapshot environment,
                                                 @Nonnull Map<String, Object> attributes,
                                                 @Nonnull CrashReportEnvelope.RuntimeMetadata runtime) {
        return new TelemetryEventEnvelope(
                SCHEMA_VERSION,
                normalizeType(eventType),
                normalizeNonBlank(eventName, "unknown_event"),
                UUID.randomUUID().toString(),
                normalizeNonBlank(projectId, "unknown-project"),
                normalizeNonBlank(projectDisplayName, projectId),
                normalizeNonBlank(source, "runtime"),
                normalizeNonBlank(sessionId, UUID.randomUUID().toString()),
                Instant.now().toString(),
                normalizeNonBlank(pluginIdentifier, "unknown"),
                normalizeNonBlank(pluginVersion, "unknown"),
                normalizeNullable(worldName),
                normalizeSeverity(severity),
                durationMs == null ? null : Math.max(0, durationMs),
                metricValue,
                environment.normalize(),
                normalizeAttributes(attributes),
                runtime.normalize()
        );
    }

    @Nonnull
    private TelemetryEventEnvelope normalize() {
        return new TelemetryEventEnvelope(
                schemaVersion() <= 0 ? SCHEMA_VERSION : schemaVersion(),
                normalizeType(eventType()),
                normalizeNonBlank(eventName(), "unknown_event"),
                normalizeNonBlank(eventId(), UUID.randomUUID().toString()),
                normalizeNonBlank(projectId(), "unknown-project"),
                normalizeNonBlank(projectDisplayName(), projectId()),
                normalizeNonBlank(source(), "runtime"),
                normalizeNonBlank(sessionId(), UUID.randomUUID().toString()),
                normalizeNonBlank(capturedAtUtc(), Instant.now().toString()),
                normalizeNonBlank(pluginIdentifier(), "unknown"),
                normalizeNonBlank(pluginVersion(), "unknown"),
                normalizeNullable(worldName()),
                normalizeSeverity(severity()),
                durationMs() == null ? null : Math.max(0, durationMs()),
                metricValue(),
                environment() == null ? new CrashReportEnvelope.EnvironmentSnapshot("unknown", "unknown", "unknown") : environment().normalize(),
                normalizeAttributes(attributes()),
                runtime() == null ? CrashReportEnvelope.RuntimeMetadata.capture(java.util.List.of()) : runtime().normalize()
        );
    }

    @Nonnull
    private static TelemetryEventEnvelope nullEvent() {
        return create(
                TYPE_ERROR,
                "unknown_event",
                "unknown-project",
                "Unknown Project",
                "runtime",
                UUID.randomUUID().toString(),
                "unknown",
                "unknown",
                null,
                SEVERITY_WARNING,
                null,
                null,
                new CrashReportEnvelope.EnvironmentSnapshot("unknown", "unknown", "unknown"),
                Map.of(),
                CrashReportEnvelope.RuntimeMetadata.capture(java.util.List.of())
        );
    }

    @Nonnull
    private static Map<String, Object> normalizeAttributes(@Nullable Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            normalized.put(entry.getKey().trim(), entry.getValue());
        }
        return Map.copyOf(normalized);
    }

    @Nonnull
    private static String normalizeType(@Nullable String value) {
        String normalized = normalizeNonBlank(value, TYPE_ERROR);
        if (TYPE_LIFECYCLE.equalsIgnoreCase(normalized)) {
            return TYPE_LIFECYCLE;
        }
        if (TYPE_PERFORMANCE.equalsIgnoreCase(normalized)) {
            return TYPE_PERFORMANCE;
        }
        if (TYPE_USAGE.equalsIgnoreCase(normalized)) {
            return TYPE_USAGE;
        }
        return TYPE_ERROR;
    }

    @Nonnull
    private static String normalizeSeverity(@Nullable String value) {
        String normalized = normalizeNonBlank(value, SEVERITY_INFO);
        if (SEVERITY_ERROR.equalsIgnoreCase(normalized)) {
            return SEVERITY_ERROR;
        }
        if (SEVERITY_WARNING.equalsIgnoreCase(normalized)) {
            return SEVERITY_WARNING;
        }
        return SEVERITY_INFO;
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Nonnull
    private static String normalizeNonBlank(@Nullable String value, @Nonnull String fallback) {
        String normalized = normalizeNullable(value);
        return normalized == null ? fallback : normalized;
    }
}
