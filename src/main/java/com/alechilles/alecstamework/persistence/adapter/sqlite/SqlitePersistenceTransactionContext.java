package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentityPort;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecyclePort;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotPort;
import com.alechilles.alecstamework.persistence.incidents.IncidentStore;
import com.alechilles.alecstamework.persistence.operation.OperationStore;
import com.alechilles.alecstamework.persistence.projection.ProjectionOutboxPort;
import java.sql.Connection;
import javax.annotation.Nonnull;

/**
 * Focused connection-bound collaborators available to one application transaction.
 *
 * <p>This is not a service locator: it exposes only the six shared persistence authorities, all
 * bound to the same caller-owned SQLite connection.</p>
 */
public final class SqlitePersistenceTransactionContext {
    private final CompanionIdentityPort identities;
    private final CompanionLifecyclePort lifecycles;
    private final CompanionSnapshotPort snapshots;
    private final OperationStore operations;
    private final IncidentStore incidents;
    private final ProjectionOutboxPort outbox;

    public SqlitePersistenceTransactionContext(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Persistence transaction connection is required");
        }
        identities = new SqliteCompanionIdentityStore(connection);
        lifecycles = new SqliteCompanionLifecycleStore(connection);
        snapshots = new SqliteCompanionSnapshotStore(connection);
        operations = new SqliteOperationStore(connection);
        incidents = new SqliteIncidentStore(connection);
        outbox = new SqliteProjectionOutboxStore(connection);
    }

    @Nonnull
    public CompanionIdentityPort identities() {
        return identities;
    }

    @Nonnull
    public CompanionLifecyclePort lifecycles() {
        return lifecycles;
    }

    @Nonnull
    public CompanionSnapshotPort snapshots() {
        return snapshots;
    }

    @Nonnull
    public OperationStore operations() {
        return operations;
    }

    @Nonnull
    public IncidentStore incidents() {
        return incidents;
    }

    @Nonnull
    public ProjectionOutboxPort outbox() {
        return outbox;
    }
}
