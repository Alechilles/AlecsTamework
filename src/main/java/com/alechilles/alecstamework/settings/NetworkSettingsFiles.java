package com.alechilles.alecstamework.settings;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This server's settings files as sections of the network's server document: what they hold in
 * full, and the rewrites a server makes when it joins a network, takes the network's settings or
 * leaves. Every method blocks on file I/O and belongs on the network settings thread.
 */
public final class NetworkSettingsFiles {
    /**
     * @param key       the section's key in the server document
     * @param file      the local settings file, which may not exist
     * @param normalize the full document for a file's text (null for no file): every setting with
     *                  its resolved value. Throws a runtime exception for text that is not a document.
     */
    public record Section(@Nonnull String key, @Nonnull Path file, @Nonnull UnaryOperator<String> normalize) {
    }

    private final List<Section> sections;
    @Nullable
    private final HytaleLogger logger;

    public NetworkSettingsFiles(@Nonnull List<Section> sections, @Nullable HytaleLogger logger) {
        this.sections = List.copyOf(sections);
        this.logger = logger;
    }

    /** The settings in effect on this server, in full, by section: what a publish sends. */
    @Nonnull
    public JsonObject effectiveSections() {
        JsonObject result = new JsonObject();
        for (Section section : sections) {
            JsonObject full = fullOrNull(section, NetworkSettingsLayer.effective(section.key(), readLocal(section)));
            if (full != null) {
                result.add(section.key(), full);
            }
        }
        return result;
    }

    /**
     * Rewrites each existing local file to the overrides this server keeps on joining a network
     * whose server document is {@code network}: the values that differ from both the network's and
     * the built-in default. Call before the network layer becomes active.
     */
    public void convertToOverrides(@Nonnull JsonObject network) {
        Objects.requireNonNull(network, "network");
        for (Section section : sections) {
            String local = readLocal(section);
            if (local == null) {
                continue;
            }
            JsonObject before = fullOrNull(section, local);
            JsonObject defaults = fullOrNull(section, null);
            if (before == null || defaults == null) {
                continue;
            }
            JsonElement networkValue = network.get(section.key());
            JsonObject networkSection = networkValue != null && networkValue.isJsonObject()
                    ? networkValue.getAsJsonObject() : null;
            JsonObject kept = JsonLayers.chosenOverrides(before, networkSection, defaults);
            if (!write(section, NetworkSettingsLayer.toJson(kept))) {
                continue;
            }
            JsonObject after = fullOrNull(section, NetworkSettingsLayer.toJson(JsonLayers.merge(networkSection, kept)));
            if (after != null && logger != null) {
                List<String> changes = new ArrayList<>();
                describeChanges("", before, after, changes);
                for (String change : changes) {
                    logger.at(Level.INFO).log("Network settings: %s %s", section.file().getFileName(), change);
                }
            }
        }
    }

    /** Writes every setting in effect into the local files in full, for a server that leaves the network. */
    public void flatten() {
        for (Section section : sections) {
            JsonObject full = fullOrNull(section, NetworkSettingsLayer.effective(section.key(), readLocal(section)));
            if (full != null) {
                write(section, NetworkSettingsLayer.toJson(full));
            }
        }
    }

    /** Deletes the local files, so this server follows the network for every setting. */
    public void clearOverrides() {
        for (Section section : sections) {
            try {
                Files.deleteIfExists(section.file());
            } catch (IOException failure) {
                warn(failure, "Could not delete %s; its settings still override the network's.", section.file());
            }
        }
    }

    /** How many settings this server's files set. With network settings active, that is its overrides. */
    public int localSettingCount() {
        int count = 0;
        for (Section section : sections) {
            String local = readLocal(section);
            if (local == null || local.isBlank()) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(local);
                count += parsed.isJsonObject() ? JsonLayers.count(parsed.getAsJsonObject()) : 0;
            } catch (RuntimeException invalid) {
                // A broken file is reported by its own store.
            }
        }
        return count;
    }

    @Nullable
    private JsonObject fullOrNull(@Nonnull Section section, @Nullable String raw) {
        try {
            return JsonParser.parseString(section.normalize().apply(raw)).getAsJsonObject();
        } catch (RuntimeException invalid) {
            warn(invalid, "%s is not a valid settings file; it is left as it is.", section.file());
            return null;
        }
    }

    @Nullable
    private String readLocal(@Nonnull Section section) {
        try {
            return Files.isRegularFile(section.file()) ? Files.readString(section.file(), StandardCharsets.UTF_8) : null;
        } catch (IOException failure) {
            warn(failure, "Could not read %s.", section.file());
            return null;
        }
    }

    private boolean write(@Nonnull Section section, @Nonnull String json) {
        try {
            Path file = section.file();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            warn(failure, "Could not write %s.", section.file());
            return false;
        }
    }

    private void warn(@Nonnull Throwable cause, @Nonnull String message, @Nonnull Object argument) {
        if (logger != null) {
            logger.at(Level.WARNING).withCause(cause).log(message, argument);
        }
    }

    private static void describeChanges(@Nonnull String path, @Nonnull JsonObject before, @Nonnull JsonObject after,
                                        @Nonnull List<String> out) {
        for (Map.Entry<String, JsonElement> entry : after.entrySet()) {
            String name = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            JsonElement old = before.get(entry.getKey());
            JsonElement now = entry.getValue();
            if (now.isJsonObject() && old != null && old.isJsonObject()) {
                describeChanges(name, old.getAsJsonObject(), now.getAsJsonObject(), out);
            } else if (!now.equals(old)) {
                out.add(name + " changed from " + old + " to " + now + " (the network's value).");
            }
        }
    }
}
