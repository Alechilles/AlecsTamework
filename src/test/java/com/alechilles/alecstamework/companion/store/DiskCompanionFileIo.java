package com.alechilles.alecstamework.companion.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.bson.BsonDocument;

/**
 * Companion file access on real files, for tests of code that also renames or deletes store
 * folders (the importer). Writes plain JSON with no {@code .bak} copy. Public so other companion
 * packages' tests can use it.
 */
public final class DiskCompanionFileIo implements CompanionFileIo {
    @Override
    public CompletableFuture<Void> write(Path file, BsonDocument document) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, document.toJson(), StandardCharsets.UTF_8);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public BsonDocument readNow(Path file) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return BsonDocument.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("unreadable companion file " + file, e);
        }
    }

    @Override
    public CompletableFuture<Void> delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public List<Path> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    @Override
    public void moveAside(Path file, String suffix) throws IOException {
        Files.move(file, file.resolveSibling(file.getFileName() + suffix));
    }
}
