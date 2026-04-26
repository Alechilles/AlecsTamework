package com.alechilles.alecstelemetry.project;

import com.alechilles.alecstelemetry.crash.CrashReportClient;
import com.alechilles.alecstelemetry.runtime.TelemetryRuntimeSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Declarative project registration loaded from `telemetry/project.json`.
 */
public record TelemetryProjectDescriptor(int schemaVersion,
                                         @Nonnull String projectId,
                                         @Nonnull String displayName,
                                         @Nonnull String runtimeMode,
                                         @Nonnull List<String> ownerPluginIdentifiers,
                                         @Nonnull List<String> packagePrefixes,
                                         @Nonnull CaptureOptions capture,
                                         @Nonnull EventOptions events,
                                         @Nonnull PerformanceOptions performance,
                                         @Nonnull UsageOptions usage,
                                         @Nonnull Defaults defaults,
                                         @Nonnull HostedDestination hosted,
                                         @Nonnull CustomEndpoint customEndpoint) {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int CURRENT_SCHEMA_VERSION = 1;
    private static final String MODE_HOSTED = "hosted";
    private static final String MODE_CUSTOM = "custom";
    public static final String RUNTIME_MODE_DEPENDENCY = "dependency";
    public static final String RUNTIME_MODE_EMBEDDED = "embedded";
    public static final String PROJECT_KEY_HEADER = "X-Telemetry-Project-Key";

    @Nonnull
    public static TelemetryProjectDescriptor fromJson(@Nonnull String rawJson, @Nullable Fallbacks fallbacks) {
        Document parsed = GSON.fromJson(rawJson, Document.class);
        Document safe = parsed == null ? new Document() : parsed;

        String fallbackProjectId = fallbacks == null ? null : fallbacks.projectId();
        String fallbackDisplayName = fallbacks == null ? null : fallbacks.displayName();
        String fallbackOwnerPluginIdentifier = fallbacks == null ? null : fallbacks.ownerPluginIdentifier();
        List<String> fallbackPackagePrefixes = fallbacks == null ? List.of() : fallbacks.packagePrefixes();

        String displayName = chooseNonBlank(safe.displayName, fallbackDisplayName, "Unknown Project");
        String projectId = chooseNonBlank(safe.projectId, fallbackProjectId, slugify(displayName));

        List<String> ownerPluginIdentifiers = normalizeList(safe.ownerPluginIdentifiers);
        if (ownerPluginIdentifiers.isEmpty() && fallbackOwnerPluginIdentifier != null && !fallbackOwnerPluginIdentifier.isBlank()) {
            ownerPluginIdentifiers = List.of(fallbackOwnerPluginIdentifier.trim());
        }

        CaptureOptions capture = safe.capture == null
                ? new CaptureOptions(true, true, true, true)
                : new CaptureOptions(
                boolOrDefault(safe.capture.uncaughtExceptions, true),
                boolOrDefault(safe.capture.setupFailures, true),
                boolOrDefault(safe.capture.startFailures, true),
                boolOrDefault(safe.capture.exceptionalWorldRemovals, true)
        );

        String destinationMode = normalizeMode(
                safe.defaults == null ? null : safe.defaults.destinationMode,
                safe.customEndpoint == null ? null : safe.customEndpoint.url
        );

        Defaults defaults = new Defaults(
                safe.defaults == null || safe.defaults.enabled == null || safe.defaults.enabled,
                destinationMode
        );

        PerformanceOptions performance = safe.performance == null
                ? new PerformanceOptions(false, 1.0d, 100, Map.of())
                : new PerformanceOptions(
                boolOrDefault(safe.performance.enabled, false),
                doubleOrDefault(safe.performance.sampleRate, 1.0d, 0.0d, 1.0d),
                intOrDefault(safe.performance.thresholdMs, 100, 1, 60000),
                normalizeDetailRules(safe.performance.details)
        );

        UsageOptions usage = safe.usage == null
                ? new UsageOptions(false, List.of(), Map.of())
                : new UsageOptions(
                boolOrDefault(safe.usage.enabled, false),
                normalizeNonBlankList(safe.usage.allowedEvents, 120),
                normalizeDetailRules(safe.usage.details)
        );

        EventOptions events = safe.events == null
                ? EventOptions.defaults()
                : new EventOptions(
                new EventTypeOptions(safe.events.errors == null || boolOrDefault(safe.events.errors.enabled, true)),
                new EventTypeOptions(safe.events.lifecycle == null || boolOrDefault(safe.events.lifecycle.enabled, true)),
                safe.events.breadcrumbs == null
                        ? new BreadcrumbOptions(true, true)
                        : new BreadcrumbOptions(
                        boolOrDefault(safe.events.breadcrumbs.enabled, true),
                        boolOrDefault(safe.events.breadcrumbs.automatic, true)
                )
        );

        HostedDestination hosted = new HostedDestination(
                normalizeNullable(safe.hosted == null ? null : safe.hosted.endpoint),
                normalizeNullable(safe.hosted == null ? null : safe.hosted.eventEndpoint),
                normalizeNullable(safe.hosted == null ? null : safe.hosted.projectKey),
                normalizeHeaders(safe.hosted == null ? null : safe.hosted.headers)
        );
        CustomEndpoint customEndpoint = new CustomEndpoint(
                normalizeNullable(safe.customEndpoint == null ? null : safe.customEndpoint.url),
                normalizeNullable(safe.customEndpoint == null ? null : safe.customEndpoint.eventUrl),
                normalizeHeaders(safe.customEndpoint == null ? null : safe.customEndpoint.headers)
        );

        List<String> packagePrefixes = normalizeList(safe.packagePrefixes);
        if (packagePrefixes.isEmpty() && fallbackPackagePrefixes != null && !fallbackPackagePrefixes.isEmpty()) {
            packagePrefixes = normalizeList(fallbackPackagePrefixes);
        }

        return new TelemetryProjectDescriptor(
                safe.schemaVersion == null || safe.schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : safe.schemaVersion,
                projectId,
                displayName,
                normalizeRuntimeMode(safe.runtimeMode),
                ownerPluginIdentifiers,
                packagePrefixes,
                capture,
                events,
                performance,
                usage,
                defaults,
                hosted,
                customEndpoint
        );
    }

    @Nullable
    public CrashReportClient.DeliveryTarget resolveDeliveryTarget(@Nonnull TelemetryRuntimeSettings settings) {
        if (!defaults.enabled()) {
            return null;
        }
        if (MODE_CUSTOM.equals(defaults.destinationMode())) {
            if (customEndpoint.url() == null || customEndpoint.url().isBlank()) {
                return null;
            }
            return new CrashReportClient.DeliveryTarget(customEndpoint.url(), customEndpoint.headers());
        }

        String endpoint = hosted.endpoint() == null || hosted.endpoint().isBlank()
                ? settings.hostedIngestEndpoint()
                : hosted.endpoint();
        LinkedHashMap<String, String> headers = new LinkedHashMap<>(hosted.headers());
        if (hosted.projectKey() != null && !hosted.projectKey().isBlank()) {
            headers.put(PROJECT_KEY_HEADER, hosted.projectKey());
        }
        return new CrashReportClient.DeliveryTarget(endpoint, headers);
    }

    private static boolean boolOrDefault(@Nullable Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static int intOrDefault(@Nullable Integer value, int fallback, int min, int max) {
        int safe = value == null ? fallback : value;
        return Math.max(min, Math.min(max, safe));
    }

    private static double doubleOrDefault(@Nullable Double value, double fallback, double min, double max) {
        double safe = value == null ? fallback : value;
        return Math.max(min, Math.min(max, safe));
    }

    @Nonnull
    private static String normalizeMode(@Nullable String rawMode, @Nullable String customUrl) {
        String normalized = normalizeNullable(rawMode);
        if (normalized == null) {
            return customUrl == null || customUrl.isBlank() ? MODE_HOSTED : MODE_CUSTOM;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        return MODE_CUSTOM.equals(lower) ? MODE_CUSTOM : MODE_HOSTED;
    }

    @Nonnull
    private static String normalizeRuntimeMode(@Nullable String rawRuntimeMode) {
        String normalized = normalizeNullable(rawRuntimeMode);
        if (normalized == null) {
            return RUNTIME_MODE_DEPENDENCY;
        }
        return RUNTIME_MODE_EMBEDDED.equalsIgnoreCase(normalized)
                ? RUNTIME_MODE_EMBEDDED
                : RUNTIME_MODE_DEPENDENCY;
    }

    @Nonnull
    private static List<String> normalizeList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String value : values) {
            String safe = normalizeNullable(value);
            if (safe != null) {
                normalized.putIfAbsent(safe.toLowerCase(Locale.ROOT), safe);
            }
        }
        return List.copyOf(new ArrayList<>(normalized.values()));
    }

    @Nonnull
    private static List<String> normalizeNonBlankList(@Nullable List<String> values, int maxLength) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String value : values) {
            String safe = normalizeNullable(value);
            if (safe != null) {
                String trimmed = safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
                normalized.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return List.copyOf(new ArrayList<>(normalized.values()));
    }

    @Nonnull
    private static Map<String, String> normalizeHeaders(@Nullable Map<String, String> rawHeaders) {
        if (rawHeaders == null || rawHeaders.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawHeaders.entrySet()) {
            if (entry == null) {
                continue;
            }
            String key = normalizeNullable(entry.getKey());
            String value = normalizeNullable(entry.getValue());
            if (key != null && value != null) {
                normalized.put(key, value);
            }
        }
        return Map.copyOf(normalized);
    }

    @Nonnull
    private static Map<String, DetailRules> normalizeDetailRules(@Nullable Map<String, DetailRulesDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, DetailRules> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, DetailRulesDocument> entry : documents.entrySet()) {
            if (entry == null) {
                continue;
            }
            String eventName = normalizeNullable(entry.getKey());
            DetailRules rules = normalizeDetailRule(entry.getValue());
            if (eventName != null && !rules.allowedFields().isEmpty()) {
                normalized.putIfAbsent(eventName.toLowerCase(Locale.ROOT), rules);
            }
        }
        return Map.copyOf(normalized);
    }

    @Nonnull
    private static DetailRules normalizeDetailRule(@Nullable DetailRulesDocument document) {
        if (document == null || document.allowedFields == null || document.allowedFields.isEmpty()) {
            return new DetailRules(Map.of());
        }
        LinkedHashMap<String, DetailFieldRule> fields = new LinkedHashMap<>();
        for (Map.Entry<String, DetailFieldDocument> entry : document.allowedFields.entrySet()) {
            if (fields.size() >= 20 || entry == null) {
                break;
            }
            String key = normalizeNullable(entry.getKey());
            DetailFieldRule field = normalizeDetailField(entry.getValue());
            if (key != null && field != null) {
                fields.putIfAbsent(key, field);
            }
        }
        return new DetailRules(Map.copyOf(fields));
    }

    @Nullable
    private static DetailFieldRule normalizeDetailField(@Nullable DetailFieldDocument document) {
        if (document == null) {
            return null;
        }
        String type = normalizeNullable(document.type);
        if (type == null) {
            return null;
        }
        String normalizedType = type.toLowerCase(Locale.ROOT);
        if (!"string".equals(normalizedType)
                && !"number".equals(normalizedType)
                && !"boolean".equals(normalizedType)
                && !"enum".equals(normalizedType)) {
            return null;
        }
        List<String> values = "enum".equals(normalizedType)
                ? normalizeNonBlankList(document.values, 120)
                : List.of();
        if ("enum".equals(normalizedType) && values.isEmpty()) {
            return null;
        }
        return new DetailFieldRule(
                normalizedType,
                values,
                intOrDefault(document.maxLength, 120, 1, 500)
        );
    }

    @Nullable
    private static String normalizeNullable(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Nonnull
    private static Map<String, Object> sanitizeDetailMap(@Nonnull Map<String, DetailRules> rulesByEventName,
                                                         @Nonnull String eventName,
                                                         @Nullable Map<String, Object> rawDetails) {
        if (rulesByEventName.isEmpty() || rawDetails == null || rawDetails.isEmpty()) {
            return Map.of();
        }
        String normalizedEventName = normalizeNullable(eventName);
        if (normalizedEventName == null) {
            return Map.of();
        }
        DetailRules rules = rulesByEventName.get(normalizedEventName.toLowerCase(Locale.ROOT));
        if (rules == null || rules.allowedFields().isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, DetailFieldRule> field : rules.allowedFields().entrySet()) {
            if (sanitized.size() >= 20) {
                break;
            }
            Object value = sanitizeDetailValue(field.getValue(), rawDetails.get(field.getKey()));
            if (value != null) {
                sanitized.put(field.getKey(), value);
            }
        }
        return Map.copyOf(sanitized);
    }

    @Nullable
    private static Object sanitizeDetailValue(@Nonnull DetailFieldRule rule, @Nullable Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        return switch (rule.type()) {
            case "number" -> rawValue instanceof Number ? rawValue : null;
            case "boolean" -> rawValue instanceof Boolean ? rawValue : null;
            case "enum" -> sanitizeEnumValue(rule, rawValue);
            default -> sanitizeStringValue(rule, rawValue);
        };
    }

    @Nullable
    private static String sanitizeStringValue(@Nonnull DetailFieldRule rule, @Nonnull Object rawValue) {
        if (!(rawValue instanceof CharSequence text)) {
            return null;
        }
        String normalized = normalizeNullable(text.toString());
        if (normalized == null) {
            return null;
        }
        return normalized.length() <= rule.maxLength() ? normalized : normalized.substring(0, rule.maxLength());
    }

    @Nullable
    private static String sanitizeEnumValue(@Nonnull DetailFieldRule rule, @Nonnull Object rawValue) {
        String normalized = sanitizeStringValue(rule, rawValue);
        if (normalized == null) {
            return null;
        }
        for (String allowed : rule.values()) {
            if (allowed.equalsIgnoreCase(normalized)) {
                return allowed;
            }
        }
        return null;
    }

    @Nonnull
    private static String chooseNonBlank(@Nullable String primary, @Nullable String fallback, @Nonnull String finalFallback) {
        String normalizedPrimary = normalizeNullable(primary);
        if (normalizedPrimary != null) {
            return normalizedPrimary;
        }
        String normalizedFallback = normalizeNullable(fallback);
        return normalizedFallback == null ? finalFallback : normalizedFallback;
    }

    @Nonnull
    private static String slugify(@Nonnull String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean previousDash = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                previousDash = false;
            } else if (c >= 'A' && c <= 'Z') {
                out.append(Character.toLowerCase(c));
                previousDash = false;
            } else if (!previousDash) {
                out.append('-');
                previousDash = true;
            }
        }
        String slug = out.toString();
        while (slug.startsWith("-")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isBlank() ? "unknown-project" : slug;
    }

    public boolean isDependencyMode() {
        return RUNTIME_MODE_DEPENDENCY.equals(runtimeMode);
    }

    public boolean isEmbeddedMode() {
        return RUNTIME_MODE_EMBEDDED.equals(runtimeMode);
    }

    /**
     * Capture settings for one project.
     */
    public record CaptureOptions(boolean uncaughtExceptions,
                                 boolean setupFailures,
                                 boolean startFailures,
                                 boolean exceptionalWorldRemovals) {
        public boolean capturesSource(@Nonnull String source) {
            return switch (source) {
                case "uncaught_exception" -> uncaughtExceptions;
                case "plugin_setup_failure" -> setupFailures;
                case "plugin_start_failure" -> startFailures;
                case "exceptional_world_removal" -> exceptionalWorldRemovals;
                default -> true;
            };
        }
    }

    /**
     * Default runtime behavior for one project.
     */
    public record Defaults(boolean enabled, @Nonnull String destinationMode) {
    }

    /**
     * Explicit controls for generic telemetry events.
     */
    public record EventOptions(@Nonnull EventTypeOptions errors,
                               @Nonnull EventTypeOptions lifecycle,
                               @Nonnull BreadcrumbOptions breadcrumbs) {

        @Nonnull
        public static EventOptions defaults() {
            return new EventOptions(
                    new EventTypeOptions(true),
                    new EventTypeOptions(true),
                    new BreadcrumbOptions(true, true)
            );
        }
    }

    public record EventTypeOptions(boolean enabled) {
    }

    public record BreadcrumbOptions(boolean enabled, boolean automatic) {
    }

    /**
     * Performance telemetry defaults for one project.
     */
    public record PerformanceOptions(boolean enabled,
                                     double sampleRate,
                                     int thresholdMs,
                                     @Nonnull Map<String, DetailRules> details) {

        @Nonnull
        public Map<String, Object> sanitizeDetails(@Nonnull String eventName, @Nullable Map<String, Object> rawDetails) {
            return sanitizeDetailMap(details, eventName, rawDetails);
        }
    }

    /**
     * Usage telemetry defaults and allowlist for one project.
     */
    public record UsageOptions(boolean enabled,
                               @Nonnull List<String> allowedEvents,
                               @Nonnull Map<String, DetailRules> details) {

        public boolean allows(@Nonnull String eventName) {
            String normalized = normalizeNullable(eventName);
            return enabled() && normalized != null && allowedEvents().stream().anyMatch(normalized::equalsIgnoreCase);
        }

        @Nonnull
        public Map<String, Object> sanitizeDetails(@Nonnull String eventName, @Nullable Map<String, Object> rawDetails) {
            return sanitizeDetailMap(details, eventName, rawDetails);
        }
    }

    public record DetailRules(@Nonnull Map<String, DetailFieldRule> allowedFields) {
    }

    public record DetailFieldRule(@Nonnull String type,
                                  @Nonnull List<String> values,
                                  int maxLength) {
    }

    /**
     * Hosted destination configuration.
     */
    public record HostedDestination(@Nullable String endpoint,
                                    @Nullable String eventEndpoint,
                                    @Nullable String projectKey,
                                    @Nonnull Map<String, String> headers) {
    }

    /**
     * Custom endpoint configuration.
     */
    public record CustomEndpoint(@Nullable String url,
                                 @Nullable String eventUrl,
                                 @Nonnull Map<String, String> headers) {
    }

    /**
     * Manifest-derived fallbacks used during descriptor normalization.
     */
    public record Fallbacks(@Nullable String projectId,
                            @Nullable String displayName,
                            @Nullable String ownerPluginIdentifier,
                            @Nonnull List<String> packagePrefixes) {
    }

    private static final class Document {
        private Integer schemaVersion;
        private String projectId;
        private String displayName;
        private String runtimeMode;
        private List<String> ownerPluginIdentifiers;
        private List<String> packagePrefixes;
        private CaptureDocument capture;
        private EventsDocument events;
        private PerformanceDocument performance;
        private UsageDocument usage;
        private DefaultsDocument defaults;
        private HostedDocument hosted;
        private CustomEndpointDocument customEndpoint;
    }

    private static final class CaptureDocument {
        private Boolean uncaughtExceptions;
        private Boolean setupFailures;
        private Boolean startFailures;
        private Boolean exceptionalWorldRemovals;
    }

    private static final class DefaultsDocument {
        private Boolean enabled;
        private String destinationMode;
    }

    private static final class EventsDocument {
        private EventTypeDocument errors;
        private EventTypeDocument lifecycle;
        private BreadcrumbsDocument breadcrumbs;
    }

    private static final class EventTypeDocument {
        private Boolean enabled;
    }

    private static final class BreadcrumbsDocument {
        private Boolean enabled;
        private Boolean automatic;
    }

    private static final class PerformanceDocument {
        private Boolean enabled;
        private Double sampleRate;
        private Integer thresholdMs;
        private Map<String, DetailRulesDocument> details;
    }

    private static final class UsageDocument {
        private Boolean enabled;
        private List<String> allowedEvents;
        private Map<String, DetailRulesDocument> details;
    }

    private static final class DetailRulesDocument {
        private Map<String, DetailFieldDocument> allowedFields;
    }

    private static final class DetailFieldDocument {
        private String type;
        private List<String> values;
        private Integer maxLength;
    }

    private static final class HostedDocument {
        private String endpoint;
        private String eventEndpoint;
        private String projectKey;
        private Map<String, String> headers;
    }

    private static final class CustomEndpointDocument {
        private String url;
        private String eventUrl;
        private Map<String, String> headers;
    }
}
