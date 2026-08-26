package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import com.alechilles.alecstamework.api.commandhud.CommandHudOpenContext;
import com.alechilles.alecstamework.api.commandhud.CommandHudSurface;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Owns teardown for one composition's detached resources. */
final class CommandHudCompositionCleanup<B> {
    private final Object lock = new Object();
    private final List<CommandHudCompositionState<B>> states;
    @Nullable
    private final CommandHudDiagnosticsService diagnostics;
    private final UUID sessionId;
    @Nullable
    private final AutoCloseable rendererController;
    @Nullable
    private AutoCloseable rendererSubscription;
    private boolean diagnosticsSessionOpen;
    private boolean rendererClosed;
    private boolean closed;

    CommandHudCompositionCleanup(
            @Nonnull List<CommandHudCompositionState<B>> states,
            @Nullable CommandHudDiagnosticsService diagnostics,
            @Nonnull UUID sessionId,
            @Nullable AutoCloseable rendererController
    ) {
        this.states = List.copyOf(states);
        this.diagnostics = diagnostics;
        this.sessionId = sessionId;
        this.rendererController = rendererController;
    }

    void openDiagnostics(
            @Nonnull CommandHudSurface surface,
            @Nullable String rendererId,
            long rendererGeneration,
            @Nonnull CommandHudOpenContext context,
            @Nonnull List<CommandHudDiagnostics.ContributorRegistration> contributors
    ) {
        if (diagnostics == null) return;
        diagnostics.openSession(sessionId, surface, rendererId, rendererGeneration,
                context.itemId(), context.configId(), contributors);
        synchronized (lock) {
            if (!closed) diagnosticsSessionOpen = true;
        }
    }

    void setRendererSubscription(@Nullable AutoCloseable subscription) {
        if (subscription == null) return;
        boolean closeNow;
        synchronized (lock) {
            closeNow = closed;
            if (!closeNow) rendererSubscription = subscription;
        }
        if (closeNow) closeQuietly(subscription);
    }

    @Nullable
    AutoCloseable rendererController() {
        return rendererController;
    }

    void close(@Nullable String reason) {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        closeStates(states);
        closeDiagnostics(reason);
        closeRenderer();
    }

    static void closeStates(@Nonnull List<? extends CommandHudCompositionState<?>> states) {
        for (int index = states.size() - 1; index >= 0; index--) {
            CommandHudCompositionState<?> state = states.get(index);
            closeQuietly(state.unregisterSubscription);
            state.unregisterSubscription = null;
            closeQuietly(state.contributor);
            state.contributor = null;
        }
    }

    private void closeDiagnostics(@Nullable String reason) {
        if (diagnostics == null) return;
        synchronized (lock) {
            if (!diagnosticsSessionOpen) return;
            diagnosticsSessionOpen = false;
        }
        diagnostics.closeSession(sessionId, reason);
    }

    private void closeRenderer() {
        AutoCloseable subscription;
        AutoCloseable controller;
        synchronized (lock) {
            if (rendererClosed) return;
            rendererClosed = true;
            subscription = rendererSubscription;
            rendererSubscription = null;
            controller = rendererController;
        }
        closeQuietly(subscription);
        closeQuietly(controller);
    }

    static void closeQuietly(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception | LinkageError ignored) {
        }
    }
}
