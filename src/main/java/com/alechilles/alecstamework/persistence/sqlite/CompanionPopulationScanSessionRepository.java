package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Owns the singleton reconciliation scan session used to resume mutable evidence sources after a
 * restart and to rotate their epoch only after the previous scan reached READY.
 */
public final class CompanionPopulationScanSessionRepository {
    private static final int SINGLETON_ID = 1;

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CompanionPopulationScanSessionRepository(
            @Nonnull SqliteConnectionManager connectionManager,
            @Nonnull PersistenceWriteQueue writeQueue
    ) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    /** Acquires the active session, resuming it after a crash or rotating a previously READY one. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Session> acquireOrResumeAsync() {
        String candidateEpoch = UUID.randomUUID().toString();
        return writeQueue.submitTracked(
                "companion_population_scan_session_acquire",
                connection -> acquireOrResumeInTransaction(connection, candidateEpoch),
                null
        );
    }

    /** Marks the exact active epoch READY; stale epochs cannot complete a newer session. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> markReadyAsync(@Nonnull String epoch) {
        String requiredEpoch = requireEpoch(epoch);
        return writeQueue.submitTracked(
                "companion_population_scan_session_ready",
                connection -> markReadyInTransaction(connection, requiredEpoch),
                null
        );
    }

    /** Loads the current session for diagnostics and deterministic recovery tests. */
    @Nonnull
    public Session loadCurrent() throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            Session session = findCurrent(connection);
            if (session == null) {
                throw new IllegalStateException("Population scan session has not been acquired.");
            }
            return session;
        }
    }

    @Nonnull
    private static Session acquireOrResumeInTransaction(
            @Nonnull Connection connection,
            @Nonnull String candidateEpoch
    ) throws Exception {
        Session current = findCurrent(connection);
        if (current == null) {
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO companion_population_scan_session (
                        singleton_id, epoch, state, started_at_ms, updated_at_ms, completed_at_ms
                    ) VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                    """
            )) {
                statement.setInt(1, SINGLETON_ID);
                statement.setString(2, candidateEpoch);
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
            return new Session(candidateEpoch, State.ACTIVE);
        }
        if (current.state() == State.ACTIVE) {
            return current;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_scan_session
                SET epoch = ?, state = 'ACTIVE', started_at_ms = ?, updated_at_ms = ?, completed_at_ms = 0
                WHERE singleton_id = ? AND epoch = ? AND state = 'READY'
                """
        )) {
            statement.setString(1, candidateEpoch);
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.setInt(4, SINGLETON_ID);
            statement.setString(5, current.epoch());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population scan session changed during rotation.");
            }
        }
        return new Session(candidateEpoch, State.ACTIVE);
    }

    private static boolean markReadyInTransaction(
            @Nonnull Connection connection,
            @Nonnull String epoch
    ) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_scan_session
                SET state = 'READY', updated_at_ms = ?, completed_at_ms = ?
                WHERE singleton_id = ? AND epoch = ? AND state = 'ACTIVE'
                """
        )) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setInt(3, SINGLETON_ID);
            statement.setString(4, epoch);
            return statement.executeUpdate() == 1;
        }
    }

    private static Session findCurrent(@Nonnull Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT epoch, state FROM companion_population_scan_session WHERE singleton_id = ?"
        )) {
            statement.setInt(1, SINGLETON_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Session(
                        requireEpoch(resultSet.getString("epoch")),
                        State.valueOf(resultSet.getString("state"))
                );
            }
        }
    }

    @Nonnull
    private static String requireEpoch(@Nonnull String epoch) {
        String value = Objects.requireNonNull(epoch, "epoch").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("epoch must not be blank.");
        }
        return value;
    }

    public enum State {
        ACTIVE,
        READY
    }

    public record Session(@Nonnull String epoch, @Nonnull State state) {
        public Session {
            epoch = requireEpoch(epoch);
            state = Objects.requireNonNull(state, "state");
        }
    }
}
