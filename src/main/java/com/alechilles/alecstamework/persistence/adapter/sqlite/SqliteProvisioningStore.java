package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningPort;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningRecord;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Immutable normalized provisioning-provenance SQLite authority. */
final class SqliteProvisioningStore implements ProvisioningPort {
    private static final String COLUMNS = """
            profile_id, caller_namespace, caller_key, correlation_id,
            policy_revision, creation_operation_id, created_at_ms
            """;

    private final Connection connection;

    SqliteProvisioningStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Provisioning connection is required"
            );
        }
        this.connection = connection;
    }

    @Override
    public Optional<ProvisioningRecord> findByProfile(
            ProfileId profileId
    ) {
        require(profileId, "Provisioning profile");
        return find(
                "profile_id = ?",
                statement -> statement.setString(
                        1, profileId.toString()
                )
        );
    }

    @Override
    public Optional<ProvisioningRecord> findByOrigin(
            ProvisioningOrigin origin
    ) {
        require(origin, "Provisioning origin");
        return find(
                "caller_namespace = ? AND caller_key = ?",
                statement -> {
                    statement.setString(1, origin.callerNamespace());
                    statement.setString(2, origin.callerKey());
                }
        );
    }

    @Override
    public List<ProvisioningRecord> findAll() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS
                        + " FROM provisioning_record ORDER BY profile_id"
        )) {
            ArrayList<ProvisioningRecord> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return List.copyOf(result);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("provisioning_find_all", failure);
        }
    }

    @Override
    public PersistenceMutationResult<ProvisioningRecord> create(
            ProvisioningRecord record
    ) {
        require(record, "Provisioning record");
        ProvisioningRecord profile = findByProfile(
                record.profileId()
        ).orElse(null);
        ProvisioningRecord origin = findByOrigin(
                record.origin()
        ).orElse(null);
        if (profile != null || origin != null) {
            return profile != null && profile.equals(record)
                    && origin != null && origin.equals(record)
                    ? PersistenceMutationResult.applied(record)
                    : PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.CONFLICT
            );
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO provisioning_record(
                    profile_id, caller_namespace, caller_key,
                    correlation_id, policy_revision,
                    creation_operation_id, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, record.profileId().toString());
            statement.setString(
                    2, record.origin().callerNamespace()
            );
            statement.setString(3, record.origin().callerKey());
            if (record.correlationId() == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(
                        4, record.correlationId().toString()
                );
            }
            statement.setLong(5, record.policyRevision());
            statement.setString(
                    6, record.creationOperationId().toString()
            );
            statement.setLong(7, record.createdAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(record);
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return PersistenceMutationResult.rejected(
                        PersistenceMutationStatus.CONFLICT
                );
            }
            throw storeFailure("provisioning_create", failure);
        }
    }

    private Optional<ProvisioningRecord> find(
            String predicate,
            Binder binder
    ) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM provisioning_record WHERE "
                        + predicate
        )) {
            binder.bind(statement);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("provisioning_find", failure);
        }
    }

    private ProvisioningRecord read(ResultSet row) throws SQLException {
        String correlation = row.getString("correlation_id");
        return new ProvisioningRecord(
                ProfileId.parse(row.getString("profile_id")),
                new ProvisioningOrigin(
                        row.getString("caller_namespace"),
                        row.getString("caller_key")
                ),
                correlation == null
                        ? null
                        : java.util.UUID.fromString(correlation),
                row.getLong("policy_revision"),
                OperationId.parse(
                        row.getString("creation_operation_id")
                ),
                row.getLong("created_at_ms")
        );
    }

    private boolean constraint(SQLException failure) {
        return failure.getErrorCode() == 19
                || failure.getMessage() != null
                && failure.getMessage().toLowerCase()
                .contains("constraint");
    }

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        if (failure instanceof PersistenceStoreException store) {
            return store;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
