package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class DeathRepository {
    private static final String SNAPSHOT_TYPE = "death";
    private static final String LINK_TYPE = "death";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;

    public DeathRepository(@Nonnull SqliteConnectionManager connectionManager,
                           @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public DeathRepository(@Nonnull SqliteConnectionManager connectionManager,
                           @Nonnull PersistenceWriteQueue writeQueue,
                           @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.profileRepository = profileRepository;
    }

    @Nonnull
    public List<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT s.profile_id, s.payload_json, p.current_npc_uuid, p.owner_uuid, p.role_id, p.display_name
                     FROM npc_snapshots s
                     INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                     WHERE s.snapshot_type = ? AND s.is_active = 1
                     """
             )) {
            statement.setString(1, SNAPSHOT_TYPE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("current_npc_uuid"));
                    String profileId = rs.getString("profile_id");
                    if (npcUuid == null || profileId == null || profileId.isBlank()) {
                        continue;
                    }

                    JsonObject payload = parseJsonObject(rs.getString("payload_json"));
                    if (payload == null) {
                        continue;
                    }
                    String[] toolIds = profileRepository.loadToolLinks(connection, profileId, LINK_TYPE);
                    UUID ownerId = coalesceUuid(
                            SqliteValueCodec.parseUuid(rs.getString("owner_uuid")),
                            parseUuid(payload, "ownerId")
                    );
                    String roleId = coalesceNonBlank(rs.getString("role_id"), getString(payload, "roleId"));
                    String displayName = coalesceNonBlank(rs.getString("display_name"), getString(payload, "displayName"));

                    rows.add(new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                            npcUuid,
                            ownerId,
                            getString(payload, "ownerName"),
                            toolIds,
                            roleId,
                            getBoolean(payload, "tamed", false),
                            getString(payload, "customName"),
                            displayName,
                            readVector(payload, "lastKnownPosition"),
                            readVector(payload, "homePosition"),
                            getLong(payload, "diedAtMs", System.currentTimeMillis()),
                            getLong(payload, "respawnAvailableAtMs", System.currentTimeMillis()),
                            getString(payload, "breedingConfigId"),
                            getDoubleObj(payload, "breedingHappiness"),
                            getLong(payload, "breedingCooldownUntilMs", 0L),
                            parseUuid(payload, "breedingLastPartnerUuid"),
                            getString(payload, "traitsConfigId"),
                            getLong(payload, "traitsRollSeed", 0L),
                            getString(payload, "traitsValues"),
                            getString(payload, "happinessConfigId"),
                            getDoubleObj(payload, "happinessValue"),
                            getLong(payload, "happinessLastUpdateMs", 0L),
                            getString(payload, "lifeStage"),
                            getLong(payload, "lifeStageBornAtMs", 0L),
                            getLong(payload, "lifeStageAdolescentAtMs", 0L),
                            getLong(payload, "lifeStageAdultAtMs", 0L),
                            getLong(payload, "lifeStageFullyGrownAtMs", 0L),
                            getDouble(payload, "lifeStageBabyScale", 0.55),
                            getDouble(payload, "lifeStageAdolescentScale", 0.80),
                            getDouble(payload, "lifeStageAdolescentSwitchScale", 0.80),
                            getDouble(payload, "lifeStageAdultStartScale", 0.80),
                            getDouble(payload, "lifeStageAdultSwitchScale", 1.00),
                            getDouble(payload, "lifeStageAdultScale", 1.00),
                            getBoolean(payload, "lifeStageGrowthScalingEnabled", false),
                            getString(payload, "attachmentsConfigId"),
                            getString(payload, "attachmentsValues"),
                            getBoolean(payload, "breedingEnabled", false),
                            getString(payload, "levelingConfigId"),
                            (int) getLong(payload, "levelingLevel", 1L),
                            getDouble(payload, "levelingTotalXp", 0.0),
                            getString(payload, "talentsConfigId"),
                            (int) getLong(payload, "talentsSpentPoints", 0L),
                            getString(payload, "purchasedTalentIds"),
                            parseDeathCauseKind(payload, "deathCauseKind"),
                            getString(payload, "deathSourceName"),
                            getString(payload, "lifeStageGender")
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean upsertAsync(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "death_upsert",
                connection -> {
                    beforeRef.set(profileRepository.loadProfileByNpcUuidInTransaction(connection, snapshot.npcUuid()));
                    upsertInTransaction(connection, snapshot);
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, snapshot.npcUuid());
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> {
                    profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get());
                    profileRepository.notifyDeathRecorded(snapshot, afterRef.get());
                }
        );
    }

    public boolean deleteAsync(@Nonnull UUID npcUuid) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "death_delete",
                connection -> {
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
                    beforeRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                    deleteInTransaction(connection, npcUuid);
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get())
        );
    }

    /**
     * Repairs the narrow crash-era state where a command-roster companion was permanently
     * released before its revivable death snapshot could be written. The committed owner-clear
     * operation is required as durable death evidence; an ownerless profile by itself is never
     * enough to infer ownership or death.
     *
     * <p>This repair is idempotent and intentionally runs before command-roster caches become
     * available. It restores the roster owner, publishes a minimal death snapshot, and moves the
     * population, roster, and timed lease to {@code DEAD_REVIVABLE} in one transaction.</p>
     */
    @Nonnull
    public OrphanedRosterDeathRecovery recoverOrphanedCommandRosterDeaths() throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                RecoveryCandidates candidates = loadOrphanedRosterDeathCandidates(connection);
                int recovered = 0;
                for (OrphanedRosterDeath candidate : candidates.recoverable().values()) {
                    recoverOrphanedRosterDeath(connection, candidate);
                    recovered++;
                }
                connection.commit();
                return new OrphanedRosterDeathRecovery(
                        recovered,
                        candidates.conflictedProfileIds().size()
                );
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    @Nonnull
    private RecoveryCandidates loadOrphanedRosterDeathCandidates(Connection connection)
            throws Exception {
        LinkedHashMap<String, OrphanedRosterDeath> recoverable = new LinkedHashMap<>();
        Set<String> conflicted = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.owner_uuid, m.command_family_id, m.profile_id, m.role_id,
                       m.home_x, m.home_y, m.home_z,
                       p.current_npc_uuid,
                       o.old_state_json, o.new_state_json, o.target_context_json,
                       o.updated_at_ms
                FROM command_family_roster_memberships m
                INNER JOIN npc_profiles p ON p.profile_id = m.profile_id
                INNER JOIN companion_population_state s ON s.profile_id = m.profile_id
                INNER JOIN companion_population_operations o ON o.profile_id = m.profile_id
                WHERE p.owner_uuid IS NULL
                  AND p.current_npc_uuid IS NOT NULL
                  AND p.role_id = m.role_id
                  AND s.lifecycle_state = 'RELEASED'
                  AND m.command_state IN ('RESTORING','ACTIVE','UNLOADED','STORING')
                  AND o.operation_type = 'OWNER_CLEAR'
                  AND o.state = 'COMMITTED'
                ORDER BY m.profile_id, o.updated_at_ms DESC
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    OrphanedRosterDeath candidate = parseOrphanedRosterDeath(result);
                    if (candidate == null || conflicted.contains(candidate.profileId())) continue;
                    OrphanedRosterDeath current = recoverable.get(candidate.profileId());
                    if (current == null) {
                        recoverable.put(candidate.profileId(), candidate);
                    } else if (!current.sameRosterIdentity(candidate)) {
                        recoverable.remove(candidate.profileId());
                        conflicted.add(candidate.profileId());
                    }
                }
            }
        }
        return new RecoveryCandidates(Map.copyOf(recoverable), Set.copyOf(conflicted));
    }

    @Nullable
    private OrphanedRosterDeath parseOrphanedRosterDeath(ResultSet result) throws Exception {
        String profileId = result.getString("profile_id");
        String ownerText = result.getString("owner_uuid");
        String familyId = result.getString("command_family_id");
        String roleId = result.getString("role_id");
        UUID ownerUuid = SqliteValueCodec.parseUuid(ownerText);
        UUID npcUuid = SqliteValueCodec.parseUuid(result.getString("current_npc_uuid"));
        JsonObject oldState = parseJsonObject(result.getString("old_state_json"));
        JsonObject newState = parseJsonObject(result.getString("new_state_json"));
        JsonObject context = parseJsonObject(result.getString("target_context_json"));
        if (profileId == null || profileId.isBlank() || ownerUuid == null || npcUuid == null
                || familyId == null || familyId.isBlank() || roleId == null || roleId.isBlank()
                || oldState == null || newState == null || context == null
                || !getBoolean(context, "permanentDeath", false)
                || !ownerText.equals(getString(oldState, "ownerUuid"))
                || getString(newState, "ownerUuid") != null
                || !"RELEASED".equals(getString(newState, "lifecycleState"))) {
            return null;
        }
        String contextNpc = getString(context, "npcUuid");
        if (!npcUuid.toString().equals(contextNpc)) return null;
        Double homeX = nullableDouble(result, "home_x");
        Double homeY = nullableDouble(result, "home_y");
        Double homeZ = nullableDouble(result, "home_z");
        Vector3d home = homeX == null || homeY == null || homeZ == null
                ? null : new Vector3d(homeX, homeY, homeZ);
        return new OrphanedRosterDeath(
                ownerUuid, familyId, profileId, roleId, npcUuid, home,
                Math.max(1L, result.getLong("updated_at_ms"))
        );
    }

    private void recoverOrphanedRosterDeath(Connection connection,
                                             OrphanedRosterDeath candidate) throws Exception {
        NpcProfileRepository.ProfileRecord profile =
                profileRepository.loadProfileByIdInTransaction(connection, candidate.profileId());
        if (profile == null || profile.ownerUuid() != null
                || !candidate.npcUuid().equals(profile.currentNpcUuid())) {
            throw new IllegalStateException("orphaned-command-roster-death-profile-changed");
        }
        profileRepository.upsertProfileInTransaction(
                connection,
                new NpcProfileRepository.ProfileUpdate(
                        candidate.npcUuid(), candidate.ownerUuid(), profile.ownerName(),
                        candidate.roleId(), profile.displayName(), profile.customName(), true,
                        profile.coopId(), profile.coopSlot(), null, profile.toolIds()),
                ProfileOwnerMutation.set(candidate.ownerUuid())
        );
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_population_state
                SET lifecycle_state = 'DEAD_REVIVABLE',
                    physical_world_name = NULL, physical_chunk_x = NULL, physical_chunk_z = NULL,
                    revision = revision + 1, source = 'command-roster-death-recovery',
                    updated_at_ms = ?
                WHERE profile_id = ? AND lifecycle_state = 'RELEASED'
                """)) {
            statement.setLong(1, Math.max(System.currentTimeMillis(), candidate.diedAtMs()));
            statement.setString(2, candidate.profileId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("orphaned-command-roster-death-population-changed");
            }
        }
        upsertInTransaction(connection, minimalRecoveredDeath(candidate, profile));
    }

    @Nonnull
    private static CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot minimalRecoveredDeath(
            OrphanedRosterDeath candidate,
            NpcProfileRepository.ProfileRecord profile) {
        return new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                candidate.npcUuid(), candidate.ownerUuid(), profile.ownerName(),
                new String[] {"roster:" + candidate.ownerUuid() + ":" + candidate.familyId()},
                candidate.roleId(), true, profile.customName(), profile.displayName(),
                candidate.homePosition(), candidate.homePosition(),
                candidate.diedAtMs(), candidate.diedAtMs(),
                null, null, 0L, null, null, 0L, null, null, null, 0L,
                null, 0L, 0L, 0L, 0L, 0.55, 0.80, 0.80, 0.80, 1.0, 1.0,
                false, null, null, false, null, 1, 0.0, null, 0, null,
                CommandLinkedNpcDeathService.DeathCauseKind.UNKNOWN,
                "recovered-command-roster-death", null
        );
    }

    @Nullable
    private static Double nullableDouble(ResultSet result, String column) throws Exception {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    void upsertInTransaction(@Nonnull Connection connection,
                             @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) throws Exception {
        if (snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                null,
                null,
                null,
                snapshot.toolIds()
        ), ProfileOwnerMutation.unchanged());
        String profileId = profileRepository.resolveOrCreateProfileIdInTransaction(connection, snapshot.npcUuid());
        profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, snapshot.toolIds());
        profileRepository.setActiveSnapshotInTransaction(
                connection,
                profileId,
                SNAPSHOT_TYPE,
                toPayloadJson(snapshot),
                Math.max(1L, snapshot.diedAtMs())
        );
        // A live companion cannot remain captured once its death becomes canonical. Leaving both
        // flags active made relocation report "captured" before it ever considered the death.
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, "capture");
        profileRepository.setProfileStateInTransaction(
                connection, profileId, false, true, null, null, null);
        transitionCommandRosterDeath(connection, profileId, snapshot.diedAtMs());
    }

    /**
     * Publishes the roster and lease half of a command companion death in the same transaction as
     * the death snapshot. This prevents the UI and summon scheduler from observing an active lease
     * for a companion whose canonical profile is already dead.
     */
    private static void transitionCommandRosterDeath(Connection connection,
                                                      String profileId,
                                                      long diedAtMs) throws Exception {
        long nowMs = Math.max(1L, diedAtMs);
        ArrayList<RosterKey> projectedRosters = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_uuid, command_family_id
                FROM command_family_roster_memberships
                WHERE profile_id = ?
                  AND command_state IN ('RESTORING','ACTIVE','UNLOADED','STORING')
                """)) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    projectedRosters.add(new RosterKey(
                            result.getString("owner_uuid"),
                            result.getString("command_family_id")));
                }
            }
        }

        for (RosterKey roster : projectedRosters) {
            int changed;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE command_family_roster_memberships
                    SET command_state = 'DEAD_REVIVABLE',
                        profile_revision = COALESCE((
                            SELECT revision FROM companion_population_state WHERE profile_id = ?
                        ), profile_revision),
                        active_for_bulk_commands = 0,
                        updated_at_ms = ?
                    WHERE owner_uuid = ? AND command_family_id = ? AND profile_id = ?
                      AND command_state IN ('RESTORING','ACTIVE','UNLOADED','STORING')
                    """)) {
                statement.setString(1, profileId);
                statement.setLong(2, nowMs);
                statement.setString(3, roster.ownerUuid());
                statement.setString(4, roster.commandFamilyId());
                statement.setString(5, profileId);
                changed = statement.executeUpdate();
            }
            if (changed == 1) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE command_family_rosters
                        SET row_revision = row_revision + 1, updated_at_ms = ?
                        WHERE owner_uuid = ? AND command_family_id = ?
                        """)) {
                    statement.setLong(1, nowMs);
                    statement.setString(2, roster.ownerUuid());
                    statement.setString(3, roster.commandFamilyId());
                    statement.executeUpdate();
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_operations
                SET operation_state = 'CANCELED', result_state = 'DEAD_REVIVABLE',
                    reason = 'projection-died', updated_at_ms = ?, completed_at_ms = 0
                WHERE operation_id IN (
                    SELECT active_operation_id FROM command_timed_summon_sessions
                    WHERE profile_id = ? AND active_operation_id IS NOT NULL
                ) AND operation_state IN ('PREPARED','APPLYING')
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE command_timed_summon_sessions
                SET row_revision = row_revision + 1,
                    summon_state = 'DEAD_REVIVABLE',
                    summon_session_id = NULL,
                    summon_remaining_ms = NULL,
                    resummon_cooldown_until_ms = 0,
                    warning_receipts_json = '[]',
                    summon_last_checkpoint_at_ms = NULL,
                    active_operation_id = NULL,
                    updated_at_ms = ?
                WHERE profile_id = ?
                  AND summon_state IN ('RESTORING','ACTIVE','UNLOADED','STORING')
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    void deleteInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, SNAPSHOT_TYPE);
        profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, new String[0]);
        profileRepository.setProfileStateInTransaction(connection, profileId, null, false, null, null, null);
    }

    private record RosterKey(String ownerUuid, String commandFamilyId) {
    }

    public record OrphanedRosterDeathRecovery(int recovered, int conflicted) {
    }

    private record RecoveryCandidates(
            Map<String, OrphanedRosterDeath> recoverable,
            Set<String> conflictedProfileIds) {
    }

    private record OrphanedRosterDeath(
            UUID ownerUuid,
            String familyId,
            String profileId,
            String roleId,
            UUID npcUuid,
            Vector3d homePosition,
            long diedAtMs) {
        boolean sameRosterIdentity(OrphanedRosterDeath other) {
            return ownerUuid.equals(other.ownerUuid) && familyId.equals(other.familyId);
        }
    }

    @Nonnull
    private String toPayloadJson(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        if (snapshot.ownerId() != null) {
            payload.addProperty("ownerId", snapshot.ownerId().toString());
        }
        putString(payload, "ownerName", snapshot.ownerName());
        putString(payload, "roleId", snapshot.roleId());
        payload.addProperty("tamed", snapshot.tamed());
        putString(payload, "customName", snapshot.customName());
        putString(payload, "displayName", snapshot.displayName());
        putVector(payload, "lastKnownPosition", snapshot.lastKnownPosition());
        putVector(payload, "homePosition", snapshot.homePosition());
        payload.addProperty("diedAtMs", snapshot.diedAtMs());
        payload.addProperty("respawnAvailableAtMs", snapshot.respawnAvailableAtMs());
        putString(payload, "breedingConfigId", snapshot.breedingConfigId());
        if (snapshot.breedingHappiness() != null) {
            payload.addProperty("breedingHappiness", snapshot.breedingHappiness());
        }
        payload.addProperty("breedingCooldownUntilMs", snapshot.breedingCooldownUntilMs());
        if (snapshot.breedingLastPartnerUuid() != null) {
            payload.addProperty("breedingLastPartnerUuid", snapshot.breedingLastPartnerUuid().toString());
        }
        putString(payload, "traitsConfigId", snapshot.traitsConfigId());
        payload.addProperty("traitsRollSeed", snapshot.traitsRollSeed());
        putString(payload, "traitsValues", snapshot.traitsValues());
        putString(payload, "happinessConfigId", snapshot.happinessConfigId());
        if (snapshot.happinessValue() != null) {
            payload.addProperty("happinessValue", snapshot.happinessValue());
        }
        payload.addProperty("happinessLastUpdateMs", snapshot.happinessLastUpdateMs());
        putString(payload, "lifeStage", snapshot.lifeStage());
        payload.addProperty("lifeStageBornAtMs", snapshot.lifeStageBornAtMs());
        payload.addProperty("lifeStageAdolescentAtMs", snapshot.lifeStageAdolescentAtMs());
        payload.addProperty("lifeStageAdultAtMs", snapshot.lifeStageAdultAtMs());
        payload.addProperty("lifeStageFullyGrownAtMs", snapshot.lifeStageFullyGrownAtMs());
        payload.addProperty("lifeStageBabyScale", snapshot.lifeStageBabyScale());
        payload.addProperty("lifeStageAdolescentScale", snapshot.lifeStageAdolescentScale());
        payload.addProperty("lifeStageAdolescentSwitchScale", snapshot.lifeStageAdolescentSwitchScale());
        payload.addProperty("lifeStageAdultStartScale", snapshot.lifeStageAdultStartScale());
        payload.addProperty("lifeStageAdultSwitchScale", snapshot.lifeStageAdultSwitchScale());
        payload.addProperty("lifeStageAdultScale", snapshot.lifeStageAdultScale());
        payload.addProperty("lifeStageGrowthScalingEnabled", snapshot.lifeStageGrowthScalingEnabled());
        putString(payload, "attachmentsConfigId", snapshot.attachmentsConfigId());
        putString(payload, "attachmentsValues", snapshot.attachmentsValues());
        payload.addProperty("breedingEnabled", snapshot.breedingEnabled());
        putString(payload, "levelingConfigId", snapshot.levelingConfigId());
        payload.addProperty("levelingLevel", snapshot.levelingLevel());
        payload.addProperty("levelingTotalXp", snapshot.levelingTotalXp());
        putString(payload, "talentsConfigId", snapshot.talentsConfigId());
        payload.addProperty("talentsSpentPoints", snapshot.talentsSpentPoints());
        putString(payload, "purchasedTalentIds", snapshot.purchasedTalentIds());
        if (snapshot.deathCauseKind() != null) {
            payload.addProperty("deathCauseKind", snapshot.deathCauseKind().name());
        }
        putString(payload, "deathSourceName", snapshot.deathSourceName());
        putString(payload, "lifeStageGender", snapshot.lifeStageGender());
        return payload.toString();
    }

    @Nullable
    private CommandLinkedNpcDeathService.DeathCauseKind parseDeathCauseKind(@Nonnull JsonObject source,
                                                                            @Nonnull String key) {
        String raw = getString(source, key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CommandLinkedNpcDeathService.DeathCauseKind.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private JsonObject parseJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putString(@Nonnull JsonObject object, @Nonnull String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(key, value);
        }
    }

    private void putVector(@Nonnull JsonObject target, @Nonnull String key, @Nullable Vector3d value) {
        if (value == null) {
            return;
        }
        JsonObject vector = new JsonObject();
        vector.addProperty("x", value.x);
        vector.addProperty("y", value.y);
        vector.addProperty("z", value.z);
        target.add(key, vector);
    }

    @Nullable
    private Vector3d readVector(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) {
            return null;
        }
        JsonObject vector = source.getAsJsonObject(key);
        try {
            return new Vector3d(
                    vector.get("x").getAsDouble(),
                    vector.get("y").getAsDouble(),
                    vector.get("z").getAsDouble()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String getString(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = source.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLong(@Nonnull JsonObject source, @Nonnull String key, long fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double getDouble(@Nonnull JsonObject source, @Nonnull String key, double fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private Double getDoubleObj(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return source.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean getBoolean(@Nonnull JsonObject source, @Nonnull String key, boolean fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private UUID parseUuid(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return UUID.fromString(source.get(key).getAsString());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private UUID coalesceUuid(@Nullable UUID first, @Nullable UUID second) {
        if (first != null) {
            return first;
        }
        return second;
    }

    @Nullable
    private String coalesceNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
