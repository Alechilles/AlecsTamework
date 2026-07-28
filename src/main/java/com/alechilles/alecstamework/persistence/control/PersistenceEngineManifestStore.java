package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Strict parser and atomic publisher for the one engine-lineage manifest. */
final class PersistenceEngineManifestStore {
    private final Path directory;
    private final Path manifestPath;

    PersistenceEngineManifestStore(Path directory) {
        this.directory = directory;
        this.manifestPath = directory.resolve(PersistenceFiles.ENGINE_MANIFEST);
    }

    Optional<PersistenceEngineManifest> read() throws Exception {
        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }
        String raw = Files.readString(manifestPath, StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            requireOnlyKnownFields(json);
            return Optional.of(new PersistenceEngineManifest(
                    requireInt(json, "formatVersion"),
                    PersistenceEngineLineage.parse(
                            requireString(json, "lineage")
                    ),
                    requireBoolean(json, "startupComplete"),
                    requireBoolean(json, "cleanShutdown"),
                    requireLong(json, "updatedAtMs")
            ));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "persistence_engine_manifest_invalid",
                    failure
            );
        }
    }

    void write(PersistenceEngineManifest manifest) throws Exception {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(
                directory,
                ".persistence-engine-",
                ".tmp"
        );
        try {
            Files.writeString(
                    temporary,
                    serialize(manifest),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(
                                 temporary,
                                 StandardOpenOption.WRITE
                         )) {
                channel.force(true);
            }
            moveAtomically(temporary, manifestPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String serialize(PersistenceEngineManifest manifest) {
        JsonObject json = new JsonObject();
        json.addProperty("formatVersion", manifest.formatVersion());
        json.addProperty("lineage", manifest.lineage().manifestValue());
        json.addProperty("startupComplete", manifest.startupComplete());
        json.addProperty("cleanShutdown", manifest.cleanShutdown());
        json.addProperty("updatedAtMs", manifest.updatedAtMs());
        return new com.google.gson.GsonBuilder()
                .disableHtmlEscaping()
                .setPrettyPrinting()
                .create()
                .toJson(json);
    }

    private void moveAtomically(Path source, Path target) throws Exception {
        Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    private void requireOnlyKnownFields(JsonObject json) {
        java.util.Set<String> expected = java.util.Set.of(
                "formatVersion",
                "lineage",
                "startupComplete",
                "cleanShutdown",
                "updatedAtMs"
        );
        if (!json.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    "Engine manifest fields do not match format"
            );
        }
    }

    private int requireInt(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return json.get(field).getAsBigDecimal().intValueExact();
    }

    private long requireLong(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isNumber()) {
            throw new IllegalArgumentException(field + " must be a long");
        }
        return json.get(field).getAsBigDecimal().longValueExact();
    }

    private String requireString(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return json.get(field).getAsString();
    }

    private boolean requireBoolean(JsonObject json, String field) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()
                || !json.getAsJsonPrimitive(field).isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return json.get(field).getAsBoolean();
    }
}
