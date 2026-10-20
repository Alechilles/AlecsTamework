package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.CompanionStore;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * Runs the one-time import of a 3.x/4.x world (spec 12.2): reads the old databases, maps them,
 * writes a normal companion store into {@code Companions.importing/}, checks it, and renames the
 * folder to {@code Companions/}. The store is the same one the module loads on every later start,
 * so the caller just falls through to its normal load.
 *
 * <p>All or nothing (plan 7 R2). The store appears as {@code Companions/} through one atomic
 * folder rename, after every file in it was forced to disk, so a crash, a power loss or a failure
 * at any earlier point leaves a world that imports again from a clean folder at the next start.
 * On failure nothing is left in {@code Companions/}, the importing folder is removed and the old
 * files are untouched.</p>
 *
 * <p>Blocks on file and database I/O. It runs on the plugin start thread before any companion
 * system registers; never call it on a world thread. Folders are deleted and renamed with
 * {@code java.nio.file}, so {@code io} must write real files (production does).</p>
 */
public final class CompanionImporter {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String SCRATCH = "legacy-scratch";
    /** A virus scanner can hold a file inside the new folder for a moment on Windows. */
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_RETRY_MS = 200L;

    /**
     * How an import ended.
     *
     * @param imported   true when {@code Companions/} now holds the imported store
     * @param failure    why nothing was imported; null when {@code imported}
     * @param reportName the report's file name, for the operator notice; set in both cases
     * @param reportFile the report file, or null when it could not be written
     */
    public record Outcome(boolean imported, @Nullable String failure, @Nonnull String reportName,
                          @Nullable Path reportFile) {
    }

    private CompanionImporter() {
    }

    /**
     * Imports the old data found in {@code legacyDirs} into {@code root}. Never throws for import
     * problems; the outcome says what happened.
     *
     * @param root      the companion store folder, which must not hold a store yet
     * @param reportDir where the report file goes (the Tamework data folder)
     * @param clock     wall clock; its value at the start is the import time
     */
    @Nonnull
    public static Outcome run(@Nonnull Path root, @Nonnull Collection<Path> legacyDirs, @Nonnull CompanionFileIo io,
                              @Nonnull LongSupplier clock, @Nonnull String createdBy, @Nonnull Path reportDir) {
        long startedAtMs = clock.getAsLong();
        long startedNanos = System.nanoTime();
        Path importing = CompanionStorage.importingDir(root);
        ImportReport.Sources sources = null;
        ImportResult result;
        try {
            deleteTree(importing);
            clearEmptyRoot(root);
            LegacySource.Located located = LegacySource.locate(legacyDirs);
            if (located.isEmpty()) {
                throw new IOException("no " + LegacySource.STATE_FILE + " or " + LegacySource.BONDED_FILE
                        + " could be opened in " + legacyDirs);
            }
            LOGGER.at(Level.INFO).log("Importing Tamework 3.x/4.x companion data (%s). This runs once and can take "
                    + "a few minutes on a large world; the old files are only read.", describe(located));

            LegacyRows rows = LegacyReader.read(legacyDirs, importing.resolve(SCRATCH));
            deleteTree(importing.resolve(SCRATCH));
            sources = sources(rows, legacyDirs);
            result = LegacyMapper.map(rows, startedAtMs);

            CompanionStore store = new CompanionStore(importing, io, clock);
            for (Map.Entry<String, List<CompanionRecord>> owner : byOwner(result.records()).entrySet()) {
                store.writeOwner(owner.getKey(), 1L, owner.getValue(), null).join();
            }
            for (SnapshotEnvelope snapshot : result.snapshots().values()) {
                store.writeSnapshot(snapshot).join();
            }
            result.aliases().save(io, importing).join();
            verify(importing, io, clock, result);
            // The read-back above came from the OS cache. Force the files to disk before the
            // marker of a finished store exists, or a power loss could leave meta.json and the
            // renamed folder with empty files in it, and that store would never be imported again.
            forceTree(importing);
            Path meta = CompanionStorage.metaFile(importing);
            io.write(meta, CompanionStorage.importMeta(createdBy, receipt(sources, result, startedAtMs))).join();
            force(meta);
            forceDirectory(importing);
            promote(importing, root);
            forceDirectory(root.toAbsolutePath().getParent());
        } catch (LegacySource.Refused | IOException | RuntimeException | OutOfMemoryError failure) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            String reason = failure instanceof LegacySource.Refused refused ? refusal(refused)
                    : failure instanceof OutOfMemoryError
                    ? "not enough memory to import; raise the server's maximum heap size (-Xmx) and restart"
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            cleanUp(importing);
            ImportReport.Sources found = sources;
            Path reportFile = writeReport(reportDir, ImportReport.FAILURE_FILE_NAME,
                    () -> ImportReport.failure(found, reason, root, startedAtMs, createdBy));
            LOGGER.at(Level.WARNING).withCause(cause).log("The Tamework 3.x/4.x companion import failed and wrote "
                    + "nothing: %s. The old files were not changed. Report: %s", reason,
                    reportFile == null ? "could not be written" : reportFile);
            return new Outcome(false, reason, ImportReport.FAILURE_FILE_NAME, reportFile);
        }
        // The store is in place. Nothing below may turn that into a failed import.
        long durationMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        String reportName = ImportReport.fileName(startedAtMs);
        ImportReport.Sources read = sources;
        Path reportFile = writeReport(reportDir, reportName,
                () -> ImportReport.success(read, result, root, startedAtMs, durationMs, createdBy));
        try {
            LOGGER.at(Level.INFO).log("%s Report: %s", ImportReport.summary(result, durationMs),
                    reportFile == null ? "could not be written" : reportFile);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("The companion import finished in %d ms but its "
                    + "summary could not be built", durationMs);
        }
        return new Outcome(true, null, reportName, reportFile);
    }

    private static String refusal(LegacySource.Refused refused) {
        String what = switch (refused.reason()) {
            case UNKNOWN_VERSION -> "the file is not from a Tamework version this importer knows";
            case INTEGRITY_FAILED -> "the file is damaged (SQLite integrity check failed)";
            case UNREADABLE -> "the file could not be read";
            case NOT_ENOUGH_DISK_SPACE -> "there is not enough free disk space to read the file";
        };
        return what + " [" + refused.getMessage() + "]";
    }

    private static String describe(LegacySource.Located located) {
        List<String> parts = new ArrayList<>();
        for (Path file : new Path[] {located.stateFile(), located.bondedFile()}) {
            if (file != null) {
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException unknown) {
                    size = -1L;
                }
                parts.add(file + ", " + size + " bytes");
            }
        }
        return String.join("; ", parts);
    }

    /** The files that were read, and the ones that were found and left out. */
    private static ImportReport.Sources sources(LegacyRows rows, Collection<Path> legacyDirs) {
        List<ImportReport.Source> read = new ArrayList<>();
        if (rows.state() != null) {
            read.add(new ImportReport.Source(rows.state().source(), rows.state().schema().name()));
        }
        if (rows.bonded() != null) {
            read.add(new ImportReport.Source(rows.bonded().source(), "BONDED_V1"));
        }
        List<Path> ignoredCopies = new ArrayList<>();
        for (String name : CompanionStorage.LegacyKind.LEGACY_3X_4X.files()) {
            List<Path> found = existing(legacyDirs, name);
            ignoredCopies.addAll(found.subList(Math.min(1, found.size()), found.size()));
        }
        List<Path> ignored2x = new ArrayList<>();
        for (String name : CompanionStorage.LegacyKind.LEGACY_2X.files()) {
            ignored2x.addAll(existing(legacyDirs, name));
        }
        return new ImportReport.Sources(read, ignoredCopies, ignored2x);
    }

    private static List<Path> existing(Collection<Path> dirs, String name) {
        Set<Path> found = new LinkedHashSet<>();
        for (Path dir : dirs) {
            if (dir != null && Files.isRegularFile(dir.resolve(name))) {
                found.add(dir.resolve(name).toAbsolutePath().normalize());
            }
        }
        return List.copyOf(found);
    }

    private static Map<String, List<CompanionRecord>> byOwner(List<CompanionRecord> records) {
        Map<String, List<CompanionRecord>> byOwner = new LinkedHashMap<>();
        for (CompanionRecord record : records) {
            byOwner.computeIfAbsent(CompanionStore.ownerKey(record.ownerUuid()), key -> new ArrayList<>()).add(record);
        }
        return byOwner;
    }

    /**
     * Reads the written store back the way the module will: every owner file must decode into
     * exactly the mapped records, the alias file must hold the mapped aliases, and every snapshot
     * must read back as the profile, format and generation that were mapped.
     */
    private static void verify(Path importing, CompanionFileIo io, LongSupplier clock, ImportResult result)
            throws IOException {
        CompanionStore store = new CompanionStore(importing, io, clock);
        CompanionStore.LoadResult loaded = store.loadAll();
        if (!loaded.quarantinedFiles().isEmpty() || !loaded.unreadableRecords().isEmpty()) {
            throw new IOException("the written store does not read back: " + loaded.quarantinedFiles().size()
                    + " unreadable owner files, " + loaded.unreadableRecords().size() + " owners with unreadable records");
        }
        Set<UUID> mapped = new HashSet<>();
        result.records().forEach(record -> mapped.add(record.profileId()));
        Set<UUID> written = new HashSet<>();
        loaded.records().forEach(record -> written.add(record.profileId()));
        if (loaded.records().size() != result.records().size() || !written.equals(mapped)) {
            throw new IOException("the written store holds " + loaded.records().size() + " records but "
                    + result.records().size() + " were mapped");
        }
        if (LegacyAliases.load(io, importing).size() != result.aliases().size()) {
            throw new IOException("the written " + LegacyAliases.FILE_NAME + " does not read back");
        }
        int snapshotFiles = io.list(importing.resolve("snapshots")).size();
        if (snapshotFiles != result.snapshots().size()) {
            throw new IOException("the written store holds " + snapshotFiles + " snapshots but "
                    + result.snapshots().size() + " were mapped");
        }
        for (SnapshotEnvelope expected : result.snapshots().values()) {
            SnapshotEnvelope read = store.readSnapshotNow(expected.profileId());
            if (read == null || read.format() != expected.format() || read.generation() != expected.generation()
                    || !read.data().keySet().equals(expected.data().keySet())) {
                throw new IOException("the written snapshot of " + expected.profileId() + " does not read back");
            }
        }
    }

    /** The {@code Import} section of {@code meta.json}. */
    private static BsonDocument receipt(ImportReport.Sources sources, ImportResult result, long atMs) {
        BsonArray files = new BsonArray();
        for (ImportReport.Source source : sources.sources()) {
            files.add(new BsonDocument("Path", new BsonString(source.file().path().toString()))
                    .append("SizeBytes", new BsonInt64(source.file().sizeBytes()))
                    .append("LastModifiedMs", new BsonInt64(source.file().lastModifiedMs()))
                    .append("Schema", new BsonString(source.schema())));
        }
        Map<LocationKind, Integer> kinds = new EnumMap<>(LocationKind.class);
        result.records().forEach(record -> kinds.merge(record.location().kind(), 1, Integer::sum));
        BsonDocument byLocation = new BsonDocument();
        kinds.forEach((kind, count) -> byLocation.put(kind.name(), new BsonInt32(count)));
        return new BsonDocument("SourceKind", new BsonString(CompanionStorage.LegacyKind.LEGACY_3X_4X.name()))
                .append("AtMs", new BsonInt64(atMs))
                .append("Sources", files)
                .append("Counts", new BsonDocument("Records", new BsonInt32(result.records().size()))
                        .append("BondedRecords", new BsonInt32(
                                (int) result.records().stream().filter(CompanionRecord::bonded).count()))
                        .append("Snapshots", new BsonInt32(result.snapshots().size()))
                        .append("Aliases", new BsonInt32(result.aliases().size()))
                        .append("SkippedRows", new BsonInt32(result.report().skippedRows().size()))
                        .append("ByLocation", byLocation));
    }

    /**
     * Makes {@code importing} the store folder with one atomic rename; the two are siblings, so
     * they are on one file store. Any failure fails the import with nothing in {@code root}.
     */
    private static void promote(Path importing, Path root) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(importing, root, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw unsupported;
            } catch (IOException busy) {
                if (attempt >= MOVE_ATTEMPTS) {
                    throw busy;
                }
                try {
                    Thread.sleep(MOVE_RETRY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw busy;
                }
            }
        }
    }

    /** Forces every regular file under {@code folder} to disk, then the folders themselves. */
    private static void forceTree(Path folder) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.toList();
        }
        for (Path path : paths) {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                force(path);
            }
        }
        for (Path path : paths) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                forceDirectory(path);
            }
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Forces a folder's entries to disk where the platform can; Windows cannot open a folder this way. */
    private static void forceDirectory(@Nullable Path folder) {
        if (folder == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException unsupported) {
            // Best effort: the file contents are already forced.
        }
    }

    /**
     * Makes sure {@code root} is free for the import: an empty folder is removed. Anything else is
     * not the importer's to delete or replace, so the import stops: a folder with files in it, or
     * a symbolic link or junction (deleting one would detach the operator's storage).
     */
    private static void clearEmptyRoot(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
            throw new IOException(root + " is a link or junction, which the importer cannot replace with the "
                    + "imported folder. Remove the link, let the import run, then move the folder and link it again");
        }
        if (Files.exists(CompanionStorage.metaFile(root))) {
            throw new IOException(root + " already holds a companion store");
        }
        boolean empty;
        try (Stream<Path> children = Files.list(root)) {
            empty = children.findAny().isEmpty();
        }
        if (!empty) {
            throw new IOException(root + " holds files but no meta.json, so it is not known to be safe to replace. "
                    + "Move that folder away, or restore its meta.json, and restart");
        }
        Files.delete(root);
    }

    /** After a failure: removes the importing folder. */
    private static void cleanUp(Path importing) {
        try {
            deleteTree(importing);
        } catch (IOException | RuntimeException leftover) {
            // The next start deletes it before it imports again; it is never loaded as a store.
            LOGGER.at(Level.WARNING).withCause(leftover).log("Could not remove %s after a failed import", importing);
        }
    }

    @Nullable
    private static Path writeReport(Path reportDir, String fileName, Supplier<String> text) {
        try {
            return ImportReport.write(reportDir, fileName, text.get());
        } catch (IOException | RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not write the import report %s to %s", fileName,
                    reportDir);
            return null;
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(folder)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
