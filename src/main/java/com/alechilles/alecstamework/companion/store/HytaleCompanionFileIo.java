package com.alechilles.alecstamework.companion.store;

import com.hypixel.hytale.server.core.universe.StorageManager;
import com.hypixel.hytale.server.core.util.BsonUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
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
        return storage.get().doSave(file, () -> {
            try {
                Files.createDirectories(file.getParent());
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return BsonUtil.writeDocument(file, document, true);
        });
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
            document = BsonUtil.readDocument(file, true).join();
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

    @Override
    @Nonnull
    public List<Path> list(@Nonnull Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @Override
    public void moveAside(@Nonnull Path file, @Nonnull String suffix) throws IOException {
        if (Files.exists(file)) {
            Files.move(file, file.resolveSibling(file.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(bak(file))) {
            Files.move(bak(file), file.resolveSibling(file.getFileName() + ".bak" + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path bak(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
