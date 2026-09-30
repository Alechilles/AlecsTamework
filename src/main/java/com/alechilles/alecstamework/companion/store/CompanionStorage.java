package com.alechilles.alecstamework.companion.store;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
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

    /** Old persistence files, in any of the old source directories (TameworkDataPathLayout). */
    public static final List<String> LEGACY_FILES = List.of(
            "tamework-state.sqlite",
            "bonded-companions.sqlite",
            "tamework.sqlite",
            "CommandLinkedNpcCaptures.dat",
            "CommandLinkedNpcCoops.dat",
            "CommandLinkedNpcDeaths.dat",
            "CommandLinkedNpcLost.dat",
            "CoopResidentSnapshots.dat");

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
        if (exists.test(metaFile(root))) {
            return Status.READY;
        }
        for (Path dir : legacyDirs) {
            if (dir == null) {
                continue;
            }
            for (String name : LEGACY_FILES) {
                if (exists.test(dir.resolve(name))) {
                    return Status.MIGRATION_REQUIRED;
                }
            }
        }
        return Status.READY;
    }

    @Nonnull
    public static BsonDocument meta(@Nonnull String createdBy) {
        return new BsonDocument("Format", new BsonInt32(META_FORMAT))
                .append("CreatedBy", new BsonString(Objects.requireNonNull(createdBy, "createdBy")));
    }
}
