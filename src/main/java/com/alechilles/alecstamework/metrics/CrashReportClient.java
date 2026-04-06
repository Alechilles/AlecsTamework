package com.alechilles.alecstamework.metrics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Network boundary for crash report uploads.
 */
public interface CrashReportClient {

    @Nonnull
    UploadResult upload(@Nonnull String payloadJson);

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
