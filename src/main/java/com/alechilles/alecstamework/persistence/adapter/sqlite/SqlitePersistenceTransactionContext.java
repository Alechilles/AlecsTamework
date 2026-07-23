package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataPort;
import com.alechilles.alecstamework.companion.command.CommandRosterPort;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeasePort;
import com.alechilles.alecstamework.companion.coop.CompanionCoopPort;
import com.alechilles.alecstamework.companion.identity.CompanionIdentityPort;
import com.alechilles.alecstamework.companion.identity.CompanionToolLinkPort;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecyclePort;
import com.alechilles.alecstamework.companion.population.OwnerPopulationPort;
import com.alechilles.alecstamework.companion.population.OwnerPopulationEvidencePort;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPort;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotPort;
import com.alechilles.alecstamework.persistence.compensation.RefundClaimPort;
import com.alechilles.alecstamework.persistence.incidents.IncidentStore;
import com.alechilles.alecstamework.persistence.operation.OperationStore;
import com.alechilles.alecstamework.persistence.projection.ProjectionOutboxPort;
import java.sql.Connection;
import javax.annotation.Nonnull;

/**
 * Focused connection-bound collaborators available to one application transaction.
 *
 * <p>This is not a service locator: its public surface exposes only the six shared authorities.
 * Package-private feature details remain bound to the same caller-owned SQLite connection.</p>
 */
public final class SqlitePersistenceTransactionContext {
    private final CompanionIdentityPort identities;
    private final CompanionLifecyclePort lifecycles;
    private final CompanionSnapshotPort snapshots;
    private final OperationStore operations;
    private final IncidentStore incidents;
    private final ProjectionOutboxPort outbox;
    private final ProfileExtensionDataPort profileExtensions;
    private final CompanionToolLinkPort toolLinks;
    private final RefundClaimPort refunds;
    private final CompanionCoopPort coops;
    private final OwnerPopulationPort population;
    private final OwnerPopulationEvidencePort populationEvidence;
    private final PopulationGroupPort populationGroups;
    private final CommandRosterPort commandRosters;
    private final TimedSummonLeasePort timedSummons;

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
        profileExtensions = new SqliteProfileExtensionDataStore(connection);
        toolLinks = new SqliteCompanionToolLinkStore(connection);
        refunds = new SqliteRefundClaimStore(connection);
        coops = new SqliteCompanionCoopStore(connection);
        population = new SqliteOwnerPopulationStore(connection);
        populationEvidence = new SqliteOwnerPopulationEvidenceStore(connection);
        populationGroups = new SqlitePopulationGroupStore(connection);
        commandRosters = new SqliteCommandRosterStore(connection);
        timedSummons = new SqliteTimedSummonLeaseStore(connection);
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

    /*
     * Feature detail is intentionally package-private: operation adapters may compose it inside
     * the shared transaction, but the context's public surface remains the six core authorities.
     */
    @Nonnull
    ProfileExtensionDataPort profileExtensions() {
        return profileExtensions;
    }

    @Nonnull
    CompanionToolLinkPort toolLinks() {
        return toolLinks;
    }

    @Nonnull
    RefundClaimPort refunds() {
        return refunds;
    }

    @Nonnull
    CompanionCoopPort coops() {
        return coops;
    }

    @Nonnull
    OwnerPopulationPort population() {
        return population;
    }

    @Nonnull
    OwnerPopulationEvidencePort populationEvidence() {
        return populationEvidence;
    }

    @Nonnull
    PopulationGroupPort populationGroups() {
        return populationGroups;
    }

    @Nonnull
    CommandRosterPort commandRosters() {
        return commandRosters;
    }

    @Nonnull
    TimedSummonLeasePort timedSummons() {
        return timedSummons;
    }
}
