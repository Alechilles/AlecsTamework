package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
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

public final class LostRepository {
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public LostRepository(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nonnull
    public List<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM lost_snapshots")) {
            while (rs.next()) {
                UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("npc_uuid"));
                if (npcUuid == null) {
                    continue;
                }
                Vector3d lastKnown = SqliteValueCodec.readVector3d(rs, "last_known_x", "last_known_y", "last_known_z");
                Vector3d home = SqliteValueCodec.readVector3d(rs, "home_x", "home_y", "home_z");
                UUID replacementUuid = SqliteValueCodec.parseUuid(rs.getString("replacement_npc_uuid"));
                rows.add(new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                        npcUuid,
                        lastKnown,
                        home,
                        rs.getLong("last_relocation_queued_at_ms"),
                        rs.getLong("lost_at_ms"),
                        rs.getInt("relocation_retry_attempts"),
                        replacementUuid,
                        rs.getLong("recovered_at_ms")
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean replaceAllAsync(@Nonnull Collection<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> rows) {
        return writeQueue.submit("lost_replace_all", connection -> replaceAllInTransaction(connection, rows));
    }

    void replaceAllInTransaction(@Nonnull Connection connection,
                                 @Nonnull Collection<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> rows) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM lost_snapshots");
        }

        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO lost_snapshots (
                    npc_uuid,
                    last_known_x, last_known_y, last_known_z,
                    home_x, home_y, home_z,
                    last_relocation_queued_at_ms,
                    lost_at_ms,
                    relocation_retry_attempts,
                    replacement_npc_uuid,
                    recovered_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            for (CommandLinkedNpcLostService.LostLinkedNpcSnapshot row : rows) {
                if (row == null || row.npcUuid() == null) {
                    continue;
                }
                insert.setString(1, row.npcUuid().toString());
                SqliteValueCodec.bindVector3d(insert, 2, 3, 4, row.lastKnownPosition());
                SqliteValueCodec.bindVector3d(insert, 5, 6, 7, row.homePosition());
                insert.setLong(8, row.lastRelocationQueuedAtMs());
                insert.setLong(9, row.lostAtMs());
                insert.setInt(10, row.relocationRetryAttempts());
                SqliteValueCodec.bindUuid(insert, 11, row.replacementNpcUuid());
                insert.setLong(12, row.recoveredAtMs());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }
}
