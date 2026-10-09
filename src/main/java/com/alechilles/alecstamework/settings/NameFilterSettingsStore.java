package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * JSON-backed blocked-name filter settings, stored next to the `/tw settings` file.
 *
 * <p>The word lists live in their own file because server owners edit them by hand, and
 * lists do not fit the scalar values of {@link ResolvedTameworkSettings}.
 */
public final class NameFilterSettingsStore {
    public static final String FILE_NAME = "tamework-name-filter.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @Nullable
    private static volatile Cached cached;

    private NameFilterSettingsStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Tamework plugin) {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(FILE_NAME).normalize();
    }

    /**
     * The settings in effect. Cached until {@link #save} or {@link #invalidateCache}; the
     * defaults apply before the plugin is available.
     */
    @Nonnull
    public static NameFilterSettings current() {
        final Tamework plugin;
        try {
            plugin = Tamework.getInstance();
        } catch (Throwable ignored) {
            return NameFilterSettings.DEFAULT;
        }
        if (plugin == null) {
            return NameFilterSettings.DEFAULT;
        }
        Path file = resolveFile(plugin);
        Cached current = cached;
        if (current != null && current.path().equals(file)) {
            return current.settings();
        }
        synchronized (NameFilterSettingsStore.class) {
            current = cached;
            if (current != null && current.path().equals(file)) {
                return current.settings();
            }
            NameFilterSettings loaded = load(file, plugin.getLogger());
            cached = new Cached(file, loaded);
            return loaded;
        }
    }

    /** Drops the cached settings so hand edits to the file apply on the next read. */
    public static void invalidateCache() {
        cached = null;
    }

    /** Reads the file, writing the default template first when it does not exist yet. */
    @Nonnull
    public static NameFilterSettings load(@Nonnull Path file, @Nullable HytaleLogger logger) {
        if (!Files.isRegularFile(file)) {
            write(file, NameFilterSettings.DEFAULT, logger);
            return NameFilterSettings.DEFAULT;
        }
        try {
            Document document = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Document.class);
            if (document == null) {
                return NameFilterSettings.DEFAULT;
            }
            return new NameFilterSettings(
                    document.enabled == null || document.enabled,
                    document.useStockList == null || document.useStockList,
                    document.blockedWords == null ? List.of() : document.blockedWords,
                    document.allowedWords == null ? List.of() : document.allowedWords
            );
        } catch (IOException | JsonParseException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to read Tamework name filter settings from %s; using defaults.", file);
            }
            return NameFilterSettings.DEFAULT;
        }
    }

    public static synchronized boolean save(@Nonnull Path file, @Nonnull NameFilterSettings settings,
                                            @Nullable HytaleLogger logger) {
        if (!write(file, settings, logger)) {
            return false;
        }
        cached = new Cached(file, settings);
        return true;
    }

    private static boolean write(@Nonnull Path file, @Nonnull NameFilterSettings settings, @Nullable HytaleLogger logger) {
        Document document = new Document();
        document.enabled = settings.enabled();
        document.useStockList = settings.useStockList();
        document.blockedWords = settings.blockedWords();
        document.allowedWords = settings.allowedWords();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(document), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to write Tamework name filter settings to %s.", file);
            }
            return false;
        }
    }

    private record Cached(@Nonnull Path path, @Nonnull NameFilterSettings settings) {
    }

    private static final class Document {
        private Boolean enabled;
        private Boolean useStockList;
        private List<String> blockedWords;
        private List<String> allowedWords;
    }
}
