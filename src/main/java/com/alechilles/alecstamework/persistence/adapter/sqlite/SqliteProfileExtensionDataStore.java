package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataDecoder;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataPort;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDecodeResult;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Connection-bound adapter for versioned profile extension data.
 *
 * <p>The caller owns the connection and transaction. Read failure is returned explicitly; a
 * malformed durable row is never reported as absent.</p>
 */
public final class SqliteProfileExtensionDataStore implements ProfileExtensionDataPort {
    private final Connection connection;

    public SqliteProfileExtensionDataStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Extension store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public PersistenceReadResult<ProfileExtensionData> find(ProfileExtensionKey key) {
        require(key, "Extension key");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, namespace, data_key, payload_version, json_payload,
                       payload_hash, revision, created_at_ms, updated_at_ms, deleted_at_ms
                FROM profile_extension_data
                WHERE profile_id = ? AND namespace = ? AND data_key = ?
                """)) {
            bindKey(statement, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? PersistenceReadResult.found(read(row), row.getLong("revision"))
                        : PersistenceReadResult.absent();
            }
        } catch (SQLException failure) {
            return storageFailure("extension_find", failure);
        } catch (RuntimeException failure) {
            return decodeFailure("extension_row_decode_failed", "extension_find", failure);
        }
    }

    @Override
    public PersistenceReadResult<List<ProfileExtensionData>> findNamespace(
            ProfileId profileId,
            String namespace
    ) {
        require(profileId, "Profile ID");
        String normalizedNamespace = requireText(namespace, "Extension namespace");
        try {
            if (!profileExists(profileId)) {
                return PersistenceReadResult.absent();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT profile_id, namespace, data_key, payload_version, json_payload,
                           payload_hash, revision, created_at_ms, updated_at_ms, deleted_at_ms
                    FROM profile_extension_data
                    WHERE profile_id = ? AND namespace = ? AND deleted_at_ms IS NULL
                    ORDER BY data_key
                    """)) {
                statement.setString(1, profileId.toString());
                statement.setString(2, normalizedNamespace);
                ArrayList<ProfileExtensionData> values = new ArrayList<>();
                long maxRevision = 0;
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        ProfileExtensionData value = read(row);
                        values.add(value);
                        maxRevision = Math.max(maxRevision, value.revision());
                    }
                }
                return PersistenceReadResult.found(List.copyOf(values), maxRevision);
            }
        } catch (SQLException failure) {
            return storageFailure("extension_find_namespace", failure);
        } catch (RuntimeException failure) {
            return decodeFailure(
                    "extension_namespace_row_decode_failed",
                    "extension_find_namespace",
                    failure
            );
        }
    }

    @Override
    public PersistenceMutationResult<ProfileExtensionData> put(
            ProfileExtensionData next,
            long expectedRevision
    ) {
        requireValidMutation(next, expectedRevision);
        ProfileExtensionData existing = foundValue(find(next.key()));
        if (existing != null && existing.equals(next)) {
            return PersistenceMutationResult.applied(existing);
        }
        if (existing == null) {
            if (expectedRevision != 0) {
                return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
            }
            try {
                if (!profileExists(next.key().profileId())) {
                    return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
                }
            } catch (SQLException failure) {
                throw storeFailure("extension_profile_exists", failure);
            }
            return insert(next);
        }
        if (existing.revision() != expectedRevision) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (existing.createdAtMs() != next.createdAtMs()) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE profile_extension_data
                SET payload_version = ?, json_payload = ?, payload_hash = ?,
                    revision = ?, updated_at_ms = ?, deleted_at_ms = NULL
                WHERE profile_id = ? AND namespace = ? AND data_key = ? AND revision = ?
                """)) {
            statement.setInt(1, next.payloadVersion());
            statement.setString(2, next.jsonPayload());
            statement.setString(3, next.payloadHash().toString());
            statement.setLong(4, next.revision());
            statement.setLong(5, next.updatedAtMs());
            statement.setString(6, next.key().profileId().toString());
            statement.setString(7, next.key().namespace());
            statement.setString(8, next.key().dataKey());
            statement.setLong(9, expectedRevision);
            return statement.executeUpdate() == 1
                    ? PersistenceMutationResult.applied(next)
                    : PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.REVISION_MISMATCH
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("extension_put", failure);
        }
    }

    @Override
    public PersistenceMutationResult<ProfileExtensionData> delete(
            ProfileExtensionKey key,
            long expectedRevision,
            long deletedAtMs
    ) {
        require(key, "Extension key");
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("Delete requires a positive expected revision");
        }
        ProfileExtensionData existing = foundValue(find(key));
        if (existing == null) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        if (existing.revision() != expectedRevision) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        if (existing.deleted()) {
            return PersistenceMutationResult.applied(existing);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE profile_extension_data
                SET revision = ?, updated_at_ms = ?, deleted_at_ms = ?
                WHERE profile_id = ? AND namespace = ? AND data_key = ? AND revision = ?
                """)) {
            statement.setLong(1, expectedRevision + 1);
            statement.setLong(2, deletedAtMs);
            statement.setLong(3, deletedAtMs);
            statement.setString(4, key.profileId().toString());
            statement.setString(5, key.namespace());
            statement.setString(6, key.dataKey());
            statement.setLong(7, expectedRevision);
            ProfileExtensionData tombstone = new ProfileExtensionData(
                    existing.key(),
                    existing.payloadVersion(),
                    existing.jsonPayload(),
                    existing.payloadHash(),
                    expectedRevision + 1,
                    existing.createdAtMs(),
                    deletedAtMs,
                    deletedAtMs
            );
            return statement.executeUpdate() == 1
                    ? PersistenceMutationResult.applied(tombstone)
                    : PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.REVISION_MISMATCH
            );
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("extension_delete", failure);
        }
    }

    private PersistenceMutationResult<ProfileExtensionData> insert(ProfileExtensionData next) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_extension_data(
                    profile_id, namespace, data_key, payload_version, json_payload,
                    payload_hash, revision, created_at_ms, updated_at_ms, deleted_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """)) {
            statement.setString(1, next.key().profileId().toString());
            statement.setString(2, next.key().namespace());
            statement.setString(3, next.key().dataKey());
            statement.setInt(4, next.payloadVersion());
            statement.setString(5, next.jsonPayload());
            statement.setString(6, next.payloadHash().toString());
            statement.setLong(7, next.revision());
            statement.setLong(8, next.createdAtMs());
            statement.setLong(9, next.updatedAtMs());
            statement.executeUpdate();
            return PersistenceMutationResult.applied(next);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("extension_insert", failure);
        }
    }

    private ProfileExtensionData foundValue(PersistenceReadResult<ProfileExtensionData> result) {
        if (result instanceof PersistenceReadResult.Found<ProfileExtensionData> found) {
            return found.value();
        }
        if (result instanceof PersistenceReadResult.Failed<ProfileExtensionData> failed) {
            throw new PersistenceStoreException(
                    failed.failure().operation(),
                    failed.failure().cause() == null
                            ? new IllegalStateException(failed.failure().code())
                            : failed.failure().cause()
            );
        }
        return null;
    }

    private boolean profileExists(ProfileId profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM companion_profile WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private ProfileExtensionData read(ResultSet row) throws SQLException {
        return new ProfileExtensionData(
                new ProfileExtensionKey(
                        ProfileId.parse(row.getString("profile_id")),
                        row.getString("namespace"),
                        row.getString("data_key")
                ),
                row.getInt("payload_version"),
                row.getString("json_payload"),
                Sha256Hash.parse(row.getString("payload_hash")),
                row.getLong("revision"),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms"),
                nullableLong(row, "deleted_at_ms")
        );
    }

    private void requireValidMutation(ProfileExtensionData value, long expectedRevision) {
        require(value, "Extension value");
        if (value.deleted()) {
            throw new IllegalArgumentException("Extension put cannot write a tombstone");
        }
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE
                || value.revision() != expectedRevision + 1) {
            throw new IllegalArgumentException(
                    "Extension mutation must advance the expected revision exactly once"
            );
        }
        ProfileExtensionDecodeResult decoded = ProfileExtensionDataDecoder.decode(value);
        if (!(decoded instanceof ProfileExtensionDecodeResult.Decoded)) {
            ProfileExtensionDecodeResult.Failed failed =
                    (ProfileExtensionDecodeResult.Failed) decoded;
            throw new IllegalArgumentException(failed.code(), failed.cause());
        }
    }

    private void bindKey(PreparedStatement statement, ProfileExtensionKey key)
            throws SQLException {
        statement.setString(1, key.profileId().toString());
        statement.setString(2, key.namespace());
        statement.setString(3, key.dataKey());
    }

    private Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private <T> PersistenceReadResult<T> storageFailure(String operation, Throwable failure) {
        return PersistenceReadResult.failed(SqliteFailureClassifier.classify(failure, operation));
    }

    private <T> PersistenceReadResult<T> decodeFailure(
            String code,
            String operation,
            Throwable failure
    ) {
        return PersistenceReadResult.failed(new StorageFailure(
                StorageFailureKind.DECODE,
                code,
                operation,
                false,
                failure
        ));
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        return new PersistenceStoreException(operation, failure);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
