package com.alechilles.alecstamework.companion.migrate;

import com.alechilles.alecstamework.companion.store.CompanionFileIo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonValue;

/**
 * How far the saved-chunk pass ({@link LegacyBodyLocator}) has come, kept in
 * {@link #FILE_NAME} inside the companion folder so a restart continues instead of starting over.
 *
 * <p>A world's chunk indexes are read in ascending order. {@code after} is the last index that was
 * read, so a resumed pass goes on with the first stored index above it. A cursor value is used
 * instead of a count because chunks can be added to or removed from storage between two starts;
 * a count would then skip or repeat the wrong chunks. {@code done} is only for the log.</p>
 *
 * <p>Not thread safe: the pass thread owns it once the pass has started.</p>
 */
final class LegacyLocateProgress {
    static final String FILE_NAME = "locate-progress.json";
    private static final int FORMAT = 1;

    /** One world's progress. {@code started} is false until its first chunk was read. */
    static final class World {
        boolean started;
        long after;
        int done;
        boolean finished;
    }

    private final Map<String, World> worlds = new LinkedHashMap<>();
    /** True once every world was read and the records left over were marked as not found. */
    boolean complete;

    @Nonnull
    World world(@Nonnull String name) {
        return worlds.computeIfAbsent(name, key -> new World());
    }

    /** Forgets everything, for a pass that must start over. */
    void reset() {
        worlds.clear();
        complete = false;
    }

    @Nonnull
    BsonDocument toBson() {
        BsonDocument byWorld = new BsonDocument();
        worlds.forEach((name, world) -> byWorld.put(name, new BsonDocument("Started", BsonBoolean.valueOf(world.started))
                .append("After", new BsonInt64(world.after))
                .append("Done", new BsonInt32(world.done))
                .append("Finished", BsonBoolean.valueOf(world.finished))));
        return new BsonDocument("Format", new BsonInt32(FORMAT))
                .append("Complete", BsonBoolean.valueOf(complete))
                .append("Worlds", byWorld);
    }

    /** @throws RuntimeException when the document is not a progress file this build can read */
    @Nonnull
    static LegacyLocateProgress fromBson(@Nonnull BsonDocument document) {
        BsonValue format = document.get("Format");
        if (format == null || !format.isNumber() || format.asNumber().intValue() != FORMAT) {
            throw new IllegalArgumentException("not a locate progress file of format " + FORMAT);
        }
        LegacyLocateProgress progress = new LegacyLocateProgress();
        progress.complete = document.getBoolean("Complete").getValue();
        for (Map.Entry<String, BsonValue> entry : document.getDocument("Worlds").entrySet()) {
            BsonDocument saved = entry.getValue().asDocument();
            World world = progress.world(entry.getKey());
            world.started = saved.getBoolean("Started").getValue();
            world.after = saved.getNumber("After").longValue();
            world.done = saved.getNumber("Done").intValue();
            world.finished = saved.getBoolean("Finished").getValue();
        }
        return progress;
    }

    /**
     * Reads the file; null when there is none. A file that cannot be read or is malformed also
     * gives null: the pass then starts over, which only repeats work, because filling a record is
     * idempotent. Blocking; call it at start, off the world threads.
     */
    @Nullable
    static LegacyLocateProgress load(@Nonnull CompanionFileIo io, @Nonnull Path storeRoot) {
        try {
            BsonDocument document = io.readNow(storeRoot.resolve(FILE_NAME));
            return document == null ? null : fromBson(document);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Writes the file through the companion store's file access (atomic replace). */
    @Nonnull
    CompletableFuture<Void> save(@Nonnull CompanionFileIo io, @Nonnull Path storeRoot) {
        return io.write(storeRoot.resolve(FILE_NAME), toBson());
    }
}
