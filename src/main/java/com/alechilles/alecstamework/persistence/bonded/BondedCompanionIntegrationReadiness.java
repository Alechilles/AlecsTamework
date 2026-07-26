package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionAvailability;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Tracks the later-task capture and panel surfaces required to advertise the API. */
public final class BondedCompanionIntegrationReadiness implements AutoCloseable {
    private final AtomicBoolean capture = new AtomicBoolean();
    private final AtomicBoolean panel = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Nonnull public AutoCloseable registerCapture() {
        return register(capture, "capture");
    }

    @Nonnull public AutoCloseable registerPanel() {
        return register(panel, "panel");
    }

    @Nonnull
    public BondedCompanionAvailability availability(
            @Nonnull BondedCompanionPersistenceReadiness persistence
    ) {
        if (!persistence.availability().available()) {
            return persistence.availability();
        }
        if (closed.get()) {
            return BondedCompanionAvailability.unavailable(
                    "bonded-companion-authority-closed"
            );
        }
        if (!capture.get()) {
            return BondedCompanionAvailability.unavailable(
                    "bonded-capture-integration-unavailable"
            );
        }
        if (!panel.get()) {
            return BondedCompanionAvailability.unavailable(
                    "bonded-panel-integration-unavailable"
            );
        }
        return BondedCompanionAvailability.availableNow();
    }

    private AutoCloseable register(AtomicBoolean surface, String name) {
        if (closed.get() || !surface.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "Bonded " + name + " integration is unavailable or registered"
            );
        }
        AtomicBoolean registrationClosed = new AtomicBoolean();
        return () -> {
            if (registrationClosed.compareAndSet(false, true)) {
                surface.set(false);
            }
        };
    }

    @Override public void close() {
        closed.set(true);
        capture.set(false);
        panel.set(false);
    }
}
