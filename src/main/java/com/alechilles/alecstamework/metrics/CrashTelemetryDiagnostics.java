package com.alechilles.alecstamework.metrics;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Snapshot status for crash telemetry diagnostics.
 */
public record CrashTelemetryDiagnostics(boolean enabled,
                                        @Nonnull String endpoint,
                                        int pendingReports,
                                        boolean flushInProgress,
                                        @Nonnull String lastFlushResult,
                                        long lastFlushEpochMs) {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    @Nonnull
    public String formatLastFlushAtUtc() {
        if (lastFlushEpochMs <= 0L) {
            return "<never>";
        }
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(lastFlushEpochMs));
    }
}
