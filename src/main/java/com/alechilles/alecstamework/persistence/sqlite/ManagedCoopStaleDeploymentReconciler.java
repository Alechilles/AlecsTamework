package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.PopulationDetachRequest;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.MutationResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Retires legacy deployed coop rows disproved by canonical companion lifecycle and UUID authority.
 *
 * <p>This reconciliation runs before managed-coop indexes are published. It deliberately ignores
 * ambiguous rows, cooped population rows, and any profile or slot with an active coop operation.</p>
 */
final class ManagedCoopStaleDeploymentReconciler {
    private static final String REPAIRABLE_PREDICATE = """
            r.active = 1 AND r.state = 'DEPLOYED'
              AND r.deployed_npc_uuid IS NOT NULL
              AND r.resident_uuid = r.deployed_npc_uuid
              AND p.current_npc_uuid IS NOT NULL
              AND (
                  s.lifecycle_state = 'CAPTURED'
                  OR (s.lifecycle_state <> 'COOP'
                      AND p.current_npc_uuid <> r.deployed_npc_uuid)
              )
              AND NOT EXISTS (
                  SELECT 1 FROM coop_lifecycle_operations o
                  WHERE o.active = 1 AND (
                      o.profile_id = r.profile_id
                      OR (o.authority_id = r.authority_id
                          AND o.resident_slot = r.resident_slot)
                  )
              )
            """;

    private final SqliteConnectionManager connections;
    private final ManagedCoopResidentRepository residents;

    ManagedCoopStaleDeploymentReconciler(
            @Nonnull SqliteConnectionManager connections,
            @Nonnull ManagedCoopResidentRepository residents) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.residents = Objects.requireNonNull(residents, "residents");
    }

    /** Atomically retires every exact stale deployment and deactivates all of its UUID claims. */
    @Nonnull
    RepairResult reconcile() throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                RepairResult result = reconcileInTransaction(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof SQLException sqlException) {
                    throw sqlException;
                }
                throw new SQLException("managed_coop_stale_deployment_repair_failed", exception);
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Nonnull
    private RepairResult reconcileInTransaction(Connection connection) throws SQLException {
        List<String> candidates = loadCandidateIds(connection);
        int repaired = 0;
        long nowMs = System.currentTimeMillis();
        for (String residentId : candidates) {
            if (!isStillRepairable(connection, residentId)) {
                continue;
            }
            ResidentRecord resident = residents.loadByIdInTransaction(connection, residentId);
            requireRepairableResident(resident);
            MutationResult result = residents.detachDeployedInTransaction(
                    connection, detachRequest(resident, nowMs)
            );
            requireRetired(result);
            repaired++;
        }
        return new RepairResult(candidates.size(), repaired);
    }

    @Nonnull
    private List<String> loadCandidateIds(Connection connection) throws SQLException {
        String sql = """
                SELECT r.resident_id
                FROM managed_coop_residents r
                INNER JOIN npc_profiles p ON p.profile_id = r.profile_id
                INNER JOIN companion_population_state s ON s.profile_id = r.profile_id
                WHERE %s
                ORDER BY r.resident_id
                """.formatted(REPAIRABLE_PREDICATE);
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ArrayList<String> residentIds = new ArrayList<>();
            while (resultSet.next()) {
                residentIds.add(resultSet.getString(1));
            }
            return List.copyOf(residentIds);
        }
    }

    private boolean isStillRepairable(Connection connection, String residentId) throws SQLException {
        String sql = """
                SELECT 1
                FROM managed_coop_residents r
                INNER JOIN npc_profiles p ON p.profile_id = r.profile_id
                INNER JOIN companion_population_state s ON s.profile_id = r.profile_id
                WHERE r.resident_id = ? AND %s
                LIMIT 1
                """.formatted(REPAIRABLE_PREDICATE);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, residentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Nonnull
    private static PopulationDetachRequest detachRequest(ResidentRecord resident, long nowMs) {
        return new PopulationDetachRequest(
                resident.residentId(),
                resident.authorityKey(),
                resident.coopId(),
                resident.residentSlot(),
                resident.profileId(),
                Objects.requireNonNull(resident.deployedNpcUuid(), "deployedNpcUuid"),
                resident.generation(),
                nowMs
        );
    }

    private static void requireRepairableResident(ResidentRecord resident) throws SQLException {
        if (resident == null || !resident.active() || resident.state() != ResidentState.DEPLOYED
                || resident.deployedNpcUuid() == null
                || !resident.deployedNpcUuid().equals(resident.residentUuid())) {
            throw new SQLException("stale_deployment_candidate_changed");
        }
    }

    private static void requireRetired(MutationResult result) throws SQLException {
        ResidentRecord retired = result == null ? null : result.resident();
        if (result == null || !result.succeeded() || retired == null || retired.active()
                || retired.state() != ResidentState.RETIRED) {
            String detail = result == null ? "result_missing" : result.detail();
            throw new SQLException("stale_deployment_retirement_failed:" + detail);
        }
    }

    record RepairResult(int candidateCount, int repairedCount) {
    }
}
