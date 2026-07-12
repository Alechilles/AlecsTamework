package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Owns the singleton reconciliation scan session used to give every server process a fresh epoch
 * for mutable evidence sources.
 *
 * <p>Offsets are resumable only inside the process that owns the returned epoch. A restarted
 * process must rescan mutable saved-world and inventory sources from zero because their contents
 * can change while the process is down even when their structural catalogs are unchanged.</p>
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

    /** Acquires a fresh process session, superseding either an ACTIVE or READY prior epoch. */
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

    /**
     * Ensures the exact epoch is ACTIVE after a failed finalization fence.
     *
     * <p>The transition is idempotent: an already-ACTIVE matching epoch confirms the required
     * fail-closed state, while a stale or missing epoch is rejected.</p>
     */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<Boolean> invalidateReadyAsync(
            @Nonnull String epoch
    ) {
        String requiredEpoch = requireEpoch(epoch);
        return writeQueue.submitTracked(
                "companion_population_scan_session_invalidate_ready",
                connection -> invalidateReadyInTransaction(connection, requiredEpoch),
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
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                    UPDATE companion_population_scan_session
                    SET epoch = ?, state = 'ACTIVE', started_at_ms = ?, updated_at_ms = ?, completed_at_ms = 0
                    WHERE singleton_id = ? AND epoch = ?
                    """
        )) {
            statement.setString(1, candidateEpoch);
            statement.setLong(2, now);
            statement.setLong(3, now);
            statement.setInt(4, SINGLETON_ID);
            statement.setString(5, current.epoch());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population scan session changed during acquisition.");
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

    private static boolean invalidateReadyInTransaction(
            @Nonnull Connection connection,
            @Nonnull String epoch
    ) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_scan_session
                SET state = 'ACTIVE', updated_at_ms = ?, completed_at_ms = 0
                WHERE singleton_id = ? AND epoch = ? AND state = 'READY'
                """
        )) {
            statement.setLong(1, now);
            statement.setInt(2, SINGLETON_ID);
            statement.setString(3, epoch);
            if (statement.executeUpdate() == 1) {
                return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT 1 FROM companion_population_scan_session
                WHERE singleton_id = ? AND epoch = ? AND state = 'ACTIVE'
                """
        )) {
            statement.setInt(1, SINGLETON_ID);
            statement.setString(2, epoch);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
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
