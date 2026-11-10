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
 * JSON-backed {@link DeployPlaces}, stored next to the `/tw settings` file. A list of places does
 * not fit the scalar values of {@link ResolvedTameworkSettings}, and server owners may edit it by
 * hand or share one file across a network.
 *
 * <p>The admission gate reads {@link #cachedOrDefault()} under the index lock, so the file is read
 * only by {@link #load} at startup (a hand edit applies at the next start),
 * never on the gate's path.</p>
 */
public final class DeployPlacesStore {
    public static final String FILE_NAME = "tamework-deploy-places.json";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private static volatile DeployPlaces cached = DeployPlaces.DEFAULT;

    private DeployPlacesStore() {
    }

    @Nonnull
    public static Path resolveFile(@Nonnull Tamework plugin) {
        return TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(FILE_NAME).normalize();
    }

    /** The places in effect; the defaults until {@link #load} or {@link #save} ran. Never reads the file. */
    @Nonnull
    public static DeployPlaces cachedOrDefault() {
        return cached;
    }

    /**
     * Reads the file into the cache, writing the default template first when it does not exist yet.
     * Blocks on file I/O; never call it on a world thread.
     */
    @Nonnull
    public static DeployPlaces load(@Nonnull Path file, @Nullable HytaleLogger logger) {
        DeployPlaces loaded = read(file, logger);
        cached = loaded;
        return loaded;
    }

    @Nonnull
    private static DeployPlaces read(@Nonnull Path file, @Nullable HytaleLogger logger) {
        // With network settings a missing file means "no override"; a template of defaults would be one.
        if (!Files.isRegularFile(file) && !NetworkSettingsLayer.active()) {
            write(file, DeployPlaces.DEFAULT, logger);
            return DeployPlaces.DEFAULT;
        }
        try {
            return parse(NetworkSettingsLayer.effective(NetworkSettingsLayer.SECTION_DEPLOY_PLACES,
                    Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null));
        } catch (IOException | JsonParseException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to read where companions may come out from %s; companions may come out"
                                + " everywhere until it is fixed.", file);
            }
            return DeployPlaces.DEFAULT;
        }
    }

    public static synchronized boolean save(@Nonnull Path file, @Nonnull DeployPlaces places,
                                            @Nullable HytaleLogger logger) {
        if (!write(file, places, logger)) {
            return false;
        }
        cached = places;
        return true;
    }

    /**
     * The places a document holds; a null or blank document gives the defaults.
     *
     * @throws JsonParseException when {@code raw} is not a places document
     */
    @Nonnull
    public static DeployPlaces parse(@Nullable String raw) {
        Document document = raw == null || raw.isBlank() ? null : GSON.fromJson(raw, Document.class);
        return document == null ? DeployPlaces.DEFAULT
                : new DeployPlaces(DeployPlaces.Mode.fromConfigValue(document.mode),
                        document.places == null ? List.of() : document.places);
    }

    /** The full document for these places. */
    @Nonnull
    public static String toJson(@Nonnull DeployPlaces places) {
        Document document = new Document();
        document.mode = places.mode().configValue();
        document.places = places.places();
        return GSON.toJson(document);
    }

    private static boolean write(@Nonnull Path file, @Nonnull DeployPlaces places, @Nullable HytaleLogger logger) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, NetworkSettingsLayer.localForm(NetworkSettingsLayer.SECTION_DEPLOY_PLACES,
                    toJson(places)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex)
                        .log("Failed to write where companions may come out to %s.", file);
            }
            return false;
        }
    }

    private static final class Document {
        private String mode;
        private List<String> places;
    }
}
