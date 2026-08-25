package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandui.CommandUiDiagnostics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns process-local command UI diagnostics state and produces redacted
 * immutable snapshots.
 *
 * <p>The service stores only registration identities, session identities,
 * status codes, counts, durations, and safe reason codes. It does not retain
 * snapshots, action handles, input values, or contributor data.</p>
 */
public final class CommandUiDiagnosticsService implements AutoCloseable {
    private static final Set<String> SAFE_REASON_CODES = Set.of(
            "initial_composition_failed",
            "required_composition_failed",
            "required_contributor_removed",
            "optional_contributor_removed",
            "contribution_bounds_exceeded",
            "callback_failed"
    );
    private final Object lock = new Object();
    private final CommandUiContributorTimingWarnings timingWarnings;
    private final Map<String, CommandUiDiagnostics.RendererRegistration> renderers =
            new LinkedHashMap<>();
    private final Map<String, CommandUiDiagnostics.ContributorRegistration> contributors =
            new LinkedHashMap<>();
    private final Map<UUID, SessionState> sessions = new LinkedHashMap<>();
    @Nullable
    private String latestFailureReason;
    private long slowCompositionCount;
    private long slowWarningCount;

    /** Creates a service backed by the monotonic system clock. */
    public CommandUiDiagnosticsService() {
        this(System::nanoTime, null);
    }

    /** Creates a service with an injected monotonic clock. */
    public CommandUiDiagnosticsService(@Nonnull LongSupplier nanoTime) {
        this(nanoTime, null);
    }

    /** Creates a service with an injected clock and warning sink. */
    public CommandUiDiagnosticsService(
            @Nonnull LongSupplier nanoTime,
            @Nullable Consumer<CommandUiContributorTimingWarnings.Warning>
                    warningSink
    ) {
        this.timingWarnings = new CommandUiContributorTimingWarnings(
                Objects.requireNonNull(nanoTime, "nanoTime"), warningSink);
    }

    /** Records one live renderer registration. */
    public void registerRenderer(@Nonnull String rendererId, long generation) {
        CommandUiDiagnostics.RendererRegistration registration =
                new CommandUiDiagnostics.RendererRegistration(rendererId,
                        generation);
        synchronized (lock) {
            renderers.put(registration.rendererId(), registration);
        }
    }

    /** Removes one renderer only when its generation still matches. */
    public void unregisterRenderer(@Nonnull String rendererId, long generation) {
        String id = requireId(rendererId, "rendererId");
        synchronized (lock) {
            CommandUiDiagnostics.RendererRegistration current = renderers.get(id);
            if (current != null && current.generation() == generation) {
                renderers.remove(id);
            }
        }
    }

    /** Records one live contributor registration. */
    public void registerContributor(@Nonnull String contributorId,
                                    long generation) {
        CommandUiDiagnostics.ContributorRegistration registration =
                new CommandUiDiagnostics.ContributorRegistration(contributorId,
                        generation);
        synchronized (lock) {
            contributors.put(registration.contributorId(), registration);
        }
    }

    /** Removes one contributor only when its generation still matches. */
    public void unregisterContributor(@Nonnull String contributorId,
                                      long generation) {
        String id = requireId(contributorId, "contributorId");
        synchronized (lock) {
            CommandUiDiagnostics.ContributorRegistration current =
                    contributors.get(id);
            if (current != null && current.generation() == generation) {
                contributors.remove(id);
            }
        }
    }

    /**
     * Starts diagnostics for one active custom session.
     *
     * <p>The contributor list contains only configured IDs and exact
     * registration generations. It is copied before being retained.</p>
     */
    public void openSession(
            @Nonnull UUID sessionId,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nullable String itemId,
            @Nullable String configId,
            @Nullable List<CommandUiDiagnostics.ContributorRegistration>
                    selectedContributors
    ) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (rendererGeneration < 0L) {
            throw new IllegalArgumentException(
                    "Renderer generation cannot be negative.");
        }
        String safeRendererId = normalizeId(rendererId);
        List<CommandUiDiagnostics.ContributorRegistration> selected =
                selectedContributors == null ? List.of()
                        : List.copyOf(selectedContributors);
        synchronized (lock) {
            SessionState state = new SessionState(sessionId, safeRendererId,
                    rendererGeneration, normalizeId(itemId), normalizeId(configId));
            for (CommandUiDiagnostics.ContributorRegistration contributor
                    : selected) {
                Objects.requireNonNull(contributor, "selected contributor");
                state.contributors.put(contributor.contributorId(),
                        new ContributorState(contributor));
            }
            sessions.put(sessionId, state);
        }
    }

    /** Closes one active session and records a safe fallback reason. */
    public void closeSession(@Nonnull UUID sessionId, @Nullable String reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        String safeReason = safeReason(reason);
        synchronized (lock) {
            if (sessions.remove(sessionId) != null && safeReason != null) {
                latestFailureReason = safeReason;
            }
        }
    }

    /** Records a safe session failure without retaining its private cause. */
    public void recordSessionFailure(@Nonnull UUID sessionId,
                                     @Nullable String reason) {
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
    public void contributorRemoved(
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

    /** Captures a monotonic callback start time. */
    public long compositionStarted() {
        return timingWarnings.start();
    }

    /**
     * Records one completed contributor composition and its safe timing
     * summary. Slow observations count even when warning emission is throttled.
     */
    public void compositionFinished(
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
        CommandUiContributorTimingWarnings.Observation timing =
                timingWarnings.finish(id, generation, startedAtNanos);
        synchronized (lock) {
            if (timing.slow()) slowCompositionCount++;
            if (timing.warningEmitted()) slowWarningCount++;
            SessionState session = sessions.get(sessionId);
            if (session == null) return;
            ContributorState state = session.contributors.computeIfAbsent(id,
                    ignored -> new ContributorState(
                            new CommandUiDiagnostics.ContributorRegistration(
                                    id, generation)));
            state.record(generation, safeStatus, timing, safeReason);
            if (safeReason != null) {
                session.failureReason = safeReason;
                latestFailureReason = safeReason;
            }
        }
    }

    /** Returns a detached immutable diagnostics snapshot. */
    @Nonnull
    public CommandUiDiagnostics snapshot() {
        synchronized (lock) {
            List<CommandUiDiagnostics.RendererRegistration> rendererValues =
                    new ArrayList<>(renderers.values());
            rendererValues.sort(Comparator.comparing(
                    CommandUiDiagnostics.RendererRegistration::rendererId));
            List<CommandUiDiagnostics.ContributorRegistration> contributorValues =
                    new ArrayList<>(contributors.values());
            contributorValues.sort(Comparator.comparing(
                    CommandUiDiagnostics.ContributorRegistration::contributorId));
            List<CommandUiDiagnostics.SessionView> sessionValues = sessions.values()
                    .stream().sorted(Comparator.comparing(
                            state -> state.sessionId.toString()))
                    .map(SessionState::view)
                    .toList();
            return new CommandUiDiagnostics(rendererValues, contributorValues,
                    sessionValues, latestFailureReason, slowCompositionCount,
                    slowWarningCount);
        }
    }

    /** Releases all process-local diagnostics state. */
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
    private static String requireId(@Nullable String value, String field) {
        String normalized = normalizeId(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
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
        if (normalized == null || !normalized.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            return "UNKNOWN";
        }
        return normalized;
    }

    @Nullable
    private static String safeReason(@Nullable String value) {
        String normalized = normalizeId(value);
        if (normalized == null) return null;
        if (normalized.startsWith("command UI contribution")) {
            return "contribution_bounds_exceeded";
        }
        if (SAFE_REASON_CODES.contains(normalized)) {
            return normalized;
        }
        return "callback_failed";
    }

    private final class SessionState {
        private final UUID sessionId;
        @Nullable
        private final String rendererId;
        private final long rendererGeneration;
        @Nullable
        private final String itemId;
        @Nullable
        private final String configId;
        private final Map<String, ContributorState> contributors =
                new LinkedHashMap<>();
        @Nullable
        private String failureReason;

        private SessionState(
                UUID sessionId,
                @Nullable String rendererId,
                long rendererGeneration,
                @Nullable String itemId,
                @Nullable String configId
        ) {
            this.sessionId = sessionId;
            this.rendererId = rendererId;
            this.rendererGeneration = rendererGeneration;
            this.itemId = itemId;
            this.configId = configId;
        }

        private CommandUiDiagnostics.SessionView view() {
            return new CommandUiDiagnostics.SessionView(sessionId, rendererId,
                    rendererGeneration,
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

        private ContributorState(
                CommandUiDiagnostics.ContributorRegistration registration
        ) {
            this.contributorId = registration.contributorId();
            this.generation = registration.generation();
        }

        private void record(
                long generation,
                String status,
                CommandUiContributorTimingWarnings.Observation timing,
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

        private CommandUiDiagnostics.ContributorView view() {
            return new CommandUiDiagnostics.ContributorView(contributorId,
                    generation, status, composeCount, totalComposeNanos,
                    lastComposeNanos, slowComposeCount, failureReason);
        }
    }
}
