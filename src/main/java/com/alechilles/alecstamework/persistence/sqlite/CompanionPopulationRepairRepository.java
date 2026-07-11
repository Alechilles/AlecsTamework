package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.parseUuid;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setInteger;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setText;
import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setUuid;

/**
 * Conservatively adopts complete reconciliation evidence into canonical profile/population rows.
 */
public final class CompanionPopulationRepairRepository {
    private final PersistenceWriteQueue writeQueue;

    public CompanionPopulationRepairRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<RepairResult> mergeAsync(
            @Nonnull CompanionPopulationEvidenceSet evidenceSet
    ) {
        Objects.requireNonNull(evidenceSet, "evidenceSet");
        return writeQueue.submitTracked(
                "companion_population_reconciliation_merge",
                connection -> mergeInTransaction(connection, evidenceSet),
                null
        );
    }

    @Nonnull
    private RepairResult mergeInTransaction(@Nonnull Connection connection,
                                            @Nonnull CompanionPopulationEvidenceSet evidenceSet)
            throws Exception {
        if (!evidenceSet.isConflictFree()) {
            return new RepairResult(false, 0, 0, 0, 0, "reconciliation-evidence-conflict");
        }
        CompanionPopulationRepairEvidenceSelector.Selection selection =
                CompanionPopulationRepairEvidenceSelector.select(connection, evidenceSet.evidence());
        if (selection.conflictReason() != null) {
            return new RepairResult(false, 0, 0, 0, 0, selection.conflictReason());
        }
        List<RepairPlan> plans = new ArrayList<>();
        for (CompanionPopulationEvidenceSet.ResolvedEvidence evidence : selection.evidence()) {
            RepairPlan plan = buildPlan(connection, evidence);
            if (plan.conflictReason() != null) {
                return new RepairResult(false, 0, 0, 0, 0, plan.conflictReason());
            }
            plans.add(plan);
        }

        int inserted = 0;
        int updated = 0;
        int duplicateObservations = 0;
        for (RepairPlan plan : plans) {
            duplicateObservations += Math.max(0, plan.evidence().observationCount() - 1);
            if (plan.existing() == null) {
                insertProfile(connection, plan);
                insertState(connection, plan, 0L);
                inserted++;
            } else {
                boolean changed = updateExisting(connection, plan);
                if (changed) {
                    updated++;
                }
            }
            upsertAlias(connection, plan);
        }
        int unknownWorldOwners = countUnknownWorldOwners(connection);
        return new RepairResult(
                true,
                plans.size(),
                inserted,
                updated,
                duplicateObservations,
                unknownWorldOwners == 0 ? null : "owned-profiles-have-unknown-world"
        );
    }

    @Nonnull
    private RepairPlan buildPlan(@Nonnull Connection connection,
                                 @Nonnull CompanionPopulationEvidenceSet.ResolvedEvidence evidence)
            throws Exception {
        String profileId = CompanionPopulationRepairIdentitySql.resolveProfileId(
                connection,
                evidence.npcUuid()
        );
        Existing existing = profileId == null ? null : loadExisting(connection, profileId);
        if (profileId == null) {
            profileId = CompanionPopulationRepairIdentitySql.deterministicProfileId(evidence.npcUuid());
            Existing collision = loadExisting(connection, profileId);
            if (collision != null) {
                return RepairPlan.conflict(evidence, "deterministic-profile-id-conflict");
            }
        }
        if (existing != null && evidence.physical()
                && existing.currentNpcUuid() != null
                && !existing.currentNpcUuid().equals(evidence.npcUuid())) {
            return RepairPlan.conflict(evidence, "historical-uuid-has-physical-representation");
        }
        // A detached ownerless representation is not evidence that an explicit release was undone.
        boolean preserveReleased = existing != null
                && existing.state() != null
                && CompanionLifecycleState.RELEASED.name().equals(existing.state().lifecycleState())
                && (!evidence.physical()
                || (evidence.ownerObserved() && evidence.observedOwnerUuid() == null));
        if (existing != null
                && !preserveReleased
                && existing.ownerUuid() != null
                && evidence.observedOwnerUuid() != null
                && !existing.ownerUuid().equals(evidence.observedOwnerUuid())) {
            return RepairPlan.conflict(evidence, "durable-owner-conflicts-with-evidence");
        }

        UUID desiredOwner = preserveReleased
                ? existing.ownerUuid()
                : existing != null && existing.ownerUuid() != null
                ? existing.ownerUuid()
                : evidence.observedOwnerUuid();
        UUID desiredCurrent = existing != null && existing.currentNpcUuid() != null
                ? existing.currentNpcUuid()
                : evidence.npcUuid();
        State desiredState = preserveReleased ? existing.state() : desiredState(existing, evidence);
        String desiredWorld = firstNonBlank(
                desiredState.ownershipWorldName(),
                existing != null ? existing.profileLastWorldName() : null,
                evidence.ownershipWorldName()
        );
        desiredState = desiredState.withOwnershipWorld(desiredWorld);
        return new RepairPlan(profileId, evidence, existing, desiredCurrent, desiredOwner, desiredState, null);
    }

    @Nonnull
    private State desiredState(@Nullable Existing existing,
                               @Nonnull CompanionPopulationEvidenceSet.ResolvedEvidence evidence) {
        if (evidence.deathObserved()) {
            CompanionPopulationEvidenceSet.PhysicalLocation location = evidence.physicalLocation();
            return new State(
                    location.worldName(),
                    CompanionLifecycleState.DEAD_REVIVABLE.name(),
                    location.worldName(),
                    location.chunkX(),
                    location.chunkZ(),
                    "reconciliation-world-dead-entity"
            );
        }
        if (evidence.livePhysical()) {
            CompanionPopulationEvidenceSet.PhysicalLocation location = evidence.physicalLocation();
            return new State(
                    location.worldName(),
                    CompanionLifecycleState.UNLOADED.name(),
                    location.worldName(),
                    location.chunkX(),
                    location.chunkZ(),
                    "reconciliation-world-entity"
            );
        }
        CompanionLifecycleState explicitLifecycle = switch (evidence.lifecycleKind()) {
            case CAPTURED_SNAPSHOT -> CompanionLifecycleState.CAPTURED;
            case DEATH_SNAPSHOT -> CompanionLifecycleState.DEAD_REVIVABLE;
            case LOST_SNAPSHOT -> CompanionLifecycleState.LOST;
            case COOP_SNAPSHOT -> CompanionLifecycleState.COOP;
            default -> null;
        };
        if (explicitLifecycle != null) {
            return new State(
                    evidence.ownershipWorldName(),
                    explicitLifecycle.name(),
                    null,
                    null,
                    null,
                    "reconciliation-profile-snapshot"
            );
        }
        if (existing != null && existing.state() != null) {
            State durable = existing.state();
            if (CompanionLifecycleState.RELEASED.name().equals(durable.lifecycleState())) {
                return durable;
            }
            if (durable.physicalWorldName() != null
                    || (!CompanionLifecycleState.UNKNOWN_DORMANT.name().equals(durable.lifecycleState())
                    && !CompanionLifecycleState.ACTIVE.name().equals(durable.lifecycleState())
                    && !CompanionLifecycleState.UNLOADED.name().equals(durable.lifecycleState()))) {
                return durable;
            }
        }
        if (evidence.lifecycleKind() == com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence.Kind.PROFILE_RECORD) {
            return new State(
                    evidence.ownershipWorldName(),
                    CompanionLifecycleState.UNKNOWN_DORMANT.name(),
                    null,
                    null,
                    null,
                    "reconciliation-profile-record"
            );
        }
        return new State(
                evidence.ownershipWorldName(),
                CompanionLifecycleState.CAPTURED.name(),
                null,
                null,
                null,
                "reconciliation-captured-item"
        );
    }

    private boolean updateExisting(@Nonnull Connection connection, @Nonnull RepairPlan plan)
            throws Exception {
        Existing existing = plan.existing();
        boolean profileChanged = !Objects.equals(existing.currentNpcUuid(), plan.desiredCurrentNpcUuid())
                || !Objects.equals(existing.ownerUuid(), plan.desiredOwnerUuid())
                || (!Objects.equals(existing.profileLastWorldName(), plan.desiredState().ownershipWorldName())
                && plan.desiredState().ownershipWorldName() != null);
        if (profileChanged) {
            updateProfile(connection, plan);
        }
        if (existing.state() == null) {
            insertState(connection, plan, 0L);
            return true;
        }
        boolean stateChanged = !existing.state().equivalent(plan.desiredState()) || profileChanged;
        if (stateChanged) {
            updateState(connection, plan, existing.revision());
        }
        return profileChanged || stateChanged;
    }

    private void insertProfile(@Nonnull Connection connection, @Nonnull RepairPlan plan) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, plan.profileId());
            setUuid(statement, 2, plan.desiredCurrentNpcUuid());
            setUuid(statement, 3, plan.desiredOwnerUuid());
            setText(statement, 4, plan.desiredState().ownershipWorldName());
            statement.setLong(5, now);
            statement.setLong(6, now);
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    private void updateProfile(@Nonnull Connection connection, @Nonnull RepairPlan plan) throws Exception {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE npc_profiles
                SET current_npc_uuid = ?, owner_uuid = ?,
                    last_world_name = COALESCE(?, last_world_name),
                    updated_at_ms = ?, last_active_at_ms = ?
                WHERE profile_id = ?
                """
        )) {
            setUuid(statement, 1, plan.desiredCurrentNpcUuid());
            setUuid(statement, 2, plan.desiredOwnerUuid());
            setText(statement, 3, plan.desiredState().ownershipWorldName());
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.setString(6, plan.profileId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population repair profile disappeared.");
            }
        }
    }

    private void insertState(@Nonnull Connection connection, @Nonnull RepairPlan plan, long revision)
            throws Exception {
        long now = System.currentTimeMillis();
        State state = plan.desiredState();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state,
                    physical_world_name, physical_chunk_x, physical_chunk_z,
                    revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, plan.profileId());
            setText(statement, 2, state.ownershipWorldName());
            statement.setString(3, state.lifecycleState());
            setText(statement, 4, state.physicalWorldName());
            setInteger(statement, 5, state.physicalChunkX());
            setInteger(statement, 6, state.physicalChunkZ());
            statement.setLong(7, revision);
            statement.setString(8, state.source());
            statement.setLong(9, now);
            statement.setLong(10, now);
            statement.executeUpdate();
        }
    }

    private void updateState(@Nonnull Connection connection,
                             @Nonnull RepairPlan plan,
                             long expectedRevision) throws Exception {
        State state = plan.desiredState();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                UPDATE companion_population_state
                SET ownership_world_name = ?, lifecycle_state = ?, physical_world_name = ?,
                    physical_chunk_x = ?, physical_chunk_z = ?, revision = revision + 1,
                    source = ?, updated_at_ms = ?
                WHERE profile_id = ? AND revision = ?
                """
        )) {
            setText(statement, 1, state.ownershipWorldName());
            statement.setString(2, state.lifecycleState());
            setText(statement, 3, state.physicalWorldName());
            setInteger(statement, 4, state.physicalChunkX());
            setInteger(statement, 5, state.physicalChunkZ());
            statement.setString(6, state.source());
            statement.setLong(7, System.currentTimeMillis());
            statement.setString(8, plan.profileId());
            statement.setLong(9, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Population repair revision changed.");
            }
        }
    }

    private void upsertAlias(@Nonnull Connection connection, @Nonnull RepairPlan plan) throws Exception {
        String conflicting = CompanionPopulationRepairIdentitySql.resolveProfileId(
                connection,
                plan.evidence().npcUuid()
        );
        if (conflicting != null && !conflicting.equals(plan.profileId())) {
            throw new IllegalStateException("Population repair alias changed profiles.");
        }
        boolean current = plan.evidence().npcUuid().equals(plan.desiredCurrentNpcUuid());
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    is_current = excluded.is_current,
                    mapped_at_ms = excluded.mapped_at_ms
                """
        )) {
            setUuid(statement, 1, plan.evidence().npcUuid());
            statement.setString(2, plan.profileId());
            statement.setInt(3, current ? 1 : 0);
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    @Nullable
    private Existing loadExisting(@Nonnull Connection connection, @Nonnull String profileId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT p.current_npc_uuid, p.owner_uuid, p.last_world_name,
                       s.ownership_world_name, s.lifecycle_state, s.physical_world_name,
                       s.physical_chunk_x, s.physical_chunk_z, s.revision, s.source
                FROM npc_profiles p
                LEFT JOIN companion_population_state s ON s.profile_id = p.profile_id
                WHERE p.profile_id = ?
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String lifecycle = resultSet.getString("lifecycle_state");
                State state = lifecycle == null ? null : new State(
                        resultSet.getString("ownership_world_name"),
                        lifecycle,
                        resultSet.getString("physical_world_name"),
                        nullableInteger(resultSet, "physical_chunk_x"),
                        nullableInteger(resultSet, "physical_chunk_z"),
                        resultSet.getString("source")
                );
                return new Existing(
                        parseUuid(resultSet.getString("current_npc_uuid")),
                        parseUuid(resultSet.getString("owner_uuid")),
                        resultSet.getString("last_world_name"),
                        state,
                        lifecycle == null ? 0L : resultSet.getLong("revision")
                );
            }
        }
    }

    private int countUnknownWorldOwners(@Nonnull Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM companion_population_state s
                INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                WHERE p.owner_uuid IS NOT NULL
                  AND (s.ownership_world_name IS NULL OR TRIM(s.ownership_world_name) = '')
                """
        ); ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    @Nullable
    private static Integer nullableInteger(@Nonnull ResultSet resultSet, @Nonnull String field)
            throws Exception {
        int value = resultSet.getInt(field);
        return resultSet.wasNull() ? null : value;
    }

    @Nullable
    private static String firstNonBlank(@Nullable String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record RepairResult(boolean merged,
                               int profileCount,
                               int insertedProfiles,
                               int updatedProfiles,
                               int duplicateObservations,
                               @Nullable String reason) {
    }

    private record Existing(@Nullable UUID currentNpcUuid,
                            @Nullable UUID ownerUuid,
                            @Nullable String profileLastWorldName,
                            @Nullable State state,
                            long revision) {
    }

    private record State(@Nullable String ownershipWorldName,
                         @Nonnull String lifecycleState,
                         @Nullable String physicalWorldName,
                         @Nullable Integer physicalChunkX,
                         @Nullable Integer physicalChunkZ,
                         @Nullable String source) {
        @Nonnull
        private State withOwnershipWorld(@Nullable String worldName) {
            return new State(
                    worldName,
                    lifecycleState,
                    physicalWorldName,
                    physicalChunkX,
                    physicalChunkZ,
                    source
            );
        }

        private boolean equivalent(@Nonnull State other) {
            return Objects.equals(ownershipWorldName, other.ownershipWorldName)
                    && lifecycleState.equals(other.lifecycleState)
                    && Objects.equals(physicalWorldName, other.physicalWorldName)
                    && Objects.equals(physicalChunkX, other.physicalChunkX)
                    && Objects.equals(physicalChunkZ, other.physicalChunkZ);
        }
    }

    private record RepairPlan(@Nullable String profileId,
                              @Nonnull CompanionPopulationEvidenceSet.ResolvedEvidence evidence,
                              @Nullable Existing existing,
                              @Nullable UUID desiredCurrentNpcUuid,
                              @Nullable UUID desiredOwnerUuid,
                              @Nullable State desiredState,
                              @Nullable String conflictReason) {
        @Nonnull
        private static RepairPlan conflict(
                @Nonnull CompanionPopulationEvidenceSet.ResolvedEvidence evidence,
                @Nonnull String reason
        ) {
            return new RepairPlan(null, evidence, null, null, null, null, reason);
        }
    }

}
