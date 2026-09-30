package com.alechilles.alecstamework.companion.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/** File access for the companion store. Production goes through Hytale's StorageManager. */
public interface CompanionFileIo {
    /** Atomically replaces {@code file} (tmp, then .bak, then move). */
    @Nonnull
    CompletableFuture<Void> write(@Nonnull Path file, @Nonnull BsonDocument document);

    /**
     * Reads {@code file}, falling back to its {@code .bak}. Returns {@code null} when neither
     * exists. Throws when a file exists but neither copy decodes.
     */
    @Nullable
    BsonDocument readNow(@Nonnull Path file) throws IOException;

    @Nonnull
    CompletableFuture<Void> delete(@Nonnull Path file);

    /** Lists {@code *.json} files directly in {@code directory}; empty when it does not exist. */
    @Nonnull
    List<Path> list(@Nonnull Path directory) throws IOException;

    /** Moves {@code file} and its {@code .bak} aside so they are never overwritten. */
    void moveAside(@Nonnull Path file, @Nonnull String suffix) throws IOException;
}
