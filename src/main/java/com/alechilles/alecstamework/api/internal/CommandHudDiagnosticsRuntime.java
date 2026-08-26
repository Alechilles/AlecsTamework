package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.commandhud.CommandHudDiagnostics;
import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Bounded internal bridge from HUD composition to detached diagnostics. */
public final class CommandHudDiagnosticsRuntime implements AutoCloseable {
    private final Object lock = new Object();
    private Supplier<CommandHudDiagnostics> snapshotSupplier = CommandHudDiagnostics::empty;
    private boolean closed;

    /** Connects the single runtime-owned detached snapshot supplier. */
    public void connect(@Nonnull Supplier<CommandHudDiagnostics> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        synchronized (lock) {
            if (closed) return;
            snapshotSupplier = supplier;
        }
    }

    /** Returns the current detached runtime snapshot. */
    @Nonnull
    public CommandHudDiagnostics snapshot() {
        Supplier<CommandHudDiagnostics> supplier;
        synchronized (lock) {
            supplier = closed ? CommandHudDiagnostics::empty : snapshotSupplier;
        }
        CommandHudDiagnostics snapshot = supplier.get();
        return snapshot == null ? CommandHudDiagnostics.empty() : snapshot;
    }

    /** Disconnects the runtime supplier. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            snapshotSupplier = CommandHudDiagnostics::empty;
        }
    }
}
