package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Emits low-cardinality telemetry context for intermittent needs seek/consume failures.
 */
public final class NeedsTelemetryDiagnostics {
    private static final long REPEAT_EVENT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final ConcurrentHashMap<String, Long> LAST_EVENT_BY_SIGNATURE = new ConcurrentHashMap<>();

    private NeedsTelemetryDiagnostics() {
    }

    public static final class EventNames {
        public static final String NEEDS_SEEK_FAILED = "needs_seek_failed";
        public static final String NEEDS_CONSUME_FAILED = "needs_consume_failed";

        private EventNames() {
        }
    }

    public static final class Fingerprints {
        public static final String NEEDS_SEEK_FAILED = "tamework.needs.seek.failed";
        public static final String NEEDS_CONSUME_FAILED = "tamework.needs.consume.failed";

        private Fingerprints() {
        }
    }

    public static void recordSeekFailure(@Nullable String roleId,
                                         @Nonnull String resourceType,
                                         @Nonnull String result,
                                         @Nonnull String detail,
                                         double searchRange,
                                         @Nullable Double currentRatio,
                                         boolean cacheHit) {
        if ("target_found".equals(result) || !isRuntimeEnabled()) {
            return;
        }
        String resource = normalizeResource(resourceType);
        String reason = normalizeReason(detail.isBlank() ? result : detail);
        if (!isReportableSeekFailureReason(reason)) {
            return;
        }
        String role = normalizeRole(roleId);
        if (isThrottled(EventNames.NEEDS_SEEK_FAILED, role, resource, reason)) {
            return;
        }
        TameworkTelemetryEvents.recordErrorIfAvailable(
                EventNames.NEEDS_SEEK_FAILED,
                null,
                needsContext("needs_seek", "seek_target_resolution", Fingerprints.NEEDS_SEEK_FAILED, resource, role)
                        .detail("reason", reason)
                        .detail("resource", resource)
                        .detail("roleId", role)
                        .detail("result", normalizeReason(result))
                        .detail("failureStage", seekFailureStage(reason))
                        .detail("sourceCandidate", seekSourceCandidate(reason))
                        .detail("cacheHit", cacheHit)
                        .detail("needBucket", needsBucket(currentRatio))
                        .detail("searchRangeBucket", rangeBucket(searchRange))
                        .build()
        );
    }

    public static void recordConsumeFailure(@Nullable String roleId,
                                            @Nonnull String mode,
                                            @Nonnull String reason,
                                            int consumedItems,
                                            double hungerGain,
                                            double thirstGain) {
        if (reason.isBlank()
                || "success".equals(reason)
                || "success_happiness_only".equals(reason)
                || !isRuntimeEnabled()) {
            return;
        }
        String resource = normalizeResource(mode);
        ConsumeFailureContext failureContext = consumeFailureContext(reason);
        String normalizedReason = failureContext.reason();
        String role = normalizeRole(roleId);
        if (isThrottled(EventNames.NEEDS_CONSUME_FAILED, role, resource, normalizedReason)) {
            return;
        }
        TelemetryEventContext.Builder context = needsContext(
                        "needs_consume",
                        "consume_resource",
                        Fingerprints.NEEDS_CONSUME_FAILED,
                        resource,
                        role
                )
                .detail("reason", normalizedReason)
                .detail("resource", resource)
                .detail("roleId", role)
                .detail("consumedItemsBucket", countBucket(consumedItems))
                .detail("hungerGainBucket", gainBucket(hungerGain))
                .detail("thirstGainBucket", gainBucket(thirstGain));
        failureContext.addDetails(context);
        TameworkTelemetryEvents.recordErrorIfAvailable(
                EventNames.NEEDS_CONSUME_FAILED,
                null,
                context.build()
        );
    }

    @Nonnull
    public static String needsBucket(@Nullable Double ratio) {
        if (ratio == null || !Double.isFinite(ratio)) {
            return "unknown";
        }
        double clamped = Math.max(0.0d, Math.min(1.0d, ratio));
        if (clamped <= 0.25d) {
            return "0-25";
        }
        if (clamped <= 0.50d) {
            return "26-50";
        }
        if (clamped <= 0.75d) {
            return "51-75";
        }
        return "76-100";
    }

    @Nonnull
    static String normalizeResource(@Nullable String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return "unknown";
        }
        String normalized = resourceType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("both") || normalized.equals("auto")) {
            return "both";
        }
        boolean food = normalized.contains("food");
        boolean water = normalized.contains("water");
        if (food && water) {
            return "both";
        }
        if (food) {
            return "food";
        }
        if (water) {
            return "water";
        }
        return "unknown";
    }

    @Nonnull
    static String normalizeReason(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        String normalized = reason.trim()
                .replace(' ', '_')
                .replace(',', '+');
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    static boolean isReportableSeekFailureReason(@Nonnull String reason) {
        return "food_item_ids_empty".equals(reason)
                || "food_source_found_but_no_stand_target".equals(reason)
                || "food_source_found_but_no_stand_target_fallback".equals(reason)
                || "water_source_found_but_no_stand_target".equals(reason)
                || "water_source_found_but_no_stand_target_fallback".equals(reason)
                || "needs_component_type_missing".equals(reason)
                || "needs_component_missing".equals(reason)
                || "needs_config_missing_or_disabled".equals(reason);
    }

    @Nonnull
    static String seekFailureStage(@Nonnull String reason) {
        if (reason.contains("_source_found_but_no_stand_target")) {
            return "stand_target";
        }
        if ("food_item_ids_empty".equals(reason) || "needs_config_missing_or_disabled".equals(reason)) {
            return "config";
        }
        if ("needs_component_type_missing".equals(reason) || "needs_component_missing".equals(reason)) {
            return "component";
        }
        return "unknown";
    }

    @Nonnull
    static String seekSourceCandidate(@Nonnull String reason) {
        if (reason.contains("_source_found_but_no_stand_target")) {
            return "found";
        }
        return "unknown";
    }

    @Nonnull
    static ConsumeFailureContext consumeFailureContext(@Nullable String reason) {
        String rawReason = reason == null ? "" : reason.trim();
        Map<String, String> containerSummary = parseContainerSummary(rawReason);
        boolean containerFoodFailed = rawReason.contains("no_container_food_consumed(");
        boolean waterFailed = rawReason.contains("not_near_water");
        String telemetryReason;
        if (containerFoodFailed && waterFailed) {
            telemetryReason = "food_and_water_unavailable";
        } else if (containerFoodFailed) {
            telemetryReason = "no_container_food_consumed";
        } else if (waterFailed) {
            telemetryReason = "not_near_water";
        } else {
            telemetryReason = normalizeReason(rawReason);
        }
        return new ConsumeFailureContext(telemetryReason, containerSummary);
    }

    @Nonnull
    private static Map<String, String> parseContainerSummary(@Nonnull String reason) {
        int start = reason.indexOf("no_container_food_consumed(");
        if (start < 0) {
            return Map.of();
        }
        start += "no_container_food_consumed(".length();
        int end = reason.indexOf(')', start);
        if (end <= start) {
            return Map.of();
        }
        String summary = reason.substring(start, end);
        Map<String, String> values = new HashMap<>();
        for (String token : summary.split(",")) {
            int separator = token.indexOf('=');
            if (separator <= 0 || separator >= token.length() - 1) {
                continue;
            }
            values.put(token.substring(0, separator).trim(), token.substring(separator + 1).trim());
        }
        return values;
    }

    @Nonnull
    private static TelemetryEventContext.Builder needsContext(@Nonnull String featureKey,
                                                             @Nonnull String operation,
                                                             @Nonnull String fingerprint,
                                                             @Nonnull String resource,
                                                             @Nonnull String role) {
        return TameworkTelemetryEvents.featureContext("needs", featureKey, "runtime")
                .severity("warning")
                .fingerprint(fingerprint)
                .operation(operation)
                .target(resource)
                .entityType(role);
    }

    private static boolean isRuntimeEnabled() {
        Tamework instance = Tamework.getInstance();
        return instance != null && instance.isDebugNeedsTelemetryDiagnosticsEnabled();
    }

    private static boolean isThrottled(@Nonnull String eventName,
                                       @Nonnull String role,
                                       @Nonnull String resource,
                                       @Nonnull String reason) {
        long nowMs = System.currentTimeMillis();
        String key = eventName + '|' + role + '|' + resource + '|' + reason;
        Long previous = LAST_EVENT_BY_SIGNATURE.get(key);
        if (previous != null && nowMs < previous + REPEAT_EVENT_INTERVAL_MS) {
            return true;
        }
        LAST_EVENT_BY_SIGNATURE.put(key, nowMs);
        return false;
    }

    @Nonnull
    private static String normalizeRole(@Nullable String roleId) {
        return roleId == null || roleId.isBlank() ? "unknown" : roleId.trim();
    }

    @Nonnull
    private static String rangeBucket(double value) {
        if (!Double.isFinite(value) || value < 0.0d) {
            return "unknown";
        }
        if (value <= 8.0d) {
            return "0-8";
        }
        if (value <= 16.0d) {
            return "9-16";
        }
        if (value <= 32.0d) {
            return "17-32";
        }
        return "33+";
    }

    @Nonnull
    private static String countBucket(int value) {
        if (value <= 0) {
            return "0";
        }
        if (value == 1) {
            return "1";
        }
        if (value <= 3) {
            return "2-3";
        }
        return "4+";
    }

    @Nonnull
    private static String gainBucket(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return "0";
        }
        if (value <= 5.0d) {
            return "1-5";
        }
        if (value <= 15.0d) {
            return "6-15";
        }
        return "16+";
    }

    static final class ConsumeFailureContext {
        private final String reason;
        @Nonnull
        private final Map<String, String> containerSummary;

        ConsumeFailureContext(@Nonnull String reason, @Nonnull Map<String, String> containerSummary) {
            this.reason = normalizeReason(reason);
            this.containerSummary = containerSummary.isEmpty() ? Map.of() : Map.copyOf(containerSummary);
        }

        @Nonnull
        String reason() {
            return reason;
        }

        @Nonnull
        String detail(@Nonnull String key) {
            return containerSummary.getOrDefault(key, "unknown");
        }

        void addDetails(@Nonnull TelemetryEventContext.Builder context) {
            if (containerSummary.isEmpty()) {
                return;
            }
            context.detail("containerStatus", normalizeReason(detail("status")).toLowerCase(Locale.ROOT))
                    .detail("scannedContainerCountBucket", countBucketOrUnknown(detail("containers")))
                    .detail("allowedContainerCountBucket", countBucketOrUnknown(detail("allowedContainers")))
                    .detail("matchingStackCountBucket", countBucketOrUnknown(detail("matchingStacks")))
                    .detail("consumeAttemptBucket", countBucketOrUnknown(detail("attempts")))
                    .detail("consumeFailureBucket", countBucketOrUnknown(detail("failures")))
                    .detail("maxItemsBucket", countBucketOrUnknown(detail("maxItems")))
                    .detail("scanRadiusBucket", decimalBucketOrUnknown(detail("radius")))
                    .detail("verticalScanBucket", countBucketOrUnknown(detail("vScan")))
                    .detail("nearestContainerDistanceBucket", distanceBucketOrUnknown(detail("nearestContainerDist")))
                    .detail("nearestAllowedContainerDistanceBucket", distanceBucketOrUnknown(detail("nearestAllowedContainerDist")))
                    .detail("scanSource", normalizeReason(detail("scanSource")).toLowerCase(Locale.ROOT));
        }

        @Nonnull
        private static String countBucketOrUnknown(@Nonnull String value) {
            try {
                return countBucket(Integer.parseInt(value));
            } catch (NumberFormatException ex) {
                return "unknown";
            }
        }

        @Nonnull
        private static String decimalBucketOrUnknown(@Nonnull String value) {
            try {
                return distanceBucket(Double.parseDouble(value));
            } catch (NumberFormatException ex) {
                return "unknown";
            }
        }

        @Nonnull
        private static String distanceBucketOrUnknown(@Nonnull String value) {
            if ("n/a".equalsIgnoreCase(value)) {
                return "none";
            }
            try {
                return distanceBucket(Double.parseDouble(value));
            } catch (NumberFormatException ex) {
                return "unknown";
            }
        }

        @Nonnull
        private static String distanceBucket(double value) {
            if (!Double.isFinite(value) || value < 0.0d) {
                return "unknown";
            }
            if (value <= 2.0d) {
                return "0-2";
            }
            if (value <= 4.0d) {
                return "3-4";
            }
            if (value <= 8.0d) {
                return "5-8";
            }
            return "9+";
        }
    }
}
