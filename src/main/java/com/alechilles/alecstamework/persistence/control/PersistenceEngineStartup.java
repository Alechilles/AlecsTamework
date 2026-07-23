package com.alechilles.alecstamework.persistence.control;

import java.nio.file.Path;
import javax.annotation.Nonnull;

/**
 * Failure-safe ownership handoff for a persistence engine during startup.
 *
 * <p>Before handoff, closing this guard closes the registered runtime or the
 * lease. After handoff, the runtime exclusively owns the lease.</p>
 */
public final class PersistenceEngineStartup implements AutoCloseable {
    private final PersistenceEngineLease lease;
    private AutoCloseable runtimeOwner;
    private boolean transferred;

    private PersistenceEngineStartup(PersistenceEngineLease lease) {
        this.lease = lease;
    }

    /** Acquires a legacy engine lease before any legacy persistence resource opens. */
    @Nonnull
    public static PersistenceEngineStartup acquireLegacy(@Nonnull Path dataDirectory) {
        return new PersistenceEngineStartup(
                PersistenceEngineLease.acquireLegacy(dataDirectory)
        );
    }

    /** Returns the lease that must be stored by the eventual runtime owner. */
    @Nonnull
    public PersistenceEngineLease lease() {
        return lease;
    }

    /** Registers the fully constructed runtime for cleanup if later startup fails. */
    public void registerOwner(@Nonnull AutoCloseable owner) {
        if (owner == null || runtimeOwner != null || transferred) {
            throw new IllegalStateException(
                    "persistence_engine_startup_owner_invalid"
            );
        }
        runtimeOwner = owner;
    }

    /** Publishes startup and transfers lease ownership to the registered runtime. */
    public void publishAndTransfer() {
        if (runtimeOwner == null || transferred) {
            throw new IllegalStateException(
                    "persistence_engine_startup_transfer_invalid"
            );
        }
        lease.publishStartupComplete();
        transferred = true;
    }

    @Override
    public void close() {
        if (transferred) {
            return;
        }
        try {
            if (runtimeOwner != null) {
                runtimeOwner.close();
            } else {
                lease.close();
            }
        } catch (Exception failure) {
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "persistence_engine_startup_cleanup_failed",
                            failure
                    );
        }
    }
}
