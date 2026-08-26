package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Bounded internal bridge from HUD composition to detached diagnostics. */
public final class CommandHudDiagnosticsRuntime implements AutoCloseable {
    private static final int MAX_SOURCES = 2;
    private static final Object LEGACY_OWNER = new Object();
    private final Object lock = new Object();
    private final IdentityHashMap<Object, Supplier<CommandHudDiagnostics>> snapshotSuppliers =
            new IdentityHashMap<>();
    private boolean closed;

    /** Connects the legacy runtime-owned detached snapshot supplier. */
    public void connect(@Nonnull Supplier<CommandHudDiagnostics> supplier) {
        connect(LEGACY_OWNER, supplier);
    }

    /**
     * Connects one bounded, owner-scoped detached snapshot supplier.
     *
     * <p>Each live HUD surface owns one source. A source replacement keeps the
     * same owner slot, while a third owner is ignored so a temporary probe
     * cannot displace live HUD diagnostics.</p>
     */
    @Nonnull
    public AutoCloseable connect(
            @Nonnull Object owner,
            @Nonnull Supplier<CommandHudDiagnostics> supplier
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(supplier, "supplier");
        synchronized (lock) {
            if (closed) return () -> { };
            if (!snapshotSuppliers.containsKey(owner)
                    && snapshotSuppliers.size() >= MAX_SOURCES) {
                return () -> { };
            }
            snapshotSuppliers.put(owner, supplier);
        }
        return () -> disconnect(owner, supplier);
    }

    /** Returns the current detached runtime snapshot. */
    @Nonnull
    public CommandHudDiagnostics snapshot() {
        List<Supplier<CommandHudDiagnostics>> suppliers;
        synchronized (lock) {
            if (closed || snapshotSuppliers.isEmpty()) {
                return CommandHudDiagnostics.empty();
            }
            suppliers = List.copyOf(snapshotSuppliers.values());
        }
        List<CommandHudDiagnostics> snapshots = new ArrayList<>(suppliers.size());
        for (Supplier<CommandHudDiagnostics> supplier : suppliers) {
            try {
                CommandHudDiagnostics snapshot = supplier.get();
                if (snapshot != null) snapshots.add(snapshot);
            } catch (RuntimeException | LinkageError ignored) {
                // One disconnected or failing surface must not hide another.
            }
        }
        return merge(snapshots);
    }

    /** Disconnects the runtime supplier. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            snapshotSuppliers.clear();
        }
    }

    private void disconnect(
            @Nonnull Object owner,
            @Nonnull Supplier<CommandHudDiagnostics> supplier
    ) {
        synchronized (lock) {
            if (snapshotSuppliers.get(owner) == supplier) {
                snapshotSuppliers.remove(owner);
            }
        }
    }

    @Nonnull
    private static CommandHudDiagnostics merge(
            @Nonnull List<CommandHudDiagnostics> snapshots
    ) {
        Map<String, CommandHudDiagnostics.RendererRegistration> targetRenderers =
                new LinkedHashMap<>();
        Map<String, CommandHudDiagnostics.RendererRegistration> hotswapRenderers =
                new LinkedHashMap<>();
        Map<String, CommandHudDiagnostics.ContributorRegistration> targetContributors =
                new LinkedHashMap<>();
        Map<String, CommandHudDiagnostics.ContributorRegistration> hotswapContributors =
                new LinkedHashMap<>();
        Map<java.util.UUID, CommandHudDiagnostics.SessionView> sessions = new LinkedHashMap<>();
        String latestFailureReason = null;
        long slowCompositionCount = 0L;
        long slowWarningCount = 0L;
        for (CommandHudDiagnostics snapshot : snapshots) {
            addRenderers(targetRenderers, snapshot.targetRenderers());
            addRenderers(hotswapRenderers, snapshot.hotswapRenderers());
            addContributors(targetContributors, snapshot.targetContributors());
            addContributors(hotswapContributors, snapshot.hotswapContributors());
            for (CommandHudDiagnostics.SessionView session : snapshot.sessions()) {
                sessions.putIfAbsent(session.sessionId(), session);
            }
            if (snapshot.latestFailureReason() != null) {
                latestFailureReason = snapshot.latestFailureReason();
            }
            slowCompositionCount += snapshot.slowCompositionCount();
            slowWarningCount += snapshot.slowWarningCount();
        }
        List<CommandHudDiagnostics.RendererRegistration> targetRendererValues =
                sortedRenderers(targetRenderers);
        List<CommandHudDiagnostics.RendererRegistration> hotswapRendererValues =
                sortedRenderers(hotswapRenderers);
        List<CommandHudDiagnostics.ContributorRegistration> targetContributorValues =
                sortedContributors(targetContributors);
        List<CommandHudDiagnostics.ContributorRegistration> hotswapContributorValues =
                sortedContributors(hotswapContributors);
        List<CommandHudDiagnostics.SessionView> sessionValues = sessions.values().stream()
                .sorted(Comparator.comparing(session -> session.sessionId().toString()))
                .toList();
        return new CommandHudDiagnostics(targetRendererValues, hotswapRendererValues,
                targetContributorValues, hotswapContributorValues, sessionValues,
                latestFailureReason, slowCompositionCount, slowWarningCount);
    }

    private static void addRenderers(
            @Nonnull Map<String, CommandHudDiagnostics.RendererRegistration> target,
            @Nonnull List<CommandHudDiagnostics.RendererRegistration> values
    ) {
        for (CommandHudDiagnostics.RendererRegistration value : values) {
            target.putIfAbsent(value.rendererId(), value);
        }
    }

    private static void addContributors(
            @Nonnull Map<String, CommandHudDiagnostics.ContributorRegistration> target,
            @Nonnull List<CommandHudDiagnostics.ContributorRegistration> values
    ) {
        for (CommandHudDiagnostics.ContributorRegistration value : values) {
            target.putIfAbsent(value.contributorId(), value);
        }
    }

    @Nonnull
    private static List<CommandHudDiagnostics.RendererRegistration> sortedRenderers(
            @Nonnull Map<String, CommandHudDiagnostics.RendererRegistration> values
    ) {
        return values.values().stream()
                .sorted(Comparator.comparing(CommandHudDiagnostics.RendererRegistration::rendererId))
                .toList();
    }

    @Nonnull
    private static List<CommandHudDiagnostics.ContributorRegistration> sortedContributors(
            @Nonnull Map<String, CommandHudDiagnostics.ContributorRegistration> values
    ) {
        return values.values().stream()
                .sorted(Comparator.comparing(CommandHudDiagnostics.ContributorRegistration::contributorId))
                .toList();
    }
}
