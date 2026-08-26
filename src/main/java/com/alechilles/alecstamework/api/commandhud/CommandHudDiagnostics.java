package com.alechilles.alecstamework.api.commandhud;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Immutable, value-only diagnostics for command HUD registrations and sessions. */
public final class CommandHudDiagnostics {
    private final List<RendererRegistration> targetRenderers;
    private final List<RendererRegistration> hotswapRenderers;
    private final List<ContributorRegistration> targetContributors;
    private final List<ContributorRegistration> hotswapContributors;
    private final List<SessionView> sessions;
    @Nullable
    private final String latestFailureReason;
    private final long slowCompositionCount;
    private final long slowWarningCount;

    /** Creates an empty-session diagnostics snapshot. */
    public CommandHudDiagnostics(
            @Nullable List<RendererRegistration> targetRenderers,
            @Nullable List<RendererRegistration> hotswapRenderers,
            @Nullable List<ContributorRegistration> targetContributors,
            @Nullable List<ContributorRegistration> hotswapContributors
    ) {
        this(targetRenderers, hotswapRenderers, targetContributors,
                hotswapContributors, List.of(), null, 0L, 0L);
    }

    /** Creates an immutable diagnostics snapshot. */
    public CommandHudDiagnostics(
            @Nullable List<RendererRegistration> targetRenderers,
            @Nullable List<RendererRegistration> hotswapRenderers,
            @Nullable List<ContributorRegistration> targetContributors,
            @Nullable List<ContributorRegistration> hotswapContributors,
            @Nullable List<SessionView> sessions,
            @Nullable String latestFailureReason,
            long slowCompositionCount,
            long slowWarningCount
    ) {
        if (slowCompositionCount < 0L || slowWarningCount < 0L) {
            throw new IllegalArgumentException(
                    "Command HUD diagnostic counts cannot be negative.");
        }
        this.targetRenderers = List.copyOf(
                targetRenderers == null ? List.of() : targetRenderers);
        this.hotswapRenderers = List.copyOf(
                hotswapRenderers == null ? List.of() : hotswapRenderers);
        this.targetContributors = List.copyOf(
                targetContributors == null ? List.of() : targetContributors);
        this.hotswapContributors = List.copyOf(
                hotswapContributors == null ? List.of() : hotswapContributors);
        this.sessions = List.copyOf(sessions == null ? List.of() : sessions);
        this.latestFailureReason = normalizeReason(latestFailureReason);
        this.slowCompositionCount = slowCompositionCount;
        this.slowWarningCount = slowWarningCount;
    }

    /** Returns a stable empty snapshot for unavailable or older adapters. */
    @Nonnull
    public static CommandHudDiagnostics empty() {
        return new CommandHudDiagnostics(
                List.of(), List.of(), List.of(), List.of(), List.of(), null, 0L, 0L);
    }

    /** Returns live target renderer IDs and exact generations. */
    @Nonnull
    public List<RendererRegistration> targetRenderers() {
        return targetRenderers;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<RendererRegistration> registeredTargetRenderers() {
        return targetRenderers;
    }

    /** Returns live hotswap renderer IDs and exact generations. */
    @Nonnull
    public List<RendererRegistration> hotswapRenderers() {
        return hotswapRenderers;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<RendererRegistration> registeredHotswapRenderers() {
        return hotswapRenderers;
    }

    /** Returns live target contributor IDs and exact generations. */
    @Nonnull
    public List<ContributorRegistration> targetContributors() {
        return targetContributors;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<ContributorRegistration> registeredTargetContributors() {
        return targetContributors;
    }

    /** Returns live hotswap contributor IDs and exact generations. */
    @Nonnull
    public List<ContributorRegistration> hotswapContributors() {
        return hotswapContributors;
    }

    /** Alias that makes the registration scope explicit. */
    @Nonnull
    public List<ContributorRegistration> registeredHotswapContributors() {
        return hotswapContributors;
    }

    /** Returns all live renderer registrations from both independent surfaces. */
    @Nonnull
    public List<RendererRegistration> renderers() {
        List<RendererRegistration> values = new ArrayList<>(
                targetRenderers.size() + hotswapRenderers.size());
        values.addAll(targetRenderers);
        values.addAll(hotswapRenderers);
        return List.copyOf(values);
    }

    /** Returns all live contributor registrations from both independent surfaces. */
    @Nonnull
    public List<ContributorRegistration> contributors() {
        List<ContributorRegistration> values = new ArrayList<>(
                targetContributors.size() + hotswapContributors.size());
        values.addAll(targetContributors);
        values.addAll(hotswapContributors);
        return List.copyOf(values);
    }

    /** Returns active custom sessions for both surfaces. */
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

    /** Returns the latest safe fallback, callback, or close reason. */
    @Nullable
    public String latestFailureReason() {
        return latestFailureReason;
    }

    /** Returns the number of slow contributor compositions observed. */
    public long slowCompositionCount() {
        return slowCompositionCount;
    }

    /** Returns the number of throttled slow-composition warnings emitted. */
    public long slowWarningCount() {
        return slowWarningCount;
    }

    /** Safe target or hotswap renderer registration state. */
    public record RendererRegistration(@Nonnull String rendererId,
                                       long generation) {
        public RendererRegistration {
            rendererId = requireId(rendererId, "rendererId");
            requireGeneration(generation, "renderer generation");
        }
    }

    /** Safe target or hotswap contributor registration state. */
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

    /** Immutable state for one active custom command HUD session. */
    public record SessionView(
            @Nonnull UUID sessionId,
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nonnull List<ContributorView> contributors,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable String failureReason
    ) {
        public SessionView {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            surface = Objects.requireNonNull(surface, "surface");
            rendererId = normalizeId(rendererId);
            requireGeneration(rendererGeneration, "renderer generation");
            contributors = List.copyOf(
                    contributors == null ? List.of() : contributors);
            itemId = normalizeId(itemId);
            configId = normalizeId(configId);
            failureReason = normalizeReason(failureReason);
        }

        /** Compatibility constructor for a target-HUD session. */
        public SessionView(
                @Nonnull UUID sessionId,
                @Nullable String rendererId,
                long rendererGeneration,
                @Nonnull List<ContributorView> contributors,
                @Nullable String itemId,
                @Nullable String configId,
                @Nullable String failureReason
        ) {
            this(sessionId, CommandHudSurface.TARGET, rendererId, rendererGeneration,
                    contributors, itemId, configId, failureReason);
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
        return normalized == null ? "UNKNOWN" : normalized;
    }

    @Nullable
    private static String normalizeId(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nullable
    private static String normalizeReason(@Nullable String value) {
        return normalizeId(value);
    }
}
