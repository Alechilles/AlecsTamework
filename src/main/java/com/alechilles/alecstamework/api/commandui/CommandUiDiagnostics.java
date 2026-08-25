package com.alechilles.alecstamework.api.commandui;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable, value-only diagnostics for the command UI registration and
 * session lifecycle.
 *
 * <p>This view contains identifiers, generations, statuses, counts, timing
 * summaries, and safe reason codes only. It never contains action handles,
 * action input, mutable runtime objects, or contributor value data.</p>
 */
public final class CommandUiDiagnostics {
    private final List<RendererRegistration> renderers;
    private final List<ContributorRegistration> contributors;
    private final List<SessionView> sessions;
    @Nullable
    private final String latestFailureReason;
    private final long slowCompositionCount;
    private final long slowWarningCount;

    /** Creates an immutable diagnostics snapshot. */
    public CommandUiDiagnostics(
            @Nullable List<RendererRegistration> renderers,
            @Nullable List<ContributorRegistration> contributors,
            @Nullable List<SessionView> sessions,
            @Nullable String latestFailureReason,
            long slowCompositionCount,
            long slowWarningCount
    ) {
        if (slowCompositionCount < 0L || slowWarningCount < 0L) {
            throw new IllegalArgumentException(
                    "Command UI diagnostic counts cannot be negative.");
        }
        this.renderers = List.copyOf(renderers == null ? List.of() : renderers);
        this.contributors = List.copyOf(
                contributors == null ? List.of() : contributors);
        this.sessions = List.copyOf(sessions == null ? List.of() : sessions);
        this.latestFailureReason = normalizeReason(latestFailureReason);
        this.slowCompositionCount = slowCompositionCount;
        this.slowWarningCount = slowWarningCount;
    }

    /** Returns a stable empty snapshot for unavailable or older adapters. */
    @Nonnull
    public static CommandUiDiagnostics empty() {
        return new CommandUiDiagnostics(
                List.of(), List.of(), List.of(), null, 0L, 0L);
    }

    /** Returns registered renderer IDs and exact generations. */
    @Nonnull
    public List<RendererRegistration> renderers() {
        return renderers;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<RendererRegistration> registeredRenderers() {
        return renderers;
    }

    /** Returns registered contributor IDs and exact generations. */
    @Nonnull
    public List<ContributorRegistration> contributors() {
        return contributors;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<ContributorRegistration> registeredContributors() {
        return contributors;
    }

    /** Returns active custom session diagnostics. */
    @Nonnull
    public List<SessionView> sessions() {
        return sessions;
    }

    /** Alias for callers interested only in active sessions. */
    @Nonnull
    public List<SessionView> activeSessions() {
        return sessions;
    }

    /** Returns the number of active custom sessions. */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** Returns the latest safe fallback, callback, or composition reason. */
    @Nullable
    public String latestFailureReason() {
        return latestFailureReason;
    }

    /** Returns the number of slow composition callbacks observed. */
    public long slowCompositionCount() {
        return slowCompositionCount;
    }

    /** Returns the number of throttled slow-composition warnings emitted. */
    public long slowWarningCount() {
        return slowWarningCount;
    }

    /** Safe renderer registration state. */
    public record RendererRegistration(@Nonnull String rendererId,
                                       long generation) {
        public RendererRegistration {
            rendererId = requireId(rendererId, "rendererId");
            requireGeneration(generation, "renderer generation");
        }
    }

    /** Safe contributor registration state. */
    public record ContributorRegistration(@Nonnull String contributorId,
                                          long generation) {
        public ContributorRegistration {
            contributorId = requireId(contributorId, "contributorId");
            requireGeneration(generation, "contributor generation");
        }
    }

    /** Immutable state for one configured contributor in one active session. */
    public record ContributorView(
            @Nonnull String contributorId,
            long generation,
            @Nonnull String status,
            long composeCount,
            long totalComposeNanos,
            long lastComposeNanos,
            long slowComposeCount,
            @Nullable String failureReason
    ) {
        public ContributorView {
            contributorId = requireId(contributorId, "contributorId");
            requireGeneration(generation, "contributor generation");
            status = requireStatus(status);
            requireCount(composeCount, "composeCount");
            requireCount(totalComposeNanos, "totalComposeNanos");
            requireCount(lastComposeNanos, "lastComposeNanos");
            requireCount(slowComposeCount, "slowComposeCount");
            failureReason = normalizeReason(failureReason);
        }
    }

    /** Immutable state for one active custom command UI session. */
    public record SessionView(
            @Nonnull UUID sessionId,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nonnull List<ContributorView> contributors,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String failureReason
    ) {
        public SessionView {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            rendererId = normalizeId(rendererId);
            requireGeneration(rendererGeneration, "renderer generation");
            contributors = List.copyOf(
                    contributors == null ? List.of() : contributors);
            itemId = normalizeId(itemId);
            configId = normalizeId(configId);
            failureReason = normalizeReason(failureReason);
        }
    }

    @Nonnull
    private static String requireId(@Nullable String value, String field) {
        String normalized = normalizeId(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return normalized;
    }

    private static void requireGeneration(long value, String field) {
        if (value < 0L) throw new IllegalArgumentException(field + " cannot be negative.");
    }

    private static void requireCount(long value, String field) {
        if (value < 0L) throw new IllegalArgumentException(field + " cannot be negative.");
    }

    @Nonnull
    private static String requireStatus(@Nullable String value) {
        String normalized = normalizeId(value);
        if (normalized == null) return "UNKNOWN";
        return normalized;
    }

    @Nullable
    private static String normalizeId(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    private static String normalizeReason(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
