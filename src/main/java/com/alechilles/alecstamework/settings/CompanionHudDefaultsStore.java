package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.ui.CompanionHudPlacement;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The server's default companion HUD position, stored next to the `/tw settings` file. Players who have not moved
 * the HUD themselves use it. Admins set it from `/tw settings`, or edit the file and run the config reload.
 */
public final class CompanionHudDefaultsStore {
    public static final String FILE_NAME = "tamework-companion-hud.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    @Nullable
    private static volatile Cached cached;

    private CompanionHudDefaultsStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Tamework plugin) {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(FILE_NAME).normalize();
    }

    /**
     * The default in effect. Cached until {@link #save} or {@link #invalidateCache}; the built-in default applies
     * before the plugin is available.
     */
    @Nonnull
    public static CompanionHudPlacement current() {
        Tamework plugin = Tamework.getInstance();
        if (plugin == null) {
            return CompanionHudPlacement.DEFAULT;
        }
        Path file = resolveFile(plugin);
        Cached current = cached;
        if (current != null && current.path().equals(file)) {
            return current.placement();
        }
        synchronized (CompanionHudDefaultsStore.class) {
            current = cached;
            if (current != null && current.path().equals(file)) {
                return current.placement();
            }
            CompanionHudPlacement loaded = load(file, plugin.getLogger());
            cached = new Cached(file, loaded);
            return loaded;
        }
    }

    /** Drops the cached default so hand edits to the file apply on the next read. */
    public static void invalidateCache() {
        cached = null;
    }

    /** Reads the file; a missing file means the built-in default and is not created until an admin saves. */
    @Nonnull
    static CompanionHudPlacement load(@Nonnull Path file, @Nullable HytaleLogger logger) {
        try {
            String raw = NetworkSettingsLayer.effective(NetworkSettingsLayer.SECTION_COMPANION_HUD,
                    Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null);
            if (raw == null) {
                return CompanionHudPlacement.DEFAULT;
            }
            CompanionHudPlacement parsed = parseOrNull(raw);
            if (parsed == null) {
                if (logger != null) {
                    logger.at(Level.WARNING).log(
                            "Tamework companion HUD default in %s has no valid point; using the built-in default.", file);
                }
                return CompanionHudPlacement.DEFAULT;
            }
            return parsed;
        } catch (IOException | JsonParseException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to read the Tamework companion HUD default from %s; using the built-in default.", file);
            }
            return CompanionHudPlacement.DEFAULT;
        }
    }

    /** The placement a document holds, or null when it has no valid point. */
    @Nullable
    private static CompanionHudPlacement parseOrNull(@Nullable String raw) {
        Document document = raw == null || raw.isBlank() ? null : GSON.fromJson(raw, Document.class);
        CompanionHudPlacement.Point point = document == null ? null : CompanionHudPlacement.Point.parse(document.point);
        return point == null ? null : new CompanionHudPlacement(point,
                document.x == null ? 0 : document.x,
                document.y == null ? 0 : document.y);
    }

    /**
     * The placement a document holds; a null, blank or pointless document gives the built-in default.
     *
     * @throws JsonParseException when {@code raw} is not JSON
     */
    @Nonnull
    public static CompanionHudPlacement parse(@Nullable String raw) {
        CompanionHudPlacement parsed = parseOrNull(raw);
        return parsed == null ? CompanionHudPlacement.DEFAULT : parsed;
    }

    /** The full document for a placement. */
    @Nonnull
    public static String toJson(@Nonnull CompanionHudPlacement placement) {
        Document document = new Document();
        document.point = placement.point().name();
        document.x = placement.x();
        document.y = placement.y();
        return GSON.toJson(document);
    }

    public static synchronized boolean save(@Nonnull Path file, @Nonnull CompanionHudPlacement placement,
                                            @Nullable HytaleLogger logger) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, NetworkSettingsLayer.localForm(NetworkSettingsLayer.SECTION_COMPANION_HUD,
                    toJson(placement)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to write the Tamework companion HUD default to %s.", file);
            }
            return false;
        }
        cached = new Cached(file, placement);
        return true;
    }

    private record Cached(@Nonnull Path path, @Nonnull CompanionHudPlacement placement) {
    }

    /** Point is a snap point name such as TOP_RIGHT; X and Y are the offset from it in HUD canvas units. */
    private static final class Document {
        private String point;
        private Integer x;
        private Integer y;
    }
}
