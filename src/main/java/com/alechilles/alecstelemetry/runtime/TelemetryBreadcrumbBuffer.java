package com.alechilles.alecstelemetry.runtime;

import com.alechilles.alecstelemetry.crash.CrashReportEnvelope;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * In-memory rolling breadcrumb buffer keyed by telemetry project id.
 */
public final class TelemetryBreadcrumbBuffer {

    private final int maxEntriesPerProject;
    private final LinkedHashMap<String, ArrayDeque<CrashReportEnvelope.BreadcrumbEntry>> byProjectId = new LinkedHashMap<>();

    public TelemetryBreadcrumbBuffer(int maxEntriesPerProject) {
        this.maxEntriesPerProject = Math.max(1, maxEntriesPerProject);
    }

    public synchronized void record(@Nonnull String projectId,
                                    @Nonnull String category,
                                    @Nonnull String detail) {
        String safeProjectId = normalize(projectId, 120);
        String safeCategory = normalize(category, 80);
        String safeDetail = normalize(detail, 400);
        if (safeProjectId.isBlank() || safeCategory.isBlank() || safeDetail.isBlank()) {
            return;
        }
        ArrayDeque<CrashReportEnvelope.BreadcrumbEntry> deque = byProjectId.computeIfAbsent(
                safeProjectId.toLowerCase(Locale.ROOT),
                ignored -> new ArrayDeque<>()
        );
        while (deque.size() >= maxEntriesPerProject) {
            deque.removeFirst();
        }
        deque.addLast(new CrashReportEnvelope.BreadcrumbEntry(Instant.now().toString(), safeCategory, safeDetail));
    }

    @Nonnull
    public synchronized List<CrashReportEnvelope.BreadcrumbEntry> snapshot(@Nonnull String projectId) {
        ArrayDeque<CrashReportEnvelope.BreadcrumbEntry> deque = byProjectId.get(projectId.toLowerCase(Locale.ROOT));
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(deque));
    }

    public synchronized void clear(@Nonnull String projectId) {
        byProjectId.remove(projectId.toLowerCase(Locale.ROOT));
    }

    @Nonnull
    private static String normalize(@Nonnull String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
