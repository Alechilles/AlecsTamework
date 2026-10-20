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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final int SNAPSHOT_FORMAT = 1;
    private static final List<String> SECTIONS = List.of("WorldBound", "Portable", "Unreadable");
    public static final String UNOWNED_KEY = "_unowned";
    /** Upper bound for the startup load pool; more threads gain little on parse and decode. */
    private static final int MAX_LOAD_THREADS = 8;

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

    /**
     * Reads every owner file. A profile found in two files keeps the higher revision. A file
     * whose every copy fails to parse is moved aside. A file that cannot be read at the I/O
     * level aborts the whole load with {@link CompanionFileAccessException} and is left in place,
     * so the caller disables companion persistence (spec 10) instead of starting that owner empty.
     *
     * <p>Files are read, parsed and decoded on a short-lived pool that is gone when this returns.
     * Results are merged on the calling thread in the order {@link CompanionFileIo#list} gave, so
     * the outcome is the same as reading the files one after another.</p>
     */
    @Nonnull
    public LoadResult loadAll() throws IOException {
        List<Path> files = io.list(ownersDir);
        List<OwnerFile> loaded = readOwnerFiles(files);
        Map<UUID, CompanionRecord> best = new LinkedHashMap<>();
        Map<String, List<BsonDocument>> unreadable = new HashMap<>();
        Set<UUID> unreadableIds = new HashSet<>();
        Map<String, Long> versions = new HashMap<>();
        List<Path> quarantined = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            OwnerFile owner = loaded.get(i);
            if (owner.readFailure() instanceof CompanionFileAccessException access) {
                throw access;
            }
            if (owner.readFailure() != null || owner.unsupported()) {
                // No copy parses, or readable but not a layout this build can rewrite without losing data.
                quarantined.add(moveAside(file));
                continue;
            }
            if (owner.missing()) {
                continue;
            }
            String name = file.getFileName().toString();
            String key = name.substring(0, name.length() - ".json".length());
            versions.put(key, owner.version());
            for (CompanionRecord record : owner.records()) {
                CompanionRecord existing = best.get(record.profileId());
                if (existing == null || record.revision() > existing.revision()) {
                    best.put(record.profileId(), record);
                }
            }
            if (owner.unreadable().isEmpty()) {
                continue;
            }
            unreadable.put(key, owner.unreadable());
            for (BsonDocument entry : owner.unreadable()) {
                if (entry.isString("ProfileId")) {
                    try {
                        unreadableIds.add(UUID.fromString(entry.getString("ProfileId").getValue()));
                    } catch (IllegalArgumentException ignored) {
                        // An entry without a usable id is still preserved; it just cannot be fenced by id.
                    }
                }
            }
        }
        return new LoadResult(new ArrayList<>(best.values()), unreadable, unreadableIds, versions, quarantined);
    }

    /**
     * One owner file after read and decode. {@code readFailure} is what {@code readNow} threw;
     * {@code missing} means no copy exists; {@code unsupported} means {@link #entries} refused it.
     */
    private record OwnerFile(@Nullable Exception readFailure, boolean missing, boolean unsupported, long version,
                             List<CompanionRecord> records, List<BsonDocument> unreadable) {
        static OwnerFile withoutRecords(@Nullable Exception readFailure, boolean missing, boolean unsupported) {
            return new OwnerFile(readFailure, missing, unsupported, 0L, List.of(), List.of());
        }
    }

    /**
     * Reads and decodes every file, one result per file in the same order. Nothing here moves or
     * writes a file or touches shared state, so one file's failure cannot affect another.
     */
    private List<OwnerFile> readOwnerFiles(List<Path> files) {
        List<OwnerFile> loaded = new ArrayList<>(files.size());
        if (files.isEmpty()) {
            return loaded;
        }
        int threads = Math.min(files.size(), Math.min(Runtime.getRuntime().availableProcessors(), MAX_LOAD_THREADS));
        // close() waits for every task, also when a join below throws, so no thread outlives the load.
        try (ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "tamework-companion-load");
            thread.setDaemon(true);
            return thread;
        })) {
            List<CompletableFuture<OwnerFile>> pending = new ArrayList<>(files.size());
            for (Path file : files) {
                pending.add(CompletableFuture.supplyAsync(() -> readOwnerFile(file), pool));
            }
            for (CompletableFuture<OwnerFile> future : pending) {
                try {
                    loaded.add(future.join());
                } catch (CompletionException e) {
                    if (e.getCause() instanceof RuntimeException unexpected) {
                        throw unexpected;
                    }
                    if (e.getCause() instanceof Error error) {
                        throw error;
                    }
                    throw e;
                }
            }
        }
        return loaded;
    }

    private OwnerFile readOwnerFile(Path file) {
        BsonDocument doc;
        try {
            doc = io.readNow(file);
        } catch (IOException | RuntimeException e) {
            return OwnerFile.withoutRecords(e, false, false);
        }
        if (doc == null) {
            return OwnerFile.withoutRecords(null, true, false);
        }
        List<BsonDocument> raw = entries(doc);
        if (raw == null) {
            return OwnerFile.withoutRecords(null, false, true);
        }
        BsonValue version = doc.get("Version");
        List<CompanionRecord> records = new ArrayList<>(raw.size());
        List<BsonDocument> unreadable = new ArrayList<>();
        for (BsonDocument entry : raw) {
            try {
                records.add(CompanionRecordBson.decode(entry));
            } catch (IllegalArgumentException e) {
                unreadable.add(entry);
            }
        }
        return new OwnerFile(null, false, false,
                version != null && version.isNumber() ? version.asNumber().longValue() : 0L, records, unreadable);
    }

    /**
     * Returns every record entry of an owner document, or {@code null} when this build cannot
     * rewrite the file safely: the format is missing or newer, or a section holds something
     * other than record documents.
     */
    @Nullable
    private static List<BsonDocument> entries(BsonDocument doc) {
        BsonValue format = doc.get("Format");
        if (format == null || !format.isNumber() || format.asNumber().longValue() > FORMAT) {
            return null;
        }
        List<BsonDocument> raw = new ArrayList<>();
        for (String section : SECTIONS) {
            BsonValue value = doc.get(section);
            if (value == null) {
                continue;
            }
            if (!value.isArray()) {
                return null;
            }
            for (BsonValue entry : value.asArray()) {
                if (!entry.isDocument()) {
                    return null;
                }
                raw.add(entry.asDocument());
            }
        }
        return raw;
    }

    private Path moveAside(Path file) throws IOException {
        String suffix = ".unreadable-" + clock.getAsLong();
        io.moveAside(file, suffix);
        return file.resolveSibling(file.getFileName() + suffix);
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

    /**
     * Blocking read; call it off the world thread. {@code null} when there is no snapshot.
     * Throws {@link IOException} when the file is not a snapshot envelope, belongs to another
     * profile, or uses a newer format, and {@link CompanionFileAccessException} when the file
     * cannot be read at the I/O level.
     */
    @Nullable
    public SnapshotEnvelope readSnapshotNow(@Nonnull UUID profileId) throws IOException {
        Path file = snapshotFile(profileId);
        BsonDocument doc = io.readNow(file);
        if (doc == null) {
            return null;
        }
        SnapshotEnvelope snapshot;
        try {
            snapshot = SnapshotEnvelope.fromBson(doc);
        } catch (RuntimeException e) {
            throw new IOException("unreadable snapshot " + file, e);
        }
        if (!snapshot.profileId().equals(profileId)) {
            throw new IOException("snapshot " + file + " belongs to " + snapshot.profileId());
        }
        if (snapshot.format() > SNAPSHOT_FORMAT) {
            throw new IOException("snapshot " + file + " has newer format " + snapshot.format());
        }
        return snapshot;
    }

    @Nonnull
    public CompletableFuture<Void> deleteSnapshot(@Nonnull UUID profileId) {
        return io.delete(snapshotFile(profileId));
    }

    private Path snapshotFile(UUID profileId) {
        return snapshotsDir.resolve(profileId + ".json");
    }
}
