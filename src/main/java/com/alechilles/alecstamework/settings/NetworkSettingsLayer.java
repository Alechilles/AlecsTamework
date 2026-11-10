package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The network's settings as this server last read them: the layer between a server's own settings
 * files and the built-in defaults. Each settings store reads its file through {@link #effective} and
 * writes it through {@link #localForm}, so while a network layer is active a local file holds only
 * the settings this server overrides.
 *
 * <p>The layer is kept in {@code Settings/tamework-network-settings.json}, so a server starts with
 * the last known network settings even before, or without, a reachable store. It is read from that
 * file on first use and replaced by {@link NetworkSettingsSync}. Reads are lock-free.</p>
 */
public final class NetworkSettingsLayer {
    public static final String CACHE_FILE_NAME = "tamework-network-settings.json";

    /** Section keys of the server document, one per settings file. */
    public static final String SECTION_MAIN = "tamework-settings";
    public static final String SECTION_COMPANION_CARD = "companion-card";
    public static final String SECTION_COMPANION_HUD = "companion-hud";
    public static final String SECTION_DEPLOY_PLACES = "deploy-places";
    public static final String SECTION_NAME_FILTER = "name-filter";
    public static final String SECTION_MOD_SETTINGS = "mod-settings";

    /** Writes the nulls that unset a network value on this server. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls().create();

    @Nullable
    private static volatile State state;

    private NetworkSettingsLayer() {
    }

    /**
     * @param revision the store revision of {@code sections}; 0 when none was read
     * @param left     an admin chose to stop following the network on this server
     * @param sections the network's server document by section, or null when this server has none
     */
    public record State(long revision, boolean left, @Nullable JsonObject sections) {
        public static final State NONE = new State(0L, false, null);

        /** Whether network values apply on this server. */
        public boolean active() {
            return sections != null && !left;
        }
    }

    /** The current state. Reads the cache file the first time, so do not make the first call on a world thread. */
    @Nonnull
    public static State state() {
        State current = state;
        if (current != null) {
            return current;
        }
        Path file = cacheFileOrNull();
        if (file == null) {
            // No plugin yet (or a unit test): nothing to read, and nothing cached so a later call can.
            return State.NONE;
        }
        synchronized (NetworkSettingsLayer.class) {
            current = state;
            if (current == null) {
                current = read(file, pluginLogger());
                state = current;
            }
            return current;
        }
    }

    public static boolean active() {
        return state().active();
    }

    /** The network's values for one section, or null when there are none. */
    @Nullable
    public static JsonObject section(@Nonnull String section) {
        State current = state();
        if (!current.active()) {
            return null;
        }
        JsonElement value = current.sections().get(section);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /**
     * The document a store should parse: the local file's text over the network's section. Returns
     * {@code localRaw} unchanged without an active layer, or when it is not a JSON object so the
     * store reports the broken file as it always did.
     */
    @Nullable
    public static String effective(@Nonnull String section, @Nullable String localRaw) {
        JsonObject network = section(section);
        if (network == null) {
            return localRaw;
        }
        JsonObject local = null;
        if (localRaw != null && !localRaw.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(localRaw);
                if (!parsed.isJsonObject()) {
                    return localRaw;
                }
                local = parsed.getAsJsonObject();
            } catch (RuntimeException invalid) {
                return localRaw;
            }
        }
        return GSON.toJson(JsonLayers.merge(network, local));
    }

    /**
     * The text a store should write to its local file for the full document {@code fullJson}: the
     * overrides against the network's section, or {@code fullJson} unchanged without an active layer.
     */
    @Nonnull
    public static String localForm(@Nonnull String section, @Nonnull String fullJson) {
        JsonObject network = section(section);
        if (network == null) {
            return fullJson;
        }
        return GSON.toJson(JsonLayers.overrides(JsonParser.parseString(fullJson).getAsJsonObject(), network));
    }

    /** Pretty JSON that keeps nulls, for local override files and the cache file. */
    @Nonnull
    static String toJson(@Nonnull JsonObject object) {
        return GSON.toJson(object);
    }

    /** Replaces the state and, with a file, saves it. Blocks on file I/O when a file is given. */
    static void install(@Nonnull State next, @Nullable Path cacheFile, @Nullable HytaleLogger logger) {
        state = next;
        if (cacheFile == null) {
            return;
        }
        JsonObject document = new JsonObject();
        document.addProperty("revision", next.revision());
        document.addProperty("left", next.left());
        if (next.sections() != null) {
            document.add("server", next.sections());
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(document), StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(failure).log(
                        "Could not save the network settings to %s; they apply until this server restarts.", cacheFile);
            }
        }
    }

    /** Sets the state without touching any file. For tests. */
    public static void useForTests(@Nullable State next) {
        state = next;
    }

    @Nonnull
    static State read(@Nonnull Path file, @Nullable HytaleLogger logger) {
        if (!Files.isRegularFile(file)) {
            return State.NONE;
        }
        try {
            JsonObject document = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement server = document.get("server");
            JsonElement revision = document.get("revision");
            JsonElement left = document.get("left");
            return new State(revision == null ? 0L : revision.getAsLong(),
                    left != null && left.getAsBoolean(),
                    server != null && server.isJsonObject() ? server.getAsJsonObject() : null);
        } catch (Exception failure) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(failure).log(
                        "Could not read the saved network settings from %s; this server uses its own settings until"
                                + " the network's are read again.", file);
            }
            return State.NONE;
        }
    }

    @Nullable
    private static Path cacheFileOrNull() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null ? null
                    : TameworkSettingsStore.resolveSettingsDirectory(plugin).resolve(CACHE_FILE_NAME).normalize();
        } catch (Throwable unavailable) {
            return null;
        }
    }

    @Nullable
    private static HytaleLogger pluginLogger() {
        try {
            Tamework plugin = Tamework.getInstance();
            return plugin == null ? null : plugin.getLogger();
        } catch (Throwable unavailable) {
            return null;
        }
    }
}
