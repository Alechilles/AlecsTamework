package com.alechilles.alecstamework.companion.store;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.StorageManager;
import com.hypixel.hytale.server.core.util.BsonUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Companion file access through Hytale's {@link StorageManager}, so saves are
 * serialized per path and held back while a Hytale backup runs.
 */
public final class HytaleCompanionFileIo implements CompanionFileIo {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Files already reported as loaded from backup, so each is logged once per process. */
    private static final Set<Path> WARNED_BACKUPS = ConcurrentHashMap.newKeySet();

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

    /**
     * Every check and read runs inside {@code doLoad}, so a pending save or delete of the same
     * path finishes first: a read racing a delete returns {@code null}, never "unreadable". Any
     * failure other than a parse failure, including a failed pending save of the same path, is
     * reported as {@link CompanionFileAccessException}. Logs one WARN per file per process when
     * the {@code .bak} copy is used.
     */
    @Override
    @Nullable
    public BsonDocument readNow(@Nonnull Path file) throws IOException {
        ReadOutcome outcome;
        try {
            outcome = storage.get()
                    .doLoad(file, () -> CompletableFuture.supplyAsync(() -> readWithBackup(file)))
                    .join();
        } catch (CompletionException | IllegalStateException e) {
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof UncheckedIOException unchecked && unchecked.getCause() instanceof CompanionFileAccessException access) {
                throw access;
            }
            throw new CompanionFileAccessException("could not read companion file " + file, cause);
        }
        if (outcome.unreadable()) {
            throw new IOException("unreadable companion file " + file + " (no copy parses)");
        }
        return outcome.document();
    }

    /** Result of reading a file and its backup: a document, nothing, or unparseable. */
    private record ReadOutcome(@Nullable BsonDocument document, boolean unreadable) {
    }

    private static ReadOutcome readWithBackup(Path file) {
        String main = readIfPresent(file);
        if (main != null) {
            BsonDocument document = parseOrNull(main);
            if (document != null) {
                return new ReadOutcome(document, false);
            }
        }
        String backup = readIfPresent(bak(file));
        if (backup != null) {
            BsonDocument document = parseOrNull(backup);
            if (document != null) {
                if (WARNED_BACKUPS.add(file)) {
                    LOGGER.at(Level.WARNING).log("Companion file %s was %s; loaded its backup copy instead",
                            file, main == null ? "missing" : "unreadable");
                }
                return new ReadOutcome(document, false);
            }
        }
        return new ReadOutcome(null, main != null || backup != null);
    }

    /** File contents, or {@code null} when the file does not exist. */
    @Nullable
    private static String readIfPresent(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(new CompanionFileAccessException("could not read companion file " + file, e));
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Nullable
    private static BsonDocument parseOrNull(String json) {
        if (json.isBlank()) {
            return null;
        }
        try {
            return BsonDocument.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
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
