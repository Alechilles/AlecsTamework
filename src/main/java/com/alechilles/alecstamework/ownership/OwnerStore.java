package com.alechilles.alecstamework.ownership;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.logger.HytaleLogger;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class OwnerStore {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

    private final Map<UUID, UUID> ownerByEntity = new ConcurrentHashMap<>();
    private final Path filePath;
    private final HytaleLogger logger;
    private final Gson gson = new Gson();

    public OwnerStore(Path dataDirectory, HytaleLogger logger) {
        this.logger = logger;
        Path dir = dataDirectory != null ? dataDirectory : Path.of(".");
        this.filePath = dir.resolve("tamework-owners.json");
        load();
    }

    public UUID getOwner(UUID entityUuid) {
        return entityUuid == null ? null : ownerByEntity.get(entityUuid);
    }

    public void setOwner(UUID entityUuid, UUID ownerUuid) {
        if (entityUuid == null) {
            return;
        }
        if (ownerUuid == null) {
            clearOwner(entityUuid);
            return;
        }
        ownerByEntity.put(entityUuid, ownerUuid);
        save();
    }

    public void clearOwner(UUID entityUuid) {
        if (entityUuid == null) {
            return;
        }
        if (ownerByEntity.remove(entityUuid) != null) {
            save();
        }
    }

    public boolean isOwner(UUID entityUuid, UUID playerUuid) {
        if (entityUuid == null || playerUuid == null) {
            return false;
        }
        UUID owner = ownerByEntity.get(entityUuid);
        return playerUuid.equals(owner);
    }

    public Map<UUID, UUID> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(ownerByEntity));
    }

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(filePath)) {
            Map<String, String> raw = gson.fromJson(reader, MAP_TYPE);
            if (raw == null) {
                return;
            }
            for (Map.Entry<String, String> entry : raw.entrySet()) {
                try {
                    UUID entityUuid = UUID.fromString(entry.getKey());
                    UUID ownerUuid = UUID.fromString(entry.getValue());
                    ownerByEntity.put(entityUuid, ownerUuid);
                } catch (IllegalArgumentException ignored) {
                    // Skip malformed entries
                }
            }
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("OwnerStore: failed to load owner data.");
            }
        }
    }

    private void save() {
        if (filePath == null) {
            return;
        }
        try {
            Files.createDirectories(filePath.getParent());
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("OwnerStore: failed to create data directory.");
            }
            return;
        }
        Map<String, String> raw = new HashMap<>();
        for (Map.Entry<UUID, UUID> entry : ownerByEntity.entrySet()) {
            raw.put(entry.getKey().toString(), entry.getValue().toString());
        }
        try (Writer writer = Files.newBufferedWriter(filePath)) {
            gson.toJson(raw, writer);
        } catch (IOException ex) {
            if (logger != null) {
                logger.at(Level.WARNING).withCause(ex).log("OwnerStore: failed to save owner data.");
            }
        }
    }
}
