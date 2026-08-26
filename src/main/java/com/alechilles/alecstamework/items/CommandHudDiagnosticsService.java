package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns redacted process-local command HUD diagnostics state. */
final class CommandHudDiagnosticsService implements AutoCloseable {
    private static final java.util.Set<String> SAFE_REASON_CODES = java.util.Set.of(
            "initial_composition_failed",
            "required_composition_failed",
            "required_contributor_failed",
            "required_contributor_removed",
            "optional_contributor_removed",
            "contribution_bounds_exceeded",
            "renderer_failed",
            "renderer_unavailable",
            "callback_failed"
    );

    private final Object lock = new Object();
    private final CommandHudTimingWarnings timingWarnings;
    private final Map<RegistrationKey, CommandHudDiagnostics.RendererRegistration> renderers =
            new LinkedHashMap<>();
    private final Map<RegistrationKey, CommandHudDiagnostics.ContributorRegistration> contributors =
            new LinkedHashMap<>();
    private final Map<UUID, SessionState> sessions = new LinkedHashMap<>();
    @Nullable
    private String latestFailureReason;
    private long slowCompositionCount;
    private long slowWarningCount;

    /** Creates diagnostics backed by the monotonic system clock. */
    CommandHudDiagnosticsService() {
        this(new CommandHudTimingWarnings());
    }

    /** Creates diagnostics backed by an injected monotonic clock. */
    CommandHudDiagnosticsService(@Nonnull LongSupplier nanoTime) {
        this(new CommandHudTimingWarnings(
                Objects.requireNonNull(nanoTime, "nanoTime"), null));
    }

    /** Creates diagnostics backed by an injected clock and warning sink. */
    CommandHudDiagnosticsService(
            @Nonnull LongSupplier nanoTime,
            @Nullable Consumer<CommandHudTimingWarnings.Warning> warningSink
    ) {
        this(new CommandHudTimingWarnings(
                Objects.requireNonNull(nanoTime, "nanoTime"), warningSink));
    }

    /** Creates diagnostics with a caller-owned timing tracker. */
    CommandHudDiagnosticsService(@Nonnull CommandHudTimingWarnings timingWarnings) {
        this.timingWarnings = Objects.requireNonNull(timingWarnings, "timingWarnings");
    }

    /** Records a renderer registration for one independent HUD surface. */
    private void registerRenderer(
            @Nonnull CommandHudSurface surface,
            @Nonnull String rendererId,
            long generation
    ) {
        Objects.requireNonNull(surface, "surface");
        CommandHudDiagnostics.RendererRegistration registration =
                new CommandHudDiagnostics.RendererRegistration(rendererId, generation);
        synchronized (lock) {
            renderers.put(new RegistrationKey(surface, registration.rendererId()), registration);
        }
    }

    /** Compatibility overload that records a target renderer. */
    private void registerRenderer(@Nonnull String rendererId, long generation) {
        registerRenderer(CommandHudSurface.TARGET, rendererId, generation);
    }

    private void unregisterRenderer(
            @Nonnull CommandHudSurface surface,
            @Nonnull String rendererId,
            long generation
    ) {
        Objects.requireNonNull(surface, "surface");
        String id = requireId(rendererId, "rendererId");
        synchronized (lock) {
            RegistrationKey key = new RegistrationKey(surface, id);
            CommandHudDiagnostics.RendererRegistration current = renderers.get(key);
            if (current != null && current.generation() == generation) renderers.remove(key);
        }
    }

    private void unregisterRenderer(@Nonnull String rendererId, long generation) {
        unregisterRenderer(CommandHudSurface.TARGET, rendererId, generation);
    }

    /** Records a contributor registration for one independent HUD surface. */
    private void registerContributor(
            @Nonnull CommandHudSurface surface,
            @Nonnull String contributorId,
            long generation
    ) {
        Objects.requireNonNull(surface, "surface");
        CommandHudDiagnostics.ContributorRegistration registration =
                new CommandHudDiagnostics.ContributorRegistration(contributorId, generation);
        synchronized (lock) {
            contributors.put(new RegistrationKey(surface, registration.contributorId()), registration);
        }
    }

    /** Compatibility overload that records a target contributor. */
    private void registerContributor(@Nonnull String contributorId, long generation) {
        registerContributor(CommandHudSurface.TARGET, contributorId, generation);
    }

    private void unregisterContributor(
            @Nonnull CommandHudSurface surface,
            @Nonnull String contributorId,
            long generation
    ) {
        Objects.requireNonNull(surface, "surface");
        String id = requireId(contributorId, "contributorId");
        synchronized (lock) {
            RegistrationKey key = new RegistrationKey(surface, id);
            CommandHudDiagnostics.ContributorRegistration current = contributors.get(key);
            if (current != null && current.generation() == generation) contributors.remove(key);
        }
    }

    private void unregisterContributor(@Nonnull String contributorId, long generation) {
        unregisterContributor(CommandHudSurface.TARGET, contributorId, generation);
    }

    /** Starts diagnostics for one active custom HUD session. */
    void openSession(
            @Nonnull UUID sessionId,
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable List<CommandHudDiagnostics.ContributorRegistration> selectedContributors
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(surface, "surface");
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException("Renderer generation cannot be negative.");
        }
        List<CommandHudDiagnostics.ContributorRegistration> selected =
                selectedContributors == null ? List.of() : List.copyOf(selectedContributors);
        synchronized (lock) {
            SessionState state = new SessionState(sessionId, surface,
                    normalizeId(rendererId), rendererGeneration,
                    normalizeId(itemId), normalizeId(configId));
            for (CommandHudDiagnostics.ContributorRegistration contributor : selected) {
                Objects.requireNonNull(contributor, "selected contributor");
                state.contributors.put(contributor.contributorId(), new ContributorState(contributor));
            }
            sessions.put(sessionId, state);
        }
    }

    /** Compatibility overload retained for target-HUD callers. */
    void openSession(
            @Nonnull UUID sessionId,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable List<CommandHudDiagnostics.ContributorRegistration> selectedContributors
    ) {
        openSession(sessionId, CommandHudSurface.TARGET, rendererId,
                rendererGeneration, itemId, configId, selectedContributors);
    }

    /** Closes one active session and records only a safe reason code. */
    void closeSession(@Nonnull UUID sessionId, @Nullable String reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        String safeReason = safeReason(reason);
        synchronized (lock) {
            if (sessions.remove(sessionId) != null && safeReason != null) {
                latestFailureReason = safeReason;
            }
        }
    }

    /** Records a safe session failure without retaining its private cause. */
    void recordSessionFailure(@Nonnull UUID sessionId, @Nullable String reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        String safeReason = safeReason(reason);
        if (safeReason == null) return;
        synchronized (lock) {
            SessionState state = sessions.get(sessionId);
            if (state != null) state.failureReason = safeReason;
            latestFailureReason = safeReason;
        }
    }

    /** Marks one selected contributor generation as removed. */
    void contributorRemoved(
            @Nonnull UUID sessionId,
            @Nonnull String contributorId,
            long generation
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        String id = requireId(contributorId, "contributorId");
        synchronized (lock) {
            SessionState session = sessions.get(sessionId);
            if (session == null) return;
            ContributorState state = session.contributors.get(id);
            if (state == null || state.generation != generation) return;
            state.status = "OPTIONAL_REMOVED";
            state.failureReason = "optional_contributor_removed";
            session.failureReason = "optional_contributor_removed";
            latestFailureReason = "optional_contributor_removed";
        }
    }

    long compositionStarted() {
        return timingWarnings.start();
    }

    /** Records one callback result and its redacted timing summary. */
    void compositionFinished(
            @Nonnull UUID sessionId,
            @Nonnull String contributorId,
            long generation,
            long startedAtNanos,
            @Nonnull String status,
            @Nullable String failureReason
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        String id = requireId(contributorId, "contributorId");
        String safeStatus = safeStatus(status);
        String safeReason = safeReason(failureReason);
        CommandHudTimingWarnings.Observation timing = timingWarnings.finish(
                id, generation, startedAtNanos);
        synchronized (lock) {
            if (timing.slow()) slowCompositionCount++;
            if (timing.warningEmitted()) slowWarningCount++;
            SessionState session = sessions.get(sessionId);
            if (session == null) return;
            ContributorState state = session.contributors.computeIfAbsent(id,
                    ignored -> new ContributorState(
                            new CommandHudDiagnostics.ContributorRegistration(id, generation)));
            state.record(generation, safeStatus, timing, safeReason);
            if (safeReason != null) {
                session.failureReason = safeReason;
                latestFailureReason = safeReason;
            }
        }
    }

    /** Returns a detached diagnostics snapshot with no HUD values or targets. */
    @Nonnull
    CommandHudDiagnostics snapshot() {
        synchronized (lock) {
            List<CommandHudDiagnostics.RendererRegistration> targetRenderers = new ArrayList<>();
            List<CommandHudDiagnostics.RendererRegistration> hotswapRenderers = new ArrayList<>();
            for (Map.Entry<RegistrationKey, CommandHudDiagnostics.RendererRegistration> entry
                    : renderers.entrySet()) {
                (entry.getKey().surface() == CommandHudSurface.TARGET
                        ? targetRenderers : hotswapRenderers).add(entry.getValue());
            }
            Comparator<CommandHudDiagnostics.RendererRegistration> rendererOrder =
                    Comparator.comparing(CommandHudDiagnostics.RendererRegistration::rendererId);
            targetRenderers.sort(rendererOrder);
            hotswapRenderers.sort(rendererOrder);

            List<CommandHudDiagnostics.ContributorRegistration> targetContributors = new ArrayList<>();
            List<CommandHudDiagnostics.ContributorRegistration> hotswapContributors = new ArrayList<>();
            for (Map.Entry<RegistrationKey, CommandHudDiagnostics.ContributorRegistration> entry
                    : contributors.entrySet()) {
                (entry.getKey().surface() == CommandHudSurface.TARGET
                        ? targetContributors : hotswapContributors).add(entry.getValue());
            }
            Comparator<CommandHudDiagnostics.ContributorRegistration> contributorOrder =
                    Comparator.comparing(CommandHudDiagnostics.ContributorRegistration::contributorId);
            targetContributors.sort(contributorOrder);
            hotswapContributors.sort(contributorOrder);

            List<CommandHudDiagnostics.SessionView> sessionValues = sessions.values().stream()
                    .sorted(Comparator.comparing(state -> state.sessionId.toString()))
                    .map(SessionState::view).toList();
            return new CommandHudDiagnostics(targetRenderers, hotswapRenderers,
                    targetContributors, hotswapContributors, sessionValues,
                    latestFailureReason, slowCompositionCount, slowWarningCount);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            renderers.clear();
            contributors.clear();
            sessions.clear();
            latestFailureReason = null;
            slowCompositionCount = 0L;
            slowWarningCount = 0L;
        }
        timingWarnings.clear();
    }

    @Nonnull
    private static String requireId(@Nullable String value, @Nonnull String field) {
        String normalized = normalizeId(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    @Nullable
    private static String normalizeId(@Nullable String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Nonnull
    private static String safeStatus(@Nullable String value) {
        String normalized = normalizeId(value);
        return normalized == null || !normalized.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                ? "UNKNOWN" : normalized;
    }

    @Nullable
    private static String safeReason(@Nullable String value) {
        String normalized = normalizeId(value);
        if (normalized == null) return null;
        if (normalized.startsWith("command HUD contribution")) {
            return "contribution_bounds_exceeded";
        }
        return SAFE_REASON_CODES.contains(normalized) ? normalized : "callback_failed";
    }

    private record RegistrationKey(@Nonnull CommandHudSurface surface, @Nonnull String id) {
        private RegistrationKey {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(id, "id");
        }
    }

    private final class SessionState {
        private final UUID sessionId;
        private final CommandHudSurface surface;
        @Nullable
        private final String rendererId;
        private final long rendererGeneration;
        @Nullable
        private final String itemId;
        @Nullable
        private final String configId;
        private final Map<String, ContributorState> contributors = new LinkedHashMap<>();
        @Nullable
        private String failureReason;

        private SessionState(
                UUID sessionId,
                CommandHudSurface surface,
                @Nullable String rendererId,
                long rendererGeneration,
                @Nullable String itemId,
                @Nullable String configId
        ) {
            this.sessionId = sessionId;
            this.surface = surface;
            this.rendererId = rendererId;
            this.rendererGeneration = rendererGeneration;
            this.itemId = itemId;
            this.configId = configId;
        }

        private CommandHudDiagnostics.SessionView view() {
            return new CommandHudDiagnostics.SessionView(sessionId, surface,
                    rendererId, rendererGeneration,
                    contributors.values().stream().map(ContributorState::view).toList(),
                    itemId, configId, failureReason);
        }
    }

    private static final class ContributorState {
        private final String contributorId;
        private long generation;
        private String status = "PENDING";
        private long composeCount;
        private long totalComposeNanos;
        private long lastComposeNanos;
        private long slowComposeCount;
        @Nullable
        private String failureReason;

        private ContributorState(CommandHudDiagnostics.ContributorRegistration registration) {
            contributorId = registration.contributorId();
            generation = registration.generation();
        }

        private void record(
                long generation,
                String status,
                CommandHudTimingWarnings.Observation timing,
                @Nullable String failureReason
        ) {
            this.generation = generation;
            this.status = status;
            composeCount++;
            totalComposeNanos += timing.elapsedNanos();
            lastComposeNanos = timing.elapsedNanos();
            if (timing.slow()) slowComposeCount++;
            this.failureReason = failureReason;
        }

        private CommandHudDiagnostics.ContributorView view() {
            return new CommandHudDiagnostics.ContributorView(contributorId, generation,
                    status, composeCount, totalComposeNanos, lastComposeNanos,
                    slowComposeCount, failureReason);
        }
    }
}
