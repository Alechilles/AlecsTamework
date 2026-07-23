package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.identity.CompanionToolLinkPort;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/** Connection-bound adapter for complete profile tool-link sets. */
public final class SqliteCompanionToolLinkStore implements CompanionToolLinkPort {
    private static final Comparator<CompanionToolLink> ORDER =
            Comparator.comparing((CompanionToolLink link) -> link.toolId().toString())
                    .thenComparing(CompanionToolLink::linkType);

    private final Connection connection;

    public SqliteCompanionToolLinkStore(@Nonnull Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Tool-link store connection is required");
        }
        this.connection = connection;
    }

    @Override
    public PersistenceMutationResult<CompanionToolLink> link(CompanionToolLink link) {
        require(link, "Tool link");
        if (!profileExists(link.profileId())) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        CompanionToolLink existing = find(link);
        CompanionToolLink stored = existing == null ? link : new CompanionToolLink(
                link.profileId(),
                link.toolId(),
                link.linkType(),
                existing.createdAtMs(),
                link.updatedAtMs()
        );
        write(stored);
        return PersistenceMutationResult.applied(stored);
    }

    @Override
    public PersistenceMutationResult<List<CompanionToolLink>> replace(
            ProfileId profileId,
            List<CompanionToolLink> links
    ) {
        require(profileId, "Profile ID");
        List<CompanionToolLink> requested = validate(profileId, links);
        if (!profileExists(profileId)) {
            return PersistenceMutationResult.rejected(PersistenceMutationStatus.NOT_FOUND);
        }
        Map<Key, CompanionToolLink> existing = index(findByProfile(profileId));
        deleteByProfile(profileId);
        ArrayList<CompanionToolLink> stored = new ArrayList<>();
        for (CompanionToolLink link : requested) {
            CompanionToolLink previous = existing.get(new Key(link));
            CompanionToolLink next = previous == null ? link : new CompanionToolLink(
                    profileId,
                    link.toolId(),
                    link.linkType(),
                    previous.createdAtMs(),
                    link.updatedAtMs()
            );
            write(next);
            stored.add(next);
        }
        return PersistenceMutationResult.applied(List.copyOf(stored));
    }

    @Override
    public List<CompanionToolLink> findByProfile(ProfileId profileId) {
        require(profileId, "Profile ID");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                FROM companion_tool_link
                WHERE profile_id = ?
                ORDER BY tool_uuid, link_type
                """)) {
            statement.setString(1, profileId.toString());
            ArrayList<CompanionToolLink> links = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    links.add(read(row));
                }
            }
            return List.copyOf(links);
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("tool_link_find_by_profile", failure);
        }
    }

    private CompanionToolLink find(CompanionToolLink link) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                FROM companion_tool_link
                WHERE profile_id = ? AND tool_uuid = ? AND link_type = ?
                """)) {
            statement.setString(1, link.profileId().toString());
            statement.setString(2, link.toolId().toString());
            statement.setString(3, link.linkType());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? read(row) : null;
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("tool_link_find", failure);
        }
    }

    private void write(CompanionToolLink link) {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_tool_link(
                    profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, tool_uuid, link_type)
                DO UPDATE SET updated_at_ms = excluded.updated_at_ms
                """)) {
            statement.setString(1, link.profileId().toString());
            statement.setString(2, link.toolId().toString());
            statement.setString(3, link.linkType());
            statement.setLong(4, link.createdAtMs());
            statement.setLong(5, link.updatedAtMs());
            statement.executeUpdate();
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("tool_link_write", failure);
        }
    }

    private void deleteByProfile(ProfileId profileId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM companion_tool_link WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId.toString());
            statement.executeUpdate();
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("tool_link_replace", failure);
        }
    }

    private boolean profileExists(ProfileId profileId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM companion_profile WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId.toString());
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        } catch (SQLException | RuntimeException failure) {
            throw storeFailure("tool_link_profile_exists", failure);
        }
    }

    private List<CompanionToolLink> validate(
            ProfileId profileId,
            List<CompanionToolLink> links
    ) {
        if (links == null) {
            throw new IllegalArgumentException("Tool links are required");
        }
        ArrayList<CompanionToolLink> copy = new ArrayList<>();
        HashSet<Key> keys = new HashSet<>();
        for (CompanionToolLink link : links) {
            if (link == null || !link.profileId().equals(profileId)
                    || !keys.add(new Key(link))) {
                throw new IllegalArgumentException(
                        "Tool links must be complete, unique, and belong to one profile"
                );
            }
            copy.add(link);
        }
        copy.sort(ORDER);
        return List.copyOf(copy);
    }

    private Map<Key, CompanionToolLink> index(List<CompanionToolLink> links) {
        HashMap<Key, CompanionToolLink> indexed = new HashMap<>();
        for (CompanionToolLink link : links) {
            indexed.put(new Key(link), link);
        }
        return indexed;
    }

    private CompanionToolLink read(ResultSet row) throws SQLException {
        return new CompanionToolLink(
                ProfileId.parse(row.getString("profile_id")),
                java.util.UUID.fromString(row.getString("tool_uuid")),
                row.getString("link_type"),
                row.getLong("created_at_ms"),
                row.getLong("updated_at_ms")
        );
    }

    private PersistenceStoreException storeFailure(String operation, Throwable failure) {
        if (failure instanceof PersistenceStoreException storeException) {
            return storeException;
        }
        return new PersistenceStoreException(operation, failure);
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private record Key(java.util.UUID toolId, String linkType) {
        private Key(CompanionToolLink link) {
            this(link.toolId(), link.linkType());
        }
    }
}
