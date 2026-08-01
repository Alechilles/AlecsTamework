package com.alechilles.alecstamework.integration.patchwork;

import com.alechilles.patchwork.embedded.EmbeddedPatchworkBootstrap;
import com.alechilles.patchwork.embedded.EmbeddedPatchworkService;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Owns Tamework's embedded Patchwork service lifecycle until the host contribution and reload bridge
 * are installed.
 */
public final class TameworkPatchworkRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Function<JavaPlugin, EmbeddedPatchworkService> bootstrap;
    private EmbeddedPatchworkService service;
    private boolean closed;

    /** Creates a runtime that uses Patchwork's production embedded bootstrap. */
    public TameworkPatchworkRuntime(JavaPlugin plugin) {
        this(plugin, EmbeddedPatchworkBootstrap::bootstrap);
    }

    TameworkPatchworkRuntime(
            JavaPlugin plugin,
            Function<JavaPlugin, EmbeddedPatchworkService> bootstrap) {
        this.plugin = plugin;
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    /** Starts the embedded service once and makes its elected generated root available. */
    public synchronized void start() {
        if (service != null) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("Embedded Patchwork runtime is closed.");
        }

        EmbeddedPatchworkService candidate = Objects.requireNonNull(
                bootstrap.apply(plugin),
                "Embedded Patchwork bootstrap returned no service."
        );
        try {
            candidate.start();
        } catch (RuntimeException | Error exception) {
            closeFailedCandidate(candidate, exception);
            throw exception;
        }

        service = candidate;
    }

    /** Returns the generated root only while the embedded service remains active. */
    public synchronized Path generatedPatchRoot() {
        if (service == null) {
            throw new IllegalStateException("Embedded Patchwork is not active.");
        }
        return Objects.requireNonNull(
                service.generatedPatchRoot(),
                "Active Patchwork runtime returned no generated root."
        );
    }

    /**
     * Closes the active embedded service. A failed close deliberately retains the service so a later
     * shutdown attempt can retry it.
     */
    @Override
    public synchronized void close() {
        if (service == null) {
            closed = true;
            return;
        }

        service.close();
        service = null;
        closed = true;
    }

    private static void closeFailedCandidate(EmbeddedPatchworkService candidate, Throwable startFailure) {
        try {
            candidate.close();
        } catch (RuntimeException | Error closeFailure) {
            startFailure.addSuppressed(closeFailure);
        }
    }
}
