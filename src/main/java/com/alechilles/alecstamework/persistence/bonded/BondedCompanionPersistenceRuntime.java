package com.alechilles.alecstamework.persistence.bonded;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;

/** Idempotent lifecycle wrapper for standalone bonded persistence startup. */
public final class BondedCompanionPersistenceRuntime implements AutoCloseable {
    private final BondedCompanionSchemaManager schemas;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile BondedCompanionPersistenceReadiness readiness =
            BondedCompanionPersistenceReadiness.failed(
                    "bonded-persistence-not-started"
            );

    public BondedCompanionPersistenceRuntime(
            @Nonnull BondedCompanionSchemaManager schemas
    ) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    /** Initializes the bonded schema once and returns the stable startup result. */
    @Nonnull
    public synchronized BondedCompanionPersistenceReadiness start() {
        if (closed.get()) {
            return readiness;
        }
        if (started.compareAndSet(false, true)) {
            readiness = schemas.initialize();
        }
        return readiness;
    }

    /** Returns the latest bonded-only readiness diagnostic. */
    @Nonnull
    public BondedCompanionPersistenceReadiness readiness() {
        return readiness;
    }

    /** Closes idempotently; connections are operation-scoped and need no shared shutdown. */
    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            readiness = BondedCompanionPersistenceReadiness.failed(
                    "bonded-persistence-closed"
            );
        }
    }
}
