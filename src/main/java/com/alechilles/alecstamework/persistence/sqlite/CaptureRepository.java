package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
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

public final class CaptureRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public CaptureRepository(@Nonnull SqliteConnectionManager connectionManager,
                             @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public List<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM capture_snapshots")) {
            while (rs.next()) {
                UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("npc_uuid"));
                if (npcUuid == null) {
                    continue;
                }
                UUID ownerUuid = SqliteValueCodec.parseUuid(rs.getString("owner_uuid"));
                Vector3d lastKnown = SqliteValueCodec.readVector3d(rs, "last_known_x", "last_known_y", "last_known_z");
                Vector3d home = SqliteValueCodec.readVector3d(rs, "home_x", "home_y", "home_z");
                String[] toolIds = loadTools(connection, npcUuid);
                rows.add(new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                        npcUuid,
                        ownerUuid,
                        toolIds,
                        rs.getString("role_id"),
                        rs.getString("display_name"),
                        lastKnown,
                        home,
                        rs.getLong("captured_at_ms")
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean replaceAllAsync(@Nonnull Collection<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> rows) {
        return writeQueue.submit("capture_replace_all", connection -> replaceAllInTransaction(connection, rows));
    }

    void replaceAllInTransaction(@Nonnull Connection connection,
                                 @Nonnull Collection<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> rows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM capture_snapshot_tools");
            statement.executeUpdate("DELETE FROM capture_snapshots");
        }

        try (PreparedStatement insertSnapshot = connection.prepareStatement(
                """
                INSERT INTO capture_snapshots (
                    npc_uuid, owner_uuid, role_id, display_name,
                    last_known_x, last_known_y, last_known_z,
                    home_x, home_y, home_z,
                    captured_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        );
             PreparedStatement insertTool = connection.prepareStatement(
                     "INSERT INTO capture_snapshot_tools (npc_uuid, tool_id) VALUES (?, ?)"
             )) {
            for (CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot row : rows) {
                if (row == null || row.npcUuid() == null) {
                    continue;
                }
                insertSnapshot.setString(1, row.npcUuid().toString());
                SqliteValueCodec.bindUuid(insertSnapshot, 2, row.ownerId());
                insertSnapshot.setString(3, row.roleId());
                insertSnapshot.setString(4, row.displayName());
                SqliteValueCodec.bindVector3d(insertSnapshot, 5, 6, 7, row.lastKnownPosition());
                SqliteValueCodec.bindVector3d(insertSnapshot, 8, 9, 10, row.homePosition());
                insertSnapshot.setLong(11, row.capturedAtMs());
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
                "SELECT tool_id FROM capture_snapshot_tools WHERE npc_uuid = ?"
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
