package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCheckpoint;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionOutboxPort;
import com.alechilles.alecstamework.persistence.projection.ProjectionSequence;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound SQLite adapter for the non-compacting replacement projection outbox.
 */
public final class SqliteProjectionOutboxStore implements ProjectionOutboxPort {
    private static final String SELECT_EVENT = """
            event_sequence, operation_id, event_type, aggregate_id, aggregate_revision,
            payload_version, payload_json, created_at_ms
            """;

    private final Connection connection;

    public SqliteProjectionOutboxStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Projection outbox connection is required");
        }
        this.connection = connection;
    }

    @Override
    public PersistenceMutationResult<ProjectionEvent> append(ProjectionEventDraft event) {
        require(event, "Projection event");
        Optional<ProjectionEvent> existing = findLogicalEvent(event);
        if (existing.isPresent()) {
            return matches(existing.get(), event)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projection_outbox(
                    operation_id, event_type, aggregate_id, aggregate_revision,
                    payload_version, payload_json, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, event.operationId().toString());
            statement.setString(2, event.eventType().toString());
            statement.setString(3, event.aggregateId());
            statement.setLong(4, event.aggregateRevision());
            statement.setInt(5, event.payloadVersion());
            statement.setString(6, event.payloadJson());
            statement.setLong(7, event.createdAtMs());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("projection_sequence_missing");
                }
                return PersistenceMutationResult.applied(new ProjectionEvent(
                        new ProjectionSequence(keys.getLong(1)),
                        event.operationId(), event.eventType(), event.aggregateId(),
                        event.aggregateRevision(), event.payloadVersion(),
                        event.payloadJson(), event.createdAtMs()
                ));
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_append", failure);
        }
    }

    @Override
    public List<ProjectionEvent> readAfter(ProjectionSequence sequence, int limit) {
        require(sequence, "Projection sequence");
        if (limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException("Projection read limit must be between 1 and 10000");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_EVENT + """
                         FROM projection_outbox
                         WHERE event_sequence > ?
                         ORDER BY event_sequence
                         LIMIT ?
                        """)) {
            statement.setLong(1, sequence.value());
            statement.setInt(2, limit);
            ArrayList<ProjectionEvent> events = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    events.add(readEvent(row));
                }
            }
            return List.copyOf(events);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_read_after", failure);
        }
    }

    /** Reads only subscribed events inside the supplied bounded sequence range. */
    @Nonnull
    public List<ProjectionEvent> readSubscribedAfter(
            @Nonnull ProjectionSequence sequence,
            @Nonnull ProjectionSequence target,
            @Nonnull ProjectionSubscription subscription,
            int limit
    ) {
        require(sequence, "Projection sequence");
        require(target, "Projection target");
        require(subscription, "Projection subscription");
        if (limit <= 0 || limit > 10_000) {
            throw new IllegalArgumentException(
                    "Projection read limit must be between 1 and 10000"
            );
        }
        if (target.compareTo(sequence) <= 0) {
            return List.of();
        }
        boolean wildcard = subscription.wildcard();
        String predicate = wildcard
                ? ""
                : " AND event_type IN ("
                        + String.join(",", Collections.nCopies(
                                subscription.eventTypes().size(), "?"
                        ))
                        + ")";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_EVENT + """
                         FROM projection_outbox
                         WHERE event_sequence > ?
                           AND event_sequence <= ?
                        """ + predicate + """
                         ORDER BY event_sequence
                         LIMIT ?
                        """)) {
            int parameter = 1;
            statement.setLong(parameter++, sequence.value());
            statement.setLong(parameter++, target.value());
            if (!wildcard) {
                for (ProjectionEventType eventType : subscription.eventTypes()) {
                    statement.setString(parameter++, eventType.toString());
                }
            }
            statement.setInt(parameter, limit);
            ArrayList<ProjectionEvent> events = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    events.add(readEvent(row));
                }
            }
            return List.copyOf(events);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_read_subscribed_after", failure);
        }
    }

    /** Compatibility overload with the subscription before the bounded target. */
    @Nonnull
    public List<ProjectionEvent> readSubscribedAfter(
            @Nonnull ProjectionSequence sequence,
            @Nonnull ProjectionSubscription subscription,
            @Nonnull ProjectionSequence target,
            int limit
    ) {
        return readSubscribedAfter(sequence, target, subscription, limit);
    }

    @Override
    public List<ProjectionEvent> findByOperation(OperationId operationId) {
        require(operationId, "Operation ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_EVENT + """
                         FROM projection_outbox
                         WHERE operation_id = ?
                         ORDER BY event_sequence
                        """)) {
            statement.setString(1, operationId.toString());
            ArrayList<ProjectionEvent> events = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    events.add(readEvent(row));
                }
            }
            return List.copyOf(events);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_find_operation", failure);
        }
    }

    @Override
    public ProjectionSequence head() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(event_sequence), 0) FROM projection_outbox");
             ResultSet row = statement.executeQuery()) {
            row.next();
            return new ProjectionSequence(row.getLong(1));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_head", failure);
        }
    }

    @Override
    public Optional<ProjectionCheckpoint> findCheckpoint(ProjectionConsumerId consumerId) {
        require(consumerId, "Projection consumer ID");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT consumer_id, acknowledged_sequence, updated_at_ms
                FROM projection_checkpoint
                WHERE consumer_id = ?
                """)) {
            statement.setString(1, consumerId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(new ProjectionCheckpoint(
                                new ProjectionConsumerId(row.getString("consumer_id")),
                                new ProjectionSequence(row.getLong("acknowledged_sequence")),
                                row.getLong("updated_at_ms")
                        ))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_find_checkpoint", failure);
        }
    }

    @Override
    public PersistenceMutationResult<ProjectionCheckpoint> acknowledge(
            ProjectionConsumerId consumerId,
            ProjectionSequence sequence,
            long acknowledgedAtMs
    ) {
        require(consumerId, "Projection consumer ID");
        require(sequence, "Projection sequence");
        if (sequence.compareTo(head()) > 0) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        ProjectionCheckpoint current = findCheckpoint(consumerId).orElse(null);
        if (current != null && current.acknowledgedSequence().compareTo(sequence) >= 0) {
            return PersistenceMutationResult.applied(current);
        }
        ProjectionCheckpoint next =
                new ProjectionCheckpoint(consumerId, sequence, acknowledgedAtMs);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO projection_checkpoint(
                    consumer_id, acknowledged_sequence, updated_at_ms
                ) VALUES (?, ?, ?)
                ON CONFLICT(consumer_id) DO UPDATE SET
                    acknowledged_sequence = excluded.acknowledged_sequence,
                    updated_at_ms = excluded.updated_at_ms
                WHERE projection_checkpoint.acknowledged_sequence
                      < excluded.acknowledged_sequence
                """)) {
            statement.setString(1, consumerId.toString());
            statement.setLong(2, sequence.value());
            statement.setLong(3, acknowledgedAtMs);
            if (statement.executeUpdate() != 1) {
                ProjectionCheckpoint raced = findCheckpoint(consumerId).orElse(null);
                return raced != null && raced.acknowledgedSequence().compareTo(sequence) >= 0
                        ? PersistenceMutationResult.applied(raced)
                        : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(next);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_acknowledge", failure);
        }
    }

    private Optional<ProjectionEvent> findLogicalEvent(ProjectionEventDraft event) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_EVENT + """
                         FROM projection_outbox
                         WHERE operation_id = ? AND event_type = ?
                           AND aggregate_id = ? AND aggregate_revision = ?
                        """)) {
            statement.setString(1, event.operationId().toString());
            statement.setString(2, event.eventType().toString());
            statement.setString(3, event.aggregateId());
            statement.setLong(4, event.aggregateRevision());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readEvent(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("projection_find_logical_event", failure);
        }
    }

    private ProjectionEvent readEvent(ResultSet row) throws SQLException {
        return new ProjectionEvent(
                new ProjectionSequence(row.getLong("event_sequence")),
                OperationId.parse(row.getString("operation_id")),
                new ProjectionEventType(row.getString("event_type")),
                row.getString("aggregate_id"),
                row.getLong("aggregate_revision"),
                row.getInt("payload_version"),
                row.getString("payload_json"),
                row.getLong("created_at_ms")
        );
    }

    private boolean matches(ProjectionEvent existing, ProjectionEventDraft requested) {
        return existing.operationId().equals(requested.operationId())
                && existing.eventType().equals(requested.eventType())
                && existing.aggregateId().equals(requested.aggregateId())
                && existing.aggregateRevision() == requested.aggregateRevision()
                && existing.payloadVersion() == requested.payloadVersion()
                && existing.payloadJson().equals(requested.payloadJson())
                && existing.createdAtMs() == requested.createdAtMs();
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
