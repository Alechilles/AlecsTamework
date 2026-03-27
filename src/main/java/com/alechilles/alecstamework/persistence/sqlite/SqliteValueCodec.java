package com.alechilles.alecstamework.persistence.sqlite;

import com.hypixel.hytale.math.vector.Vector3d;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.annotation.Nullable;

final class SqliteValueCodec {
    private SqliteValueCodec() {
    }

    @Nullable
    static UUID parseUuid(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    static String formatUuid(@Nullable UUID value) {
        return value == null ? null : value.toString();
    }

    static void bindUuid(PreparedStatement statement, int index, @Nullable UUID value) throws SQLException {
        statement.setString(index, formatUuid(value));
    }

    @Nullable
    static Vector3d readVector3d(ResultSet rs,
                                 String xColumn,
                                 String yColumn,
                                 String zColumn) throws SQLException {
        Object xObj = rs.getObject(xColumn);
        Object yObj = rs.getObject(yColumn);
        Object zObj = rs.getObject(zColumn);
        if (xObj == null || yObj == null || zObj == null) {
            return null;
        }
        return new Vector3d(rs.getDouble(xColumn), rs.getDouble(yColumn), rs.getDouble(zColumn));
    }

    static void bindVector3d(PreparedStatement statement,
                             int xIndex,
                             int yIndex,
                             int zIndex,
                             @Nullable Vector3d value) throws SQLException {
        if (value == null) {
            statement.setObject(xIndex, null);
            statement.setObject(yIndex, null);
            statement.setObject(zIndex, null);
            return;
        }
        statement.setDouble(xIndex, value.x);
        statement.setDouble(yIndex, value.y);
        statement.setDouble(zIndex, value.z);
    }
}
