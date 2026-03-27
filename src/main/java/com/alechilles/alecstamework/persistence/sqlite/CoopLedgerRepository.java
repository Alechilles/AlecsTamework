package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class CoopLedgerRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CoopLedgerRepository(@Nonnull SqliteConnectionManager connectionManager,
                                @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public List<CoopLedgerRow> loadAll() {
        ArrayList<CoopLedgerRow> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM coop_slot_ledger")) {
            while (rs.next()) {
                String slotKey = rs.getString("slot_key");
                if (slotKey == null || slotKey.isBlank()) {
                    continue;
                }
                UUID housedUuid = SqliteValueCodec.parseUuid(rs.getString("housed_npc_uuid"));
                UUID lastReleasedUuid = SqliteValueCodec.parseUuid(rs.getString("last_released_npc_uuid"));
                UUID ownerUuid = SqliteValueCodec.parseUuid(rs.getString("owner_uuid"));
                String[] toolIds = loadTools(connection, slotKey);
                rows.add(new CoopLedgerRow(
                        slotKey,
                        rs.getString("world_name"),
                        rs.getString("coop_id"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getInt("resident_slot"),
                        housedUuid,
                        lastReleasedUuid,
                        ownerUuid,
                        toolIds,
                        rs.getString("role_id"),
                        rs.getString("display_name"),
                        rs.getLong("housed_at_ms"),
                        rs.getLong("released_at_ms"),
                        rs.getString("state_snapshot_json")
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean replaceAllAsync(@Nonnull Collection<CoopLedgerRow> rows) {
        return writeQueue.submit("coop_replace_all", connection -> replaceAllInTransaction(connection, rows));
    }

    void replaceAllInTransaction(@Nonnull Connection connection,
                                 @Nonnull Collection<CoopLedgerRow> rows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM coop_slot_tools");
            statement.executeUpdate("DELETE FROM coop_slot_ledger");
        }

        try (PreparedStatement insertLedger = connection.prepareStatement(
                """
                INSERT INTO coop_slot_ledger (
                    slot_key, world_name, coop_id, x, y, z, resident_slot,
                    housed_npc_uuid, last_released_npc_uuid, owner_uuid,
                    role_id, display_name, housed_at_ms, released_at_ms,
                    state_snapshot_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        );
             PreparedStatement insertTool = connection.prepareStatement(
                     "INSERT INTO coop_slot_tools (slot_key, tool_id) VALUES (?, ?)"
             )) {
            for (CoopLedgerRow row : rows) {
                if (row == null || row.slotKey() == null || row.slotKey().isBlank()) {
                    continue;
                }
                int i = 1;
                insertLedger.setString(i++, row.slotKey());
                insertLedger.setString(i++, row.worldName());
                insertLedger.setString(i++, row.coopId());
                insertLedger.setInt(i++, row.x());
                insertLedger.setInt(i++, row.y());
                insertLedger.setInt(i++, row.z());
                insertLedger.setInt(i++, row.residentSlot());
                SqliteValueCodec.bindUuid(insertLedger, i++, row.housedNpcUuid());
                SqliteValueCodec.bindUuid(insertLedger, i++, row.lastReleasedNpcUuid());
                SqliteValueCodec.bindUuid(insertLedger, i++, row.ownerId());
                insertLedger.setString(i++, row.roleId());
                insertLedger.setString(i++, row.displayName());
                insertLedger.setLong(i++, row.housedAtMs());
                insertLedger.setLong(i++, row.releasedAtMs());
                insertLedger.setString(i, row.stateSnapshotJson());
                insertLedger.addBatch();

                if (row.toolIds() != null) {
                    for (String toolId : row.toolIds()) {
                        if (toolId == null || toolId.isBlank()) {
                            continue;
                        }
                        insertTool.setString(1, row.slotKey());
                        insertTool.setString(2, toolId);
                        insertTool.addBatch();
                    }
                }
            }
            insertLedger.executeBatch();
            insertTool.executeBatch();
        }
    }

    private String[] loadTools(@Nonnull Connection connection, @Nonnull String slotKey) throws Exception {
        ArrayList<String> tools = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tool_id FROM coop_slot_tools WHERE slot_key = ?"
        )) {
            statement.setString(1, slotKey);
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
