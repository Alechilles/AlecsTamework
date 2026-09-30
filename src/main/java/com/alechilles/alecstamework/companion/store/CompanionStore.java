package com.alechilles.alecstamework.companion.store;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.RecordScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;

/**
 * File layout for companion data (spec 7): {@code owners/<owner>.json} holds every record of
 * one owner, split into world-bound and portable sections; {@code snapshots/<profileId>.json}
 * holds one snapshot. Loading repairs what it can and never overwrites what it cannot read.
 */
public final class CompanionStore {
    public static final int FORMAT = 1;
    public static final String UNOWNED_KEY = "_unowned";

    /** Result of reading every owner file at startup. */
    public record LoadResult(
            @Nonnull List<CompanionRecord> records,
            @Nonnull Map<String, List<BsonDocument>> unreadableRecords,
            @Nonnull Set<UUID> unreadableIds,
            @Nonnull Map<String, Long> versions,
            @Nonnull List<Path> quarantinedFiles
    ) {
    }

    private final Path ownersDir;
    private final Path snapshotsDir;
    private final CompanionFileIo io;
    private final LongSupplier clock;

    public CompanionStore(@Nonnull Path root, @Nonnull CompanionFileIo io, @Nonnull LongSupplier clock) {
        this.ownersDir = root.resolve("owners");
        this.snapshotsDir = root.resolve("snapshots");
        this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Nonnull
    public static String ownerKey(@Nullable UUID owner) {
        return owner == null ? UNOWNED_KEY : owner.toString();
    }

    @Nullable
    public static UUID ownerOf(@Nonnull String key) {
        return UNOWNED_KEY.equals(key) ? null : UUID.fromString(key);
    }

    /** Reads every owner file. A profile found in two files keeps the higher revision. */
    @Nonnull
    public LoadResult loadAll() throws IOException {
        Map<UUID, CompanionRecord> best = new LinkedHashMap<>();
        Map<String, List<BsonDocument>> unreadable = new HashMap<>();
        Set<UUID> unreadableIds = new HashSet<>();
        Map<String, Long> versions = new HashMap<>();
        List<Path> quarantined = new ArrayList<>();
        for (Path file : io.list(ownersDir)) {
            String name = file.getFileName().toString();
            String key = name.substring(0, name.length() - ".json".length());
            BsonDocument doc;
            try {
                doc = io.readNow(file);
            } catch (IOException | RuntimeException e) {
                String suffix = ".unreadable-" + clock.getAsLong();
                io.moveAside(file, suffix);
                quarantined.add(file.resolveSibling(name + suffix));
                continue;
            }
            if (doc == null) {
                continue;
            }
            BsonValue version = doc.get("Version");
            versions.put(key, version != null && version.isNumber() ? version.asNumber().longValue() : 0L);
            List<BsonDocument> raw = new ArrayList<>();
            for (String section : List.of("WorldBound", "Portable", "Unreadable")) {
                if (doc.isArray(section)) {
                    for (BsonValue value : doc.getArray(section)) {
                        if (value.isDocument()) {
                            raw.add(value.asDocument());
                        }
                    }
                }
            }
            for (BsonDocument entry : raw) {
                try {
                    CompanionRecord record = CompanionRecordBson.decode(entry);
                    CompanionRecord existing = best.get(record.profileId());
                    if (existing == null || record.revision() > existing.revision()) {
                        best.put(record.profileId(), record);
                    }
                } catch (IllegalArgumentException e) {
                    unreadable.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                    if (entry.isString("ProfileId")) {
                        try {
                            unreadableIds.add(UUID.fromString(entry.getString("ProfileId").getValue()));
                        } catch (IllegalArgumentException ignored) {
                            // An entry without a usable id is still preserved; it just cannot be fenced by id.
                        }
                    }
                }
            }
        }
        return new LoadResult(new ArrayList<>(best.values()), unreadable, unreadableIds, versions, quarantined);
    }

    /**
     * Replaces one owner file. {@code unreadable} entries are written back unchanged. An owner
     * with nothing left has its file deleted.
     */
    @Nonnull
    public CompletableFuture<Void> writeOwner(@Nonnull String ownerKey, long version,
                                              @Nonnull List<CompanionRecord> records,
                                              @Nullable List<BsonDocument> unreadable) {
        Path file = ownersDir.resolve(ownerKey + ".json");
        List<BsonDocument> kept = unreadable == null ? List.of() : unreadable;
        if (records.isEmpty() && kept.isEmpty()) {
            return io.delete(file);
        }
        BsonArray worldBound = new BsonArray();
        BsonArray portable = new BsonArray();
        for (CompanionRecord record : records) {
            (record.scope() == RecordScope.PORTABLE ? portable : worldBound).add(CompanionRecordBson.encode(record));
        }
        BsonDocument doc = new BsonDocument("Format", new BsonInt32(FORMAT))
                .append("Owner", new BsonString(ownerKey))
                .append("Version", new BsonInt64(version))
                .append("WorldBound", worldBound)
                .append("Portable", portable)
                .append("Unreadable", new BsonArray(new ArrayList<>(kept)));
        return io.write(file, doc);
    }

    @Nonnull
    public CompletableFuture<Void> writeSnapshot(@Nonnull SnapshotEnvelope snapshot) {
        return io.write(snapshotFile(snapshot.profileId()), snapshot.toBson());
    }

    /** Blocking read; call it off the world thread. {@code null} when there is no snapshot. */
    @Nullable
    public SnapshotEnvelope readSnapshotNow(@Nonnull UUID profileId) throws IOException {
        BsonDocument doc = io.readNow(snapshotFile(profileId));
        return doc == null ? null : SnapshotEnvelope.fromBson(doc);
    }

    @Nonnull
    public CompletableFuture<Void> deleteSnapshot(@Nonnull UUID profileId) {
        return io.delete(snapshotFile(profileId));
    }

    private Path snapshotFile(UUID profileId) {
        return snapshotsDir.resolve(profileId + ".json");
    }
}
