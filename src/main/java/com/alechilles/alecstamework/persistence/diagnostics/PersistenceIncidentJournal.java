package com.alechilles.alecstamework.persistence.diagnostics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Bounded append-only JSONL journal that remains available when SQLite or telemetry fails. */
public final class PersistenceIncidentJournal implements PersistenceIncidentSink, AutoCloseable {
    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024L * 1024L;
    public static final int DEFAULT_MAX_FILES = 5;
    public static final int DEFAULT_RETENTION_DAYS = 7;
    private static final long WARNING_THROTTLE_MS = 60_000L;

    private final Path directory;
    private final String bootId;
    private final HytaleLogger logger;
    private final Clock clock;
    private final long maxFileBytes;
    private final int maxFiles;
    private final int retentionDays;
    private final ArrayBlockingQueue<PersistenceIncidentEvent> queue;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong lastWarningAtMs = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Thread worker;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public PersistenceIncidentJournal(@Nonnull Path directory,
                                      @Nonnull String bootId,
                                      @Nullable HytaleLogger logger) {
        this(directory, bootId, logger, Clock.systemUTC(), DEFAULT_MAX_FILE_BYTES,
                DEFAULT_MAX_FILES, DEFAULT_RETENTION_DAYS, 1_024);
    }

    PersistenceIncidentJournal(Path directory, String bootId, HytaleLogger logger, Clock clock,
                               long maxFileBytes, int maxFiles, int retentionDays, int queueCapacity) {
        this.directory = directory.toAbsolutePath().normalize();
        this.bootId = safeFileToken(bootId);
        this.logger = logger;
        this.clock = clock;
        this.maxFileBytes = Math.max(128L, maxFileBytes);
        this.maxFiles = Math.max(1, maxFiles);
        this.retentionDays = Math.max(1, retentionDays);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.worker = new Thread(this::run, "tamework-persistence-diagnostics");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void record(@Nonnull PersistenceIncidentEvent event) {
        if (closing.get() || !queue.offer(event)) droppedRecords.incrementAndGet();
    }

    public long droppedRecords() {
        return droppedRecords.get();
    }

    @Nonnull
    public Path directory() {
        return directory;
    }

    private void run() {
        while (!closing.get() || !queue.isEmpty()) {
            try {
                PersistenceIncidentEvent event = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (event != null) append(event);
            } catch (InterruptedException interrupted) {
                if (!closing.get()) Thread.currentThread().interrupt();
            } catch (Exception failure) {
                droppedRecords.incrementAndGet();
                warnThrottled(failure);
            }
        }
    }

    private void append(PersistenceIncidentEvent event) throws Exception {
        Files.createDirectories(directory);
        Path file = selectFile();
        try (BufferedWriter writer = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(gson.toJson(event));
            writer.newLine();
            writer.flush();
        }
        enforceRetention();
    }

    private Path selectFile() throws Exception {
        String date = LocalDate.now(clock).toString();
        String stem = "incidents-" + bootId + "-" + date;
        Path candidate = directory.resolve(stem + ".jsonl");
        int part = 1;
        while (Files.exists(candidate) && Files.size(candidate) >= maxFileBytes) {
            candidate = directory.resolve(stem + "-" + part + ".jsonl");
            part++;
        }
        return candidate;
    }

    private void enforceRetention() throws Exception {
        Instant cutoff = clock.instant().minusSeconds(retentionDays * 86_400L);
        List<Path> files = journalFiles();
        for (Path file : files) {
            if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
        }
        files = journalFiles();
        for (int index = maxFiles; index < files.size(); index++) Files.deleteIfExists(files.get(index));
    }

    private List<Path> journalFiles() throws Exception {
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> path.getFileName().toString().startsWith("incidents-"))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparingLong(this::lastModifiedSafe).reversed());
        return files;
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private void warnThrottled(Exception failure) {
        long now = clock.millis();
        long previous = lastWarningAtMs.get();
        if (now - previous < WARNING_THROTTLE_MS || !lastWarningAtMs.compareAndSet(previous, now)) return;
        if (logger != null) {
            logger.at(Level.WARNING).log(
                    "Persistence diagnostics journal write failed; canonical persistence is unaffected: "
                            + failure.getClass().getSimpleName());
        }
    }

    private static String safeFileToken(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9-]", "-");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    @Override
    public void close() {
        closing.set(true);
        worker.interrupt();
        try {
            worker.join(1_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!queue.isEmpty()) droppedRecords.addAndGet(queue.size());
    }
}
