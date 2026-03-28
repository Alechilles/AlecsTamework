package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Stores profile-scoped namespaced extension data for external API consumers.
 */
public final class ApiProfileDataRepository {
    private static final String RESERVED_NAMESPACE = "Alechilles:Tamework";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public ApiProfileDataRepository(@Nonnull SqliteConnectionManager connectionManager,
                                    @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    @Nullable
    public String get(@Nullable String profileId, @Nullable String namespace, @Nullable String key) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        if (normalizedProfileId == null || normalizedNamespace == null || normalizedKey == null) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT json_payload
                     FROM api_profile_data
                     WHERE profile_id = ? AND namespace = ? AND data_key = ?
                     LIMIT 1
                     """
             )) {
            statement.setString(1, normalizedProfileId);
            statement.setString(2, normalizedNamespace);
            statement.setString(3, normalizedKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String payload = rs.getString("json_payload");
                return payload == null || payload.isBlank() ? null : payload;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    public LinkedHashMap<String, String> list(@Nullable String profileId, @Nullable String namespace) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        if (normalizedProfileId == null || normalizedNamespace == null) {
            return values;
        }
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT data_key, json_payload
                     FROM api_profile_data
                     WHERE profile_id = ? AND namespace = ?
                     ORDER BY data_key
                     """
             )) {
            statement.setString(1, normalizedProfileId);
            statement.setString(2, normalizedNamespace);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String key = normalizeKey(rs.getString("data_key"));
                    String payload = rs.getString("json_payload");
                    if (key != null && payload != null && !payload.isBlank()) {
                        values.put(key, payload);
                    }
                }
            }
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
        return values;
    }

    public boolean putAsync(@Nullable String profileId,
                            @Nullable String namespace,
                            @Nullable String key,
                            @Nullable String jsonPayload) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        String normalizedPayload = normalizePayload(jsonPayload);
        if (normalizedProfileId == null
                || normalizedNamespace == null
                || normalizedKey == null
                || normalizedPayload == null
                || !profileExists(normalizedProfileId)) {
            return false;
        }
        return writeQueue.submit(
                "api_profile_data_put",
                connection -> putInTransaction(connection, normalizedProfileId, normalizedNamespace, normalizedKey, normalizedPayload)
        );
    }

    public boolean deleteAsync(@Nullable String profileId, @Nullable String namespace, @Nullable String key) {
        String normalizedProfileId = normalizeProfileId(profileId);
        String normalizedNamespace = normalizeNamespace(namespace);
        String normalizedKey = normalizeKey(key);
        if (normalizedProfileId == null
                || normalizedNamespace == null
                || normalizedKey == null
                || !profileExists(normalizedProfileId)) {
            return false;
        }
        return writeQueue.submit(
                "api_profile_data_delete",
                connection -> deleteInTransaction(connection, normalizedProfileId, normalizedNamespace, normalizedKey)
        );
    }

    void putInTransaction(@Nonnull Connection connection,
                          @Nonnull String profileId,
                          @Nonnull String namespace,
                          @Nonnull String key,
                          @Nonnull String jsonPayload) throws Exception {
        if (!profileExistsInTransaction(connection, profileId)) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO api_profile_data (
                    profile_id, namespace, data_key, json_payload, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, namespace, data_key) DO UPDATE SET
                    json_payload = excluded.json_payload,
                    updated_at_ms = excluded.updated_at_ms
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            statement.setString(4, jsonPayload);
            statement.setLong(5, nowMs);
            statement.setLong(6, nowMs);
            statement.executeUpdate();
        }
    }

    void deleteInTransaction(@Nonnull Connection connection,
                             @Nonnull String profileId,
                             @Nonnull String namespace,
                             @Nonnull String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM api_profile_data WHERE profile_id = ? AND namespace = ? AND data_key = ?"
        )) {
            statement.setString(1, profileId);
            statement.setString(2, namespace);
            statement.setString(3, key);
            statement.executeUpdate();
        }
    }

    private boolean profileExists(@Nonnull String profileId) {
        try (Connection connection = connectionManager.openConnection()) {
            return profileExistsInTransaction(connection, profileId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean profileExistsInTransaction(@Nonnull Connection connection,
                                               @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1"
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Nullable
    private String normalizeProfileId(@Nullable String profileId) {
        if (profileId == null) {
            return null;
        }
        String trimmed = profileId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private String normalizeNamespace(@Nullable String namespace) {
        if (namespace == null) {
            return null;
        }
        String trimmed = namespace.trim();
        if (trimmed.isEmpty() || RESERVED_NAMESPACE.equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed;
    }

    @Nullable
    private String normalizeKey(@Nullable String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private String normalizePayload(@Nullable String jsonPayload) {
        if (jsonPayload == null) {
            return null;
        }
        String trimmed = jsonPayload.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
