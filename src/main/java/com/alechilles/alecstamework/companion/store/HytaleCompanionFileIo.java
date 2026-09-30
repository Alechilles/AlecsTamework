package com.alechilles.alecstamework.companion.store;

import com.hypixel.hytale.server.core.universe.StorageManager;
import com.hypixel.hytale.server.core.util.BsonUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Companion file access through Hytale's {@link StorageManager}, so saves are
 * serialized per path and held back while a Hytale backup runs.
 */
public final class HytaleCompanionFileIo implements CompanionFileIo {
    private final Supplier<StorageManager> storage;

    public HytaleCompanionFileIo(@Nonnull Supplier<StorageManager> storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    @Nonnull
    public CompletableFuture<Void> write(@Nonnull Path file, @Nonnull BsonDocument document) {
        // BsonUtil.writeDocument creates missing parent directories itself.
        return storage.get().doSave(file, () -> BsonUtil.writeDocument(file, document, true));
    }

    @Override
    @Nullable
    public BsonDocument readNow(@Nonnull Path file) throws IOException {
        boolean mainExists = Files.exists(file);
        if (!mainExists && !Files.exists(bak(file))) {
            return null;
        }
        BsonDocument document;
        try {
            // Through doLoad so a pending save or delete of the same path finishes first.
            document = storage.get().doLoad(file, () -> BsonUtil.readDocument(file, true)).join();
        } catch (CompletionException | IllegalStateException e) {
            throw new IOException("unreadable companion file " + file, e);
        }
        if (document == null && mainExists) {
            // The main file exists but neither it nor a backup produced a document.
            throw new IOException("unreadable companion file " + file);
        }
        return document;
    }

    @Override
    @Nonnull
    public CompletableFuture<Void> delete(@Nonnull Path file) {
        return storage.get().doSave(file, () -> CompletableFuture.runAsync(() -> {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(bak(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    /**
     * Also returns {@code <key>.json} for a {@code <key>.json.bak} whose main file is missing.
     * A crash between BsonUtil's two moves can leave only the backup, and {@link #readNow}
     * then loads it.
     */
    @Override
    @Nonnull
    public List<Path> list(@Nonnull Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        TreeSet<Path> result = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".json")) {
                    result.add(p);
                } else if (name.endsWith(".json.bak")) {
                    result.add(p.resolveSibling(name.substring(0, name.length() - ".bak".length())));
                }
            });
        }
        return List.copyOf(result);
    }

    /**
     * Never replaces an existing file, so a name collision fails instead of overwriting an
     * earlier quarantined copy. A backup with no main file becomes {@code <file><suffix>}, so
     * that path always exists after a successful call.
     */
    @Override
    public void moveAside(@Nonnull Path file, @Nonnull String suffix) throws IOException {
        Path aside = file.resolveSibling(file.getFileName() + suffix);
        if (Files.exists(file)) {
            Files.move(file, aside);
            aside = file.resolveSibling(file.getFileName() + ".bak" + suffix);
        }
        if (Files.exists(bak(file))) {
            Files.move(bak(file), aside);
        }
    }

    private static Path bak(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
