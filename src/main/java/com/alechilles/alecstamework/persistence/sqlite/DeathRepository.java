package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.hypixel.hytale.math.vector.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class DeathRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public DeathRepository(@Nonnull SqliteConnectionManager connectionManager,
                           @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public List<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM death_snapshots")) {
            while (rs.next()) {
                UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("npc_uuid"));
                if (npcUuid == null) {
                    continue;
                }
                UUID ownerUuid = SqliteValueCodec.parseUuid(rs.getString("owner_uuid"));
                Vector3d lastKnown = SqliteValueCodec.readVector3d(rs, "last_known_x", "last_known_y", "last_known_z");
                Vector3d home = SqliteValueCodec.readVector3d(rs, "home_x", "home_y", "home_z");
                UUID breedingLastPartnerUuid = SqliteValueCodec.parseUuid(rs.getString("breeding_last_partner_uuid"));
                String[] toolIds = loadTools(connection, npcUuid);
                rows.add(new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        rs.getString("owner_name"),
                        toolIds,
                        rs.getString("role_id"),
                        rs.getInt("tamed") != 0,
                        rs.getString("custom_name"),
                        rs.getString("display_name"),
                        lastKnown,
                        home,
                        rs.getLong("died_at_ms"),
                        rs.getLong("respawn_available_at_ms"),
                        rs.getString("breeding_config_id"),
                        (Double) rs.getObject("breeding_happiness"),
                        rs.getLong("breeding_cooldown_until_ms"),
                        breedingLastPartnerUuid,
                        rs.getString("traits_config_id"),
                        rs.getLong("traits_roll_seed"),
                        rs.getString("traits_values"),
                        rs.getString("happiness_config_id"),
                        (Double) rs.getObject("happiness_value"),
                        rs.getLong("happiness_last_update_ms"),
                        rs.getString("life_stage"),
                        rs.getLong("life_stage_born_at_ms"),
                        rs.getLong("life_stage_adolescent_at_ms"),
                        rs.getLong("life_stage_adult_at_ms"),
                        rs.getLong("life_stage_fully_grown_at_ms"),
                        rs.getDouble("life_stage_baby_scale"),
                        rs.getDouble("life_stage_adolescent_scale"),
                        rs.getDouble("life_stage_adolescent_switch_scale"),
                        rs.getDouble("life_stage_adult_start_scale"),
                        rs.getDouble("life_stage_adult_switch_scale"),
                        rs.getDouble("life_stage_adult_scale"),
                        rs.getInt("life_stage_growth_scaling_enabled") != 0,
                        rs.getString("attachments_config_id"),
                        rs.getString("attachments_values"),
                        rs.getInt("breeding_enabled") != 0
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean replaceAllAsync(@Nonnull Collection<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> rows) {
        return writeQueue.submit("death_replace_all", connection -> replaceAllInTransaction(connection, rows));
    }

    void replaceAllInTransaction(@Nonnull Connection connection,
                                 @Nonnull Collection<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> rows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM death_snapshot_tools");
            statement.executeUpdate("DELETE FROM death_snapshots");
        }

        try (PreparedStatement insertSnapshot = connection.prepareStatement(
                """
                INSERT INTO death_snapshots (
                    npc_uuid, owner_uuid, owner_name, role_id, tamed,
                    custom_name, display_name,
                    last_known_x, last_known_y, last_known_z,
                    home_x, home_y, home_z,
                    died_at_ms, respawn_available_at_ms,
                    breeding_config_id, breeding_happiness, breeding_cooldown_until_ms,
                    breeding_last_partner_uuid,
                    traits_config_id, traits_roll_seed, traits_values,
                    happiness_config_id, happiness_value, happiness_last_update_ms,
                    life_stage,
                    life_stage_born_at_ms, life_stage_adolescent_at_ms, life_stage_adult_at_ms, life_stage_fully_grown_at_ms,
                    life_stage_baby_scale, life_stage_adolescent_scale, life_stage_adolescent_switch_scale,
                    life_stage_adult_start_scale, life_stage_adult_switch_scale, life_stage_adult_scale,
                    life_stage_growth_scaling_enabled,
                    attachments_config_id, attachments_values, breeding_enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        );
             PreparedStatement insertTool = connection.prepareStatement(
                     "INSERT INTO death_snapshot_tools (npc_uuid, tool_id) VALUES (?, ?)"
             )) {
            for (CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot row : rows) {
                if (row == null || row.npcUuid() == null) {
                    continue;
                }
                int i = 1;
                insertSnapshot.setString(i++, row.npcUuid().toString());
                SqliteValueCodec.bindUuid(insertSnapshot, i++, row.ownerId());
                insertSnapshot.setString(i++, row.ownerName());
                insertSnapshot.setString(i++, row.roleId());
                insertSnapshot.setInt(i++, row.tamed() ? 1 : 0);
                insertSnapshot.setString(i++, row.customName());
                insertSnapshot.setString(i++, row.displayName());
                SqliteValueCodec.bindVector3d(insertSnapshot, i, i + 1, i + 2, row.lastKnownPosition());
                i += 3;
                SqliteValueCodec.bindVector3d(insertSnapshot, i, i + 1, i + 2, row.homePosition());
                i += 3;
                insertSnapshot.setLong(i++, row.diedAtMs());
                insertSnapshot.setLong(i++, row.respawnAvailableAtMs());
                insertSnapshot.setString(i++, row.breedingConfigId());
                insertSnapshot.setObject(i++, row.breedingHappiness());
                insertSnapshot.setLong(i++, row.breedingCooldownUntilMs());
                SqliteValueCodec.bindUuid(insertSnapshot, i++, row.breedingLastPartnerUuid());
                insertSnapshot.setString(i++, row.traitsConfigId());
                insertSnapshot.setLong(i++, row.traitsRollSeed());
                insertSnapshot.setString(i++, row.traitsValues());
                insertSnapshot.setString(i++, row.happinessConfigId());
                insertSnapshot.setObject(i++, row.happinessValue());
                insertSnapshot.setLong(i++, row.happinessLastUpdateMs());
                insertSnapshot.setString(i++, row.lifeStage());
                insertSnapshot.setLong(i++, row.lifeStageBornAtMs());
                insertSnapshot.setLong(i++, row.lifeStageAdolescentAtMs());
                insertSnapshot.setLong(i++, row.lifeStageAdultAtMs());
                insertSnapshot.setLong(i++, row.lifeStageFullyGrownAtMs());
                insertSnapshot.setDouble(i++, row.lifeStageBabyScale());
                insertSnapshot.setDouble(i++, row.lifeStageAdolescentScale());
                insertSnapshot.setDouble(i++, row.lifeStageAdolescentSwitchScale());
                insertSnapshot.setDouble(i++, row.lifeStageAdultStartScale());
                insertSnapshot.setDouble(i++, row.lifeStageAdultSwitchScale());
                insertSnapshot.setDouble(i++, row.lifeStageAdultScale());
                insertSnapshot.setInt(i++, row.lifeStageGrowthScalingEnabled() ? 1 : 0);
                insertSnapshot.setString(i++, row.attachmentsConfigId());
                insertSnapshot.setString(i++, row.attachmentsValues());
                insertSnapshot.setInt(i, row.breedingEnabled() ? 1 : 0);
                insertSnapshot.addBatch();

                if (row.toolIds() != null) {
                    for (String toolId : row.toolIds()) {
                        if (toolId == null || toolId.isBlank()) {
                            continue;
                        }
                        insertTool.setString(1, row.npcUuid().toString());
                        insertTool.setString(2, toolId);
                        insertTool.addBatch();
                    }
                }
            }
            insertSnapshot.executeBatch();
            insertTool.executeBatch();
        }
    }

    private String[] loadTools(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        ArrayList<String> tools = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tool_id FROM death_snapshot_tools WHERE npc_uuid = ?"
        )) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String toolId = rs.getString("tool_id");
                    if (toolId != null && !toolId.isBlank()) {
                        tools.add(toolId);
                    }
                }
            }
        }
        return tools.toArray(new String[0]);
    }
}
