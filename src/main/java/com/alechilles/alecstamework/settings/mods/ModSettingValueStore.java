package com.alechilles.alecstamework.settings.mods;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Saves the values admins chose for mod settings in one server-wide JSON file. Only values that
 * were explicitly set are stored, so a mod update that changes a default reaches every server that
 * never touched the setting. Values for mods that are not installed are kept.
 */
final class ModSettingValueStore {
    static final String DIRECTORY_NAME = "ModSettings";
    static final String FILE_NAME = "values.json";
    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path file;
    private final HytaleLogger logger;

    ModSettingValueStore(@Nonnull Path pluginDataDirectory, @Nonnull HytaleLogger logger) {
        this.file = pluginDataDirectory.resolve(DIRECTORY_NAME).resolve(FILE_NAME).toAbsolutePath().normalize();
        this.logger = logger;
    }

    @Nonnull
    Path file() {
        return file;
    }

    /** Reads saved values. A missing file is empty; an unreadable file is moved aside and treated as empty. */
    @Nonnull
    Map<String, JsonPrimitive> load() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonElement values = root.isJsonObject() ? root.getAsJsonObject().get("Values") : null;
            if (values == null || !values.isJsonObject()) {
                throw new JsonParseException("Missing Values object");
            }
            Map<String, JsonPrimitive> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : values.getAsJsonObject().entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    result.put(entry.getKey(), entry.getValue().getAsJsonPrimitive());
                }
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            Path backup = file.resolveSibling(FILE_NAME + ".broken-" + System.currentTimeMillis());
            logger.at(Level.WARNING).withCause(failure).log(
                    "Mod settings file '%s' could not be read; moving it to '%s' and using defaults.", file, backup);
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                logger.at(Level.WARNING).withCause(moveFailure).log("Could not move unreadable mod settings file '%s'.", file);
            }
            return Map.of();
        }
    }

    /** Writes all saved values atomically. */
    void save(@Nonnull Map<String, JsonPrimitive> values) throws IOException {
        JsonObject valuesJson = new JsonObject();
        values.forEach(valuesJson::add);
        JsonObject root = new JsonObject();
        root.addProperty("Version", VERSION);
        root.add("Values", valuesJson);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
