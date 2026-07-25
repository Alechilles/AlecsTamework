package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonLease;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseChange;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeasePort;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonPolicy;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;

/** Normalized SQLite authority for one timed lease detail per profile. */
public final class SqliteTimedSummonLeaseStore
        implements TimedSummonLeasePort {
    private static final String COLUMNS = """
            profile_id, lease_revision, session_id, remaining_ms,
            cooldown_until_ms, config_id, config_revision,
            active_duration_ms, resummon_cooldown_ms,
            auto_store_on_owner_logout, warning_thresholds_json,
            emitted_warning_thresholds_json, checkpointed_at_ms,
            created_at_ms, updated_at_ms
            """;

    private final Connection connection;

    public SqliteTimedSummonLeaseStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "Timed summon lease connection is required"
            );
        }
        this.connection = connection;
    }

    @Override
    public Optional<TimedSummonLease> find(ProfileId profileId) {
        require(profileId, "Timed summon profile");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS
                        + " FROM timed_summon_lease WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        ? Optional.of(read(row))
                        : Optional.empty();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("timed_summon_lease_find", failure);
        }
    }

    @Override
    public List<TimedSummonLease> findAll() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS
                        + " FROM timed_summon_lease ORDER BY profile_id"
        );
             ResultSet rows = statement.executeQuery()) {
            ArrayList<TimedSummonLease> leases = new ArrayList<>();
            while (rows.next()) {
                leases.add(read(rows));
            }
            return List.copyOf(leases);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("timed_summon_lease_find_all", failure);
        }
    }

    @Override
    public PersistenceMutationResult<TimedSummonLeaseChange> replace(
            Long expectedRevision,
            TimedSummonLease target
    ) {
        require(target, "Timed summon target");
        if (expectedRevision != null && expectedRevision <= 0) {
            throw new IllegalArgumentException(
                    "Expected timed lease revision must be positive"
            );
        }
        TimedSummonLease current =
                find(target.profileId()).orElse(null);
        if (current == null
                ? expectedRevision != null
                : expectedRevision == null
                || current.leaseRevision() != expectedRevision) {
            return rejected(PersistenceMutationStatus.REVISION_MISMATCH);
        }
        TimedSummonLeaseChange change =
                new TimedSummonLeaseChange(current, target);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO timed_summon_lease(
                    profile_id, lease_revision, session_id, remaining_ms,
                    cooldown_until_ms, config_id, config_revision,
                    active_duration_ms, resummon_cooldown_ms,
                    auto_store_on_owner_logout, warning_thresholds_json,
                    emitted_warning_thresholds_json, checkpointed_at_ms,
                    created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    lease_revision = excluded.lease_revision,
                    session_id = excluded.session_id,
                    remaining_ms = excluded.remaining_ms,
                    cooldown_until_ms = excluded.cooldown_until_ms,
                    config_id = excluded.config_id,
                    config_revision = excluded.config_revision,
                    active_duration_ms = excluded.active_duration_ms,
                    resummon_cooldown_ms =
                        excluded.resummon_cooldown_ms,
                    auto_store_on_owner_logout =
                        excluded.auto_store_on_owner_logout,
                    warning_thresholds_json =
                        excluded.warning_thresholds_json,
                    emitted_warning_thresholds_json =
                        excluded.emitted_warning_thresholds_json,
                    checkpointed_at_ms = excluded.checkpointed_at_ms,
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            bind(statement, target);
            if (statement.executeUpdate() != 1) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            return PersistenceMutationResult.applied(change);
        } catch (SQLException failure) {
            if (constraint(failure)) {
                return rejected(PersistenceMutationStatus.CONFLICT);
            }
            throw storeFailure("timed_summon_lease_replace", failure);
        } catch (RuntimeException failure) {
            throw storeFailure("timed_summon_lease_replace", failure);
        }
    }

    private TimedSummonLease read(ResultSet row)
            throws SQLException {
        TimedSummonPolicy policy = new TimedSummonPolicy(
                row.getString("config_id"),
                nullableLong(row, "config_revision"),
                row.getLong("active_duration_ms"),
                row.getLong("resummon_cooldown_ms"),
                row.getInt("auto_store_on_owner_logout") != 0,
                longs(row.getString("warning_thresholds_json"))
        );
        String session = row.getString("session_id");
        return new TimedSummonLease(
                ProfileId.parse(row.getString("profile_id")),
                row.getLong("lease_revision"),
                session == null
                        ? null
                        : TimedSummonSessionId.parse(session),
                nullableLong(row, "remaining_ms"),
                nullableLong(row, "cooldown_until_ms"),
                policy,
                Set.copyOf(longs(row.getString(
                        "emitted_warning_thresholds_json"
                ))),
                nullableLong(row, "checkpointed_at_ms"),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms")
        );
    }

    private void bind(
            PreparedStatement statement,
            TimedSummonLease lease
    ) throws SQLException {
        statement.setString(1, lease.profileId().toString());
        statement.setLong(2, lease.leaseRevision());
        setText(
                statement,
                3,
                lease.sessionId() == null
                        ? null
                        : lease.sessionId().toString()
        );
        setLong(statement, 4, lease.remainingMs());
        setLong(statement, 5, lease.cooldownUntilMs());
        setText(statement, 6, lease.policy().configId());
        setLong(statement, 7, lease.policy().configRevision());
        statement.setLong(8, lease.policy().activeDurationMs());
        statement.setLong(9, lease.policy().resummonCooldownMs());
        statement.setInt(
                10,
                lease.policy().autoStoreOnOwnerLogout() ? 1 : 0
        );
        statement.setString(
                11, json(lease.policy().warningThresholdsMs())
        );
        statement.setString(
                12, json(lease.emittedWarningThresholdsMs())
        );
        setLong(statement, 13, lease.checkpointedAtMs());
        statement.setLong(14, lease.createdAtMs());
        statement.setLong(15, lease.updatedAtMs());
    }

    private String json(Iterable<Long> values) {
        JsonArray json = new JsonArray();
        ArrayList<Long> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(java.util.Comparator.reverseOrder());
        sorted.forEach(json::add);
        return json.toString();
    }

    private List<Long> longs(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException(
                    "Timed warning evidence must be an array"
            );
        }
        LinkedHashSet<Long> values = new LinkedHashSet<>();
        for (JsonElement element : parsed.getAsJsonArray()) {
            long value = element.getAsLong();
            if (!values.add(value)) {
                throw new IllegalArgumentException(
                        "Timed warning evidence must be unique"
                );
            }
        }
        return List.copyOf(values);
    }

    private Long nullableLong(ResultSet row, String column)
            throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private void setText(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private boolean constraint(SQLException failure) {
        return failure.getErrorCode() == 19
                || failure.getMessage() != null
                && failure.getMessage().contains(
                "SQLITE_CONSTRAINT"
        );
    }

    private <T> PersistenceMutationResult<T> rejected(
            PersistenceMutationStatus status
    ) {
        return PersistenceMutationResult.rejected(status);
    }

    private PersistenceStoreException storeFailure(
            String operation,
            Throwable failure
    ) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}

