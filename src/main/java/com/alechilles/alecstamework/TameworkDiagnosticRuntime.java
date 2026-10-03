package com.alechilles.alecstamework;

import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Owns Tamework embedded telemetry.
 */
final class TameworkDiagnosticRuntime implements AutoCloseable {

    private final CrashTelemetryService telemetry;

    private TameworkDiagnosticRuntime(@Nonnull CrashTelemetryService telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    /** Creates telemetry or leaves the plugin operational when it is unavailable. */
    @Nullable
    static TameworkDiagnosticRuntime create(@Nonnull Tamework plugin) {
        try {
            return new TameworkDiagnosticRuntime(CrashTelemetryService.create(plugin));
        } catch (Exception failure) {
            plugin.getLogger().at(Level.WARNING).withCause(failure).log(
                    "Failed to initialize Tamework embedded telemetry; "
                            + "continuing without telemetry."
            );
            return null;
        }
    }

    void start() {
        telemetry.start();
    }

    void recordStartCompleted() {
        telemetry.recordBreadcrumb("lifecycle", "Tamework start completed.");
    }

    void captureStartFailure(@Nullable Throwable failure) {
        if (failure != null) {
            telemetry.captureStartFailure(failure);
        }
    }

    void captureExceptionalWorldRemoval(@Nullable RemoveWorldEvent event) {
        if (event != null && event.getRemovalReason()
                == RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            telemetry.captureExceptionalWorldRemoval(
                    event.getWorld(), event.getRemovalReason()
            );
        }
    }

    @Nonnull
    CrashTelemetryService telemetry() {
        return telemetry;
    }

    /** Stops embedded telemetry. */
    @Override
    public void close() {
        telemetry.shutdown();
    }
}
