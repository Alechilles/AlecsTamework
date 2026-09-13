package com.alechilles.alecstamework.items.locate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Disk cache for advisory captured-item sightings. It is never gameplay authority. */
public final class CapturedItemLocationCache implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private final Path file;
    private final CapturedItemLocationIndex index;

    public CapturedItemLocationCache(
            Path file,
            CapturedItemLocationIndex index
    ) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath()
                .normalize();
        this.index = Objects.requireNonNull(index, "index");
    }

    /** Loads persisted sightings as stale data; absent caches leave the index unchanged. */
    public void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Captured-item location cache exceeds 8 MiB");
        }
        byte[] raw = Files.readAllBytes(file);
        if (raw.length > MAX_FILE_BYTES) {
            throw new IOException("Captured-item location cache exceeds 8 MiB");
        }
        try {
            index.restore(read(new String(raw, StandardCharsets.UTF_8)));
        } catch (RuntimeException failure) {
            throw new IOException("Captured-item location cache is invalid", failure);
        }
    }

    /** Writes a complete advisory snapshot through a sibling temporary file. */
    public void save() throws IOException {
        String encoded = JSON.toJson(write(index.snapshot()));
        byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Captured-item location cache exceeds 8 MiB");
        }

        Path directory = file.getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(
                directory, ".captured-item-locations-", ".tmp"
        );
        try {
            Files.write(temporary, bytes, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        // The runtime owns scheduling and lifecycle; this cache owns no resources.
    }

    private JsonObject write(List<CapturedItemLocationIndex.Sighting> sightings) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray values = new JsonArray();
        for (CapturedItemLocationIndex.Sighting sighting : sightings) {
            JsonObject value = new JsonObject();
            value.add("capture", write(sighting.capture()));
            value.add("holder", write(sighting.holder()));
            value.addProperty("observedAtMs", sighting.observedAtMs());
            value.addProperty("loaded", sighting.loaded());
            nullable(value, "itemId", sighting.itemId());
            values.add(value);
        }
        root.add("sightings", values);
        return root;
    }

    private JsonObject write(CapturedItemLocationIndex.CaptureKey capture) {
        JsonObject value = new JsonObject();
        nullable(value, "profileId", capture.profileId());
        nullable(value, "snapshotId", capture.snapshotId());
        value.addProperty("npcUuid", capture.npcUuid().toString());
        return value;
    }

    private JsonObject write(CapturedItemLocationIndex.Holder holder) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", holder.kind().name());
        value.addProperty("worldName", holder.worldName());
        value.addProperty("id", holder.id());
        nullable(value, "name", holder.name());
        value.addProperty("x", holder.x());
        value.addProperty("y", holder.y());
        value.addProperty("z", holder.z());
        return value;
    }

    private void nullable(JsonObject target, String name, String value) {
        target.add(name, value == null ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(value));
    }

    private Collection<CapturedItemLocationIndex.Sighting> read(String raw) {
        JsonElement element = JsonParser.parseString(raw);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Cache root must be an object");
        }
        JsonObject root = element.getAsJsonObject();
        requireFields(root, Set.of("version", "sightings"));
        if (integer(root, "version") != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported cache version");
        }
        JsonElement sightings = root.get("sightings");
        if (!sightings.isJsonArray()) {
            throw new IllegalArgumentException("sightings must be an array");
        }
        List<CapturedItemLocationIndex.Sighting> result = new ArrayList<>();
        for (JsonElement item : sightings.getAsJsonArray()) {
            result.add(readSighting(object(item, "sighting")));
        }
        return result;
    }

    private CapturedItemLocationIndex.Sighting readSighting(JsonObject value) {
        // Version 1 caches written before item labels remain readable.
        requireFields(value, value.has("itemId")
                ? Set.of("capture", "holder", "observedAtMs", "loaded", "itemId")
                : Set.of("capture", "holder", "observedAtMs", "loaded"));
        return new CapturedItemLocationIndex.Sighting(
                readCapture(object(value.get("capture"), "capture")),
                readHolder(object(value.get("holder"), "holder")),
                number(value, "observedAtMs"), booleanValue(value, "loaded"),
                value.has("itemId") ? nullableString(value, "itemId") : null
        );
    }

    private CapturedItemLocationIndex.CaptureKey readCapture(JsonObject value) {
        requireFields(value, Set.of("profileId", "snapshotId", "npcUuid"));
        return new CapturedItemLocationIndex.CaptureKey(
                nullableString(value, "profileId"),
                nullableString(value, "snapshotId"),
                UUID.fromString(string(value, "npcUuid"))
        );
    }

    private CapturedItemLocationIndex.Holder readHolder(JsonObject value) {
        requireFields(value, Set.of("kind", "worldName", "id", "name", "x", "y", "z"));
        return new CapturedItemLocationIndex.Holder(
                CapturedItemLocationIndex.Kind.valueOf(string(value, "kind")),
                string(value, "worldName"), string(value, "id"),
                nullableString(value, "name"), decimal(value, "x"),
                decimal(value, "y"), decimal(value, "z")
        );
    }

    private JsonObject object(JsonElement value, String field) {
        if (value == null || !value.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private void requireFields(JsonObject value, Set<String> expected) {
        if (!value.keySet().equals(expected)) {
            throw new IllegalArgumentException("Cache fields do not match format");
        }
    }

    private String nullableString(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return element.isJsonNull() ? null : string(value, field);
    }

    private String string(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }

    private int integer(JsonObject value, String field) {
        try {
            return decimalValue(value, field).intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " must be an integer", failure);
        }
    }

    private long number(JsonObject value, String field) {
        try {
            return decimalValue(value, field).longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(field + " must be a long", failure);
        }
    }

    private double decimal(JsonObject value, String field) {
        double result = decimalValue(value, field).doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
        return result;
    }

    private java.math.BigDecimal decimalValue(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        return element.getAsBigDecimal();
    }

    private boolean booleanValue(JsonObject value, String field) {
        JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
