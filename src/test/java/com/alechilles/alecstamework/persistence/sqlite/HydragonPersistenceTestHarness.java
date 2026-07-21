package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Shared isolated SQLite writer harness for schema-v8 integration repository tests. */
final class HydragonPersistenceTestHarness implements AutoCloseable {
    final SqliteConnectionManager connections;
    final PersistenceWriteQueue queue;

    HydragonPersistenceTestHarness(Path databasePath) throws Exception {
        connections = new SqliteConnectionManager(databasePath);
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.commit();
        }
        queue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
    }

    String insertProfile(UUID ownerUuid, String roleId, String lifecycle,
                         String ownershipWorld, long populationRevision) throws Exception {
        String profileId = UUID.randomUUID().toString();
        try (Connection connection = connections.openConnection();
             PreparedStatement profile = connection.prepareStatement("""
                     INSERT INTO npc_profiles (
                         profile_id, owner_uuid, role_id, last_world_name,
                         created_at_ms, updated_at_ms, last_active_at_ms
                     ) VALUES (?, ?, ?, ?, 1, 1, 1)
                     """);
             PreparedStatement population = connection.prepareStatement("""
                     INSERT INTO companion_population_state (
                         profile_id, ownership_world_name, lifecycle_state,
                         revision, source, created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, ?, 'test', 1, 1)
                     """)) {
            profile.setString(1, profileId);
            profile.setString(2, ownerUuid == null ? null : ownerUuid.toString());
            profile.setString(3, roleId);
            profile.setString(4, ownershipWorld);
            profile.executeUpdate();
            population.setString(1, profileId);
            population.setString(2, ownershipWorld);
            population.setString(3, lifecycle);
            population.setLong(4, populationRevision);
            population.executeUpdate();
        }
        return profileId;
    }

    static <T> T await(PersistenceWriteQueue.WriteSubmission<T> submission) throws Exception {
        PersistenceWriteQueue.WriteOutcome<T> outcome = submission.completion().get(5, TimeUnit.SECONDS);
        if (!outcome.isCommitted()) {
            throw new AssertionError("Write did not commit: " + outcome.failureReason(), outcome.failure());
        }
        return outcome.value();
    }

    @Override
    public void close() {
        queue.close();
    }
}
