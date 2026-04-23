package com.alechilles.alecstelemetry.crash;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Network boundary for crash report uploads.
 */
public interface CrashReportClient {

    @Nonnull
    UploadResult upload(@Nonnull DeliveryTarget target, @Nonnull String payloadJson);

    /**
     * Resolved destination details for one project.
     */
    record DeliveryTarget(@Nonnull String endpoint, @Nonnull Map<String, String> headers) {
        @Nonnull
        public DeliveryTarget normalize() {
            String safeEndpoint = endpoint == null ? "" : endpoint.trim();
            LinkedHashMap<String, String> safeHeaders = new LinkedHashMap<>();
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry == null) {
                        continue;
                    }
                    String key = entry.getKey() == null ? "" : entry.getKey().trim();
                    String value = entry.getValue() == null ? "" : entry.getValue().trim();
                    if (!key.isBlank() && !value.isBlank()) {
                        safeHeaders.put(key, value);
                    }
                }
            }
            return new DeliveryTarget(safeEndpoint, Map.copyOf(safeHeaders));
        }
    }

    /**
     * Result for one upload attempt.
     */
    record UploadResult(boolean success, int statusCode, @Nullable String detail) {
        @Nonnull
        public static UploadResult success(int statusCode) {
            return new UploadResult(true, statusCode, null);
        }

        @Nonnull
        public static UploadResult failure(int statusCode, @Nullable String detail) {
            return new UploadResult(false, statusCode, detail);
        }
    }
}
