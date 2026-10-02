package com.alechilles.alecstamework.companion.store;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;

/**
 * Where companion data lives and whether this world may use the new store (spec 7, 12.1).
 * A world that holds old Tamework saves but no new store must not get an empty store: the
 * importer (phase 7) skips worlds whose {@code meta.json} exists, so the old data would be
 * orphaned. Such a world reports {@link Status#MIGRATION_REQUIRED} and nothing is written.
 */
public final class CompanionStorage {
    public enum Status { READY, MIGRATION_REQUIRED }

    public static final int META_FORMAT = 1;

    /**
     * Which old Tamework saves a world holds, and the Tamework release line that converts them.
     * The old files live in any of the old source directories (TameworkDataPathLayout).
     */
    public enum LegacyKind {
        /** 3.x and 4.x SQLite stores. Checked first (spec 12.1). */
        LEGACY_3X_4X("3.x or 4.x", "5.0.x", List.of(
                "tamework-state.sqlite",
                "bonded-companions.sqlite")),
        /** 2.x database and data bundles. */
        LEGACY_2X("2.x", "4.3.x", List.of(
                "tamework.sqlite",
                "CommandLinkedNpcCaptures.dat",
                "CommandLinkedNpcCoops.dat",
                "CommandLinkedNpcDeaths.dat",
                "CommandLinkedNpcLost.dat",
                "CoopResidentSnapshots.dat"));

        private final String dataVersions;
        private final String converterVersion;
        private final List<String> files;

        LegacyKind(String dataVersions, String converterVersion, List<String> files) {
            this.dataVersions = dataVersions;
            this.converterVersion = converterVersion;
            this.files = files;
        }

        /** The Tamework versions that wrote this data, for logs. */
        @Nonnull public String dataVersions() { return dataVersions; }
        /** The Tamework release line an operator must run once on the world to convert it. */
        @Nonnull public String converterVersion() { return converterVersion; }
        @Nonnull public List<String> files() { return files; }
    }

    private CompanionStorage() {
    }

    @Nonnull
    public static Path root(@Nonnull Path universe) {
        return universe.resolve("Tamework").resolve("Companions");
    }

    @Nonnull
    public static Path metaFile(@Nonnull Path root) {
        return root.resolve("meta.json");
    }

    @Nonnull
    public static Status detect(@Nonnull Path root, @Nonnull Collection<Path> legacyDirs, @Nonnull Predicate<Path> exists) {
        return detectLegacy(root, legacyDirs, exists) == null ? Status.READY : Status.MIGRATION_REQUIRED;
    }

    /**
     * The old saves that block this world, or {@code null} when the world may use the new store
     * (it already has one, or holds no old saves). 3.x/4.x data in any directory wins over 2.x.
     */
    @Nullable
    public static LegacyKind detectLegacy(@Nonnull Path root, @Nonnull Collection<Path> legacyDirs,
                                          @Nonnull Predicate<Path> exists) {
        if (exists.test(metaFile(root))) {
            return null;
        }
        for (LegacyKind kind : LegacyKind.values()) {
            for (Path dir : legacyDirs) {
                if (dir == null) {
                    continue;
                }
                for (String name : kind.files()) {
                    if (exists.test(dir.resolve(name))) {
                        return kind;
                    }
                }
            }
        }
        return null;
    }

    @Nonnull
    public static BsonDocument meta(@Nonnull String createdBy) {
        return new BsonDocument("Format", new BsonInt32(META_FORMAT))
                .append("CreatedBy", new BsonString(Objects.requireNonNull(createdBy, "createdBy")));
    }

    /**
     * {@code meta.json} for an empty store an operator created with {@code /tw persistence start-fresh}
     * while old saves blocked the world (spec 12.3). The {@code FreshStart} section is the receipt:
     * {@code FoundData} names the {@link LegacyKind} that was left unconverted and {@code AtMs} is the
     * wall-clock time. Other receipts get their own named section beside it.
     */
    @Nonnull
    public static BsonDocument freshStartMeta(@Nonnull String createdBy, @Nonnull LegacyKind found, long atMs) {
        return meta(createdBy).append("FreshStart",
                new BsonDocument("FoundData", new BsonString(found.name())).append("AtMs", new BsonInt64(atMs)));
    }

    /**
     * The folder the 3.x/4.x importer builds a store in before it becomes {@code root} (spec 12.2):
     * {@code Companions.importing} beside {@code Companions}. It never holds a usable store; a
     * folder left by a killed import is deleted at the next start.
     */
    @Nonnull
    public static Path importingDir(@Nonnull Path root) {
        return root.resolveSibling(root.getFileName() + ".importing");
    }

    /**
     * {@code meta.json} for a store the importer made from old saves. The {@code Import} section is
     * the receipt (source kind, each source file's path, size, modified time and schema, and the
     * counts); {@code CompanionImporter} builds it.
     */
    @Nonnull
    public static BsonDocument importMeta(@Nonnull String createdBy, @Nonnull BsonDocument receipt) {
        return meta(createdBy).append("Import", Objects.requireNonNull(receipt, "receipt"));
    }
}
