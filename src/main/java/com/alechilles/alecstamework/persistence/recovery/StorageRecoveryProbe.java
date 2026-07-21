package com.alechilles.alecstamework.persistence.recovery;

import com.alechilles.alecstamework.persistence.health.PersistenceStorageHealthService;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceFeatureCircuitRepository;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRegistry;
import com.alechilles.alecstamework.persistence.incidents.PersistenceQuarantineRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceIntegrityService;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import com.alechilles.alecstamework.persistence.sqlite.SqliteSchemaMigrator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Performs the real-write, integrity, durable-denial, and index-publication gates for recovery. */
public final class StorageRecoveryProbe {
    private final String bootId;
    private final SqliteConnectionManager connections;
    private final SqliteSchemaMigrator schemaMigrator;
    private final PersistenceIntegrityService integrity;
    private final PersistenceStorageHealthService storageHealth;
    private final PersistenceQuarantineRepository quarantineRepository;
    private final PersistenceQuarantineRegistry quarantines;
    private final PersistenceFeatureCircuitRepository circuitRepository;
    private final PersistenceFeatureCircuitRegistry circuits;
    private final List<StorageRecoveryIndexPublisher> indexPublishers;

    public StorageRecoveryProbe(@Nonnull String bootId,
                                @Nonnull SqliteConnectionManager connections,
                                @Nonnull SqliteSchemaMigrator schemaMigrator,
                                @Nonnull PersistenceIntegrityService integrity,
                                @Nonnull PersistenceStorageHealthService storageHealth,
                                @Nonnull PersistenceQuarantineRepository quarantineRepository,
                                @Nonnull PersistenceQuarantineRegistry quarantines,
                                @Nonnull PersistenceFeatureCircuitRepository circuitRepository,
                                @Nonnull PersistenceFeatureCircuitRegistry circuits,
                                @Nonnull List<StorageRecoveryIndexPublisher> indexPublishers) {
        this.bootId = bootId;
        this.connections = connections;
        this.schemaMigrator = schemaMigrator;
        this.integrity = integrity;
        this.storageHealth = storageHealth;
        this.quarantineRepository = quarantineRepository;
        this.quarantines = quarantines;
        this.circuitRepository = circuitRepository;
        this.circuits = circuits;
        this.indexPublishers = List.copyOf(indexPublishers);
    }

    @Nonnull
    public ProbeResult probe() {
        if (!storageHealth.beginRecovery()) {
            return ProbeResult.notStarted("storage_not_read_only");
        }
        try {
            long revision = executeDurableProbe();
            PersistenceIntegrityService.IntegrityReport report = integrity.inspect();
            if (!report.isClean()) return fail("storage_integrity_check_failed", report.failure());
            reloadDurableDenials();
            for (StorageRecoveryIndexPublisher publisher : indexPublishers) publisher.publish();
            if (!storageHealth.completeRecovery()) return fail("storage_recovery_transition_failed", null);
            return new ProbeResult(ProbeStatus.RECOVERED, "recovered", revision, null);
        } catch (Exception failure) {
            return fail("storage_recovery_probe_failed", failure);
        }
    }

    private long executeDurableProbe() throws Exception {
        try (Connection connection = connections.openConnection()) {
            if (!schemaMigrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8)) {
                throw new IllegalStateException("schema_v8_unavailable");
            }
            connection.setAutoCommit(false);
            try {
                long revision = incrementProbe(connection);
                connection.commit();
                return revision;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private long incrementProbe(Connection connection) throws Exception {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE persistence_storage_probe
                SET revision = revision + 1, updated_at_ms = ?, last_boot_id = ?
                WHERE probe_id = 1
                """)) {
            update.setLong(1, System.currentTimeMillis());
            update.setString(2, bootId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("storage_probe_row_missing");
        }
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT revision, last_boot_id FROM persistence_storage_probe WHERE probe_id = 1");
             ResultSet result = read.executeQuery()) {
            if (!result.next() || !bootId.equals(result.getString("last_boot_id"))) {
                throw new IllegalStateException("storage_probe_readback_failed");
            }
            return result.getLong("revision");
        }
    }

    private void reloadDurableDenials() throws Exception {
        quarantines.reload(quarantineRepository.listActive());
        circuits.reload(circuitRepository.load());
    }

    private ProbeResult fail(String reason, Throwable failure) {
        storageHealth.failRecovery(reason);
        return new ProbeResult(ProbeStatus.RETAINED_READ_ONLY, reason, 0L, failure);
    }

    public enum ProbeStatus {
        RECOVERED,
        RETAINED_READ_ONLY,
        NOT_STARTED
    }

    public record ProbeResult(@Nonnull ProbeStatus status,
                              @Nonnull String reason,
                              long probeRevision,
                              @Nullable Throwable failure) {
        private static ProbeResult notStarted(String reason) {
            return new ProbeResult(ProbeStatus.NOT_STARTED, reason, 0L, null);
        }

        public boolean recovered() {
            return status == ProbeStatus.RECOVERED;
        }
    }
}
