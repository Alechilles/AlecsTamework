package com.alechilles.alecstamework.access;

import com.alechilles.alecstamework.api.access.AccessLevel;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads and writes every owner's grants as one small JSON file in the Tamework data folder.
 * Callers keep file access off world threads. An entry that cannot be read is skipped; a file
 * that cannot be read at all is reported so the caller leaves it untouched.
 *
 * <p>The same JSON form, through {@link #toJson} and {@link #fromJson}, is the document a server
 * network shares.</p>
 */
public final class SharedAccessFileStore {
    public static final String FILE_NAME = "shared-access.json";

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private SharedAccessFileStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Path dataDirectory) {
        return dataDirectory.resolve(FILE_NAME).toAbsolutePath().normalize();
    }

    /** Reads the saved grants. A missing file is an empty, writable result. */
    @Nonnull
    public static LoadResult load(@Nonnull Path dataDirectory) {
        Path file = resolveFile(dataDirectory);
        if (!Files.isRegularFile(file)) {
            return new LoadResult(Map.of(), true, 0L, Set.of());
        }
        try {
            Document document = parse(Files.readString(file, StandardCharsets.UTF_8));
            long networkRevision = document.networkRevision == null ? 0L : Math.max(0L, document.networkRevision);
            Set<UUID> pending = new LinkedHashSet<>();
            if (document.pendingNetworkOwners != null) {
                for (String raw : document.pendingNetworkOwners) {
                    UUID ownerId = parseUuid(raw);
                    if (ownerId != null) {
                        pending.add(ownerId);
                    }
                }
            }
            return new LoadResult(owners(document), true, networkRevision, pending);
        } catch (Exception unreadable) {
            return new LoadResult(Map.of(), false, 0L, Set.of());
        }
    }

    /**
     * Writes one complete snapshot, replacing the file atomically where the filesystem allows.
     *
     * @param networkRevision      the last network revision this server applied, or 0 when it
     *                             never followed a network's grants
     * @param pendingNetworkOwners owners changed here and not yet saved to the network
     */
    public static boolean save(@Nonnull Path dataDirectory, @Nonnull Map<UUID, OwnerGrants> owners,
                               long networkRevision, @Nonnull Set<UUID> pendingNetworkOwners) {
        Path file = resolveFile(dataDirectory);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, toJson(owners, networkRevision, pendingNetworkOwners) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception failed) {
            return false;
        }
    }

    /** Every owner's grants as one JSON document: the form a server network shares. */
    @Nonnull
    public static String toJson(@Nonnull Map<UUID, OwnerGrants> owners) {
        return toJson(owners, 0L, Set.of());
    }

    /**
     * Reads a document written by {@link #toJson}. An entry that cannot be read is skipped.
     *
     * @throws RuntimeException when {@code json} is not a grants document at all, or a newer
     *                          build wrote it
     */
    @Nonnull
    public static Map<UUID, OwnerGrants> fromJson(@Nonnull String json) {
        return owners(parse(json));
    }

    @Nonnull
    private static String toJson(@Nonnull Map<UUID, OwnerGrants> owners, long networkRevision,
                                 @Nonnull Set<UUID> pendingNetworkOwners) {
        Document document = new Document();
        document.version = CURRENT_VERSION;
        document.networkRevision = networkRevision > 0L ? networkRevision : null;
        if (!pendingNetworkOwners.isEmpty()) {
            document.pendingNetworkOwners = new ArrayList<>();
            pendingNetworkOwners.forEach(ownerId -> document.pendingNetworkOwners.add(ownerId.toString()));
        }
        document.owners = new LinkedHashMap<>();
        for (Map.Entry<UUID, OwnerGrants> entry : owners.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                document.owners.put(entry.getKey().toString(), toDocument(entry.getValue()));
            }
        }
        return GSON.toJson(document);
    }

    @Nonnull
    private static Document parse(@Nonnull String json) {
        Document document = GSON.fromJson(json, Document.class);
        if (document == null) {
            throw new IllegalArgumentException("The shared access document is empty.");
        }
        // A newer build may store levels or fields this one would drop, and the next save would
        // then delete them everywhere.
        if (document.version != null && document.version > CURRENT_VERSION) {
            throw new IllegalArgumentException("The shared access document has version " + document.version
                    + "; this build reads up to " + CURRENT_VERSION + ".");
        }
        return document;
    }

    @Nonnull
    private static Map<UUID, OwnerGrants> owners(@Nonnull Document document) {
        Map<UUID, OwnerGrants> owners = new LinkedHashMap<>();
        if (document.owners != null) {
            for (Map.Entry<String, OwnerDocument> entry : document.owners.entrySet()) {
                UUID ownerId = parseUuid(entry.getKey());
                OwnerGrants grants = ownerId == null ? null : toGrants(entry.getValue());
                if (grants != null && !grants.isEmpty()) {
                    owners.put(ownerId, grants);
                }
            }
        }
        return owners;
    }

    @Nullable
    private static OwnerGrants toGrants(@Nullable OwnerDocument owner) {
        if (owner == null) {
            return null;
        }
        Map<UUID, OwnerGrants.PlayerGrant> players = new LinkedHashMap<>();
        if (owner.players != null) {
            for (Map.Entry<String, PlayerDocument> entry : owner.players.entrySet()) {
                UUID playerId = parseUuid(entry.getKey());
                AccessLevel level = entry.getValue() == null ? null : parseLevel(entry.getValue().level);
                if (playerId != null && level != null) {
                    players.put(playerId, new OwnerGrants.PlayerGrant(playerId, entry.getValue().name, level));
                }
            }
        }
        Map<String, AccessLevel> groups = new LinkedHashMap<>();
        if (owner.groups != null) {
            for (Map.Entry<String, String> entry : owner.groups.entrySet()) {
                AccessLevel level = parseLevel(entry.getValue());
                if (entry.getKey() != null && !entry.getKey().isBlank() && level != null) {
                    groups.put(entry.getKey(), level);
                }
            }
        }
        Set<UUID> privateCompanions = new LinkedHashSet<>();
        if (owner.privateCompanions != null) {
            for (String raw : owner.privateCompanions) {
                UUID profileId = parseUuid(raw);
                if (profileId != null) {
                    privateCompanions.add(profileId);
                }
            }
        }
        return new OwnerGrants(players, groups, privateCompanions);
    }

    @Nonnull
    private static OwnerDocument toDocument(@Nonnull OwnerGrants grants) {
        OwnerDocument owner = new OwnerDocument();
        owner.players = new LinkedHashMap<>();
        for (OwnerGrants.PlayerGrant grant : grants.players().values()) {
            PlayerDocument player = new PlayerDocument();
            player.name = grant.playerName();
            player.level = grant.level().name();
            owner.players.put(grant.playerId().toString(), player);
        }
        owner.groups = new LinkedHashMap<>();
        grants.groups().forEach((groupId, level) -> owner.groups.put(groupId, level.name()));
        owner.privateCompanions = new ArrayList<>();
        grants.privateCompanions().forEach(profileId -> owner.privateCompanions.add(profileId.toString()));
        return owner;
    }

    @Nullable
    private static AccessLevel parseLevel(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            AccessLevel level = AccessLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return level.isGrantable() ? level : null;
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    @Nullable
    private static UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * The saved grants, and whether the file may be replaced.
     *
     * @param networkRevision      the last network revision this server applied; 0 when the file
     *                             has none, which means this server never followed a network's grants
     * @param pendingNetworkOwners owners changed here and not yet saved to the network; empty when
     *                             the file has none
     */
    public record LoadResult(@Nonnull Map<UUID, OwnerGrants> owners, boolean writable, long networkRevision,
                             @Nonnull Set<UUID> pendingNetworkOwners) {
        public LoadResult {
            owners = Map.copyOf(owners);
            pendingNetworkOwners = Set.copyOf(pendingNetworkOwners);
        }
    }

    private static final class Document {
        private Integer version;
        /** Only in this server's own file, never in the network's document. */
        private Long networkRevision;
        /** Only in this server's own file: owners changed here and not yet saved to the network. */
        private List<String> pendingNetworkOwners;
        private LinkedHashMap<String, OwnerDocument> owners;
    }

    private static final class OwnerDocument {
        private LinkedHashMap<String, PlayerDocument> players;
        private LinkedHashMap<String, String> groups;
        private List<String> privateCompanions;
    }

    private static final class PlayerDocument {
        private String name;
        private String level;
    }
}
