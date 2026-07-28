package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshotPort;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Connection-bound SQLite adapter for immutable companion snapshot evidence.
 *
 * <p>Replacing a current snapshot preserves history and requires an exact canonical lifecycle
 * revision. It never changes lifecycle state or publishes runtime caches.</p>
 */
public final class SqliteCompanionSnapshotStore implements CompanionSnapshotPort {
    private static final String SELECT_COLUMNS = """
            snapshot_id, profile_id, snapshot_kind, payload_version, payload_json,
            payload_hash, source_lifecycle_revision, is_current, created_at_ms
            """;

    private final Connection connection;

    public SqliteCompanionSnapshotStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Snapshot store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public Optional<CompanionSnapshot> findById(SnapshotId snapshotId) {
        require(snapshotId, "Snapshot ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + " FROM companion_snapshot WHERE snapshot_id = ?")) {
            statement.setString(1, snapshotId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readSnapshot(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_find_id", failure);
        }
    }

    @Override
    public Optional<CompanionSnapshot> findCurrent(ProfileId profileId, SnapshotKind kind) {
        require(profileId, "Profile ID");
        require(kind, "Snapshot kind");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + """
                         FROM companion_snapshot
                         WHERE profile_id = ? AND snapshot_kind = ? AND is_current = 1
                        """)) {
            statement.setString(1, profileId.toString());
            statement.setString(2, kind.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readSnapshot(row)) : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_find_current", failure);
        }
    }

    @Override
    public List<CompanionSnapshot> findCurrentByProfile(ProfileId profileId) {
        require(profileId, "Profile ID");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + """
                         FROM companion_snapshot
                         WHERE profile_id = ? AND is_current = 1
                         ORDER BY snapshot_kind, created_at_ms, snapshot_id
                        """)) {
            statement.setString(1, profileId.toString());
            ArrayList<CompanionSnapshot> snapshots = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    snapshots.add(readSnapshot(row));
                }
            }
            return List.copyOf(snapshots);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_find_current_profile", failure);
        }
    }

    @Override
    public List<CompanionSnapshot> findHistory(ProfileId profileId, SnapshotKind kind) {
        require(profileId, "Profile ID");
        require(kind, "Snapshot kind");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + SELECT_COLUMNS + """
                         FROM companion_snapshot
                         WHERE profile_id = ? AND snapshot_kind = ?
                         ORDER BY created_at_ms, snapshot_id
                        """)) {
            statement.setString(1, profileId.toString());
            statement.setString(2, kind.toString());
            ArrayList<CompanionSnapshot> snapshots = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    snapshots.add(readSnapshot(row));
                }
            }
            return List.copyOf(snapshots);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_find_history", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionSnapshot> replaceCurrent(
            CompanionSnapshot snapshot
    ) {
        require(snapshot, "Snapshot");
        if (!snapshot.current()) {
            throw new IllegalArgumentException("Current snapshot replacement must be marked current");
        }
        Optional<CompanionSnapshot> existing = findById(snapshot.snapshotId());
        if (existing.isPresent()) {
            return existing.get().equals(snapshot)
                    ? PersistenceMutationResult.applied(existing.get())
                    : PersistenceMutationResult.rejected(PersistenceMutationStatus.CONFLICT);
        }
        PersistenceMutationStatus correlation = lifecycleCorrelation(snapshot);
        if (correlation != PersistenceMutationStatus.APPLIED) {
            return PersistenceMutationResult.rejected(correlation);
        }
        try {
            retireCurrent(snapshot.profileId(), snapshot.kind());
            insert(snapshot);
            return PersistenceMutationResult.applied(snapshot);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_replace_current", failure);
        }
    }

    @Override
    public PersistenceMutationResult<CompanionSnapshot> retireCurrent(
            SnapshotId snapshotId
    ) {
        require(snapshotId, "Snapshot ID");
        CompanionSnapshot existing = findById(snapshotId).orElse(null);
        if (existing == null) {
            return PersistenceMutationResult.rejected(
                    PersistenceMutationStatus.NOT_FOUND
            );
        }
        if (!existing.current()) {
            return PersistenceMutationResult.applied(existing);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_snapshot
                SET is_current = 0
                WHERE snapshot_id = ? AND is_current = 1
                """)) {
            statement.setString(1, snapshotId.toString());
            if (statement.executeUpdate() != 1) {
                return PersistenceMutationResult.rejected(
                        PersistenceMutationStatus.CONFLICT
                );
            }
            return PersistenceMutationResult.applied(new CompanionSnapshot(
                    existing.snapshotId(),
                    existing.profileId(),
                    existing.kind(),
                    existing.payloadVersion(),
                    existing.payloadJson(),
                    existing.payloadHash(),
                    existing.sourceLifecycleRevision(),
                    false,
                    existing.createdAtMs()
            ));
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_retire_current", failure);
        }
    }

    private PersistenceMutationStatus lifecycleCorrelation(CompanionSnapshot snapshot) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revision
                FROM companion_lifecycle
                WHERE profile_id = ?
                """)) {
            statement.setString(1, snapshot.profileId().toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return PersistenceMutationStatus.NOT_FOUND;
                }
                return row.getLong("revision") == snapshot.sourceLifecycleRevision().value()
                        ? PersistenceMutationStatus.APPLIED
                        : PersistenceMutationStatus.REVISION_MISMATCH;
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("snapshot_correlate_lifecycle", failure);
        }
    }

    private void retireCurrent(ProfileId profileId, SnapshotKind kind) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_snapshot
                SET is_current = 0
                WHERE profile_id = ? AND snapshot_kind = ? AND is_current = 1
                """)) {
            statement.setString(1, profileId.toString());
            statement.setString(2, kind.toString());
            statement.executeUpdate();
        }
    }

    private void insert(CompanionSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_snapshot(
                    snapshot_id, profile_id, snapshot_kind, payload_version,
                    payload_json, payload_hash, source_lifecycle_revision,
                    is_current, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                """)) {
            statement.setString(1, snapshot.snapshotId().toString());
            statement.setString(2, snapshot.profileId().toString());
            statement.setString(3, snapshot.kind().toString());
            statement.setInt(4, snapshot.payloadVersion());
            statement.setString(5, snapshot.payloadJson());
            statement.setString(6, snapshot.payloadHash().toString());
            statement.setLong(7, snapshot.sourceLifecycleRevision().value());
            statement.setLong(8, snapshot.createdAtMs());
            statement.executeUpdate();
        }
    }

    private CompanionSnapshot readSnapshot(ResultSet row) throws SQLException {
        return new CompanionSnapshot(
                SnapshotId.parse(row.getString("snapshot_id")),
                ProfileId.parse(row.getString("profile_id")),
                new SnapshotKind(row.getString("snapshot_kind")),
                row.getInt("payload_version"),
                row.getString("payload_json"),
                Sha256Hash.parse(row.getString("payload_hash")),
                new LifecycleRevision(row.getLong("source_lifecycle_revision")),
                row.getInt("is_current") == 1,
                row.getLong("created_at_ms")
        );
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
