package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies a journal-embedded coop source transition inside the population commit transaction. */
final class CompanionPopulationCoopLedgerMutation {
    private static final String FIELD = "coopLedgerMutation";

    private CompanionPopulationCoopLedgerMutation() {
    }

    static void applyIfPresent(@Nonnull Connection connection,
                               @Nonnull CoopLedgerRepository repository,
                               @Nullable String targetContextJson) throws Exception {
        JsonObject mutation = mutationObject(targetContextJson);
        if (mutation == null) {
            return;
        }
        Mode mode = Mode.valueOf(requiredString(mutation, "mode").toUpperCase(java.util.Locale.ROOT));
        CoopLedgerRow row = row(mutation);
        UUID previous = uuid(mutation, "previousNpcUuid");
        UUID current = uuid(mutation, "currentNpcUuid");
        if (mode == Mode.CAPTURE) {
            if (row.housedNpcUuid() == null || current == null
                    || !current.equals(row.housedNpcUuid())) {
                throw new IllegalArgumentException("Invalid atomic coop capture mutation.");
            }
            repository.upsertSlotIfSourceMatchesInTransaction(
                    connection,
                    row,
                    booleanValue(mutation, "expectedSlotPresent"),
                    uuid(mutation, "expectedHousedNpcUuid"),
                    uuid(mutation, "expectedLastReleasedNpcUuid")
            );
            return;
        }
        if (previous == null || current == null || row.housedNpcUuid() != null
                || !current.equals(row.lastReleasedNpcUuid())) {
            throw new IllegalArgumentException("Invalid atomic coop release mutation.");
        }
        repository.releaseAndRemapInTransaction(connection, row, previous, current);
    }

    @Nullable
    private static JsonObject mutationObject(@Nullable String targetContextJson) {
        if (targetContextJson == null || targetContextJson.isBlank()) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(targetContextJson);
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Population target context must be an object.");
        }
        JsonElement mutation = parsed.getAsJsonObject().get(FIELD);
        if (mutation == null || mutation.isJsonNull()) {
            return null;
        }
        if (!mutation.isJsonObject()) {
            throw new IllegalArgumentException("Coop ledger mutation must be an object.");
        }
        return mutation.getAsJsonObject();
    }

    @Nonnull
    private static CoopLedgerRow row(@Nonnull JsonObject source) {
        String world = nullableString(source, "worldName");
        String coopId = requiredString(source, "coopId");
        int x = requiredInt(source, "x");
        int y = requiredInt(source, "y");
        int z = requiredInt(source, "z");
        int slot = requiredInt(source, "residentSlot");
        return new CoopLedgerRow(
                String.valueOf(world) + "|" + x + "," + y + "," + z + "|" + slot,
                world,
                coopId,
                x,
                y,
                z,
                slot,
                uuid(source, "housedNpcUuid"),
                uuid(source, "lastReleasedNpcUuid"),
                uuid(source, "ownerId"),
                strings(source.get("toolIds")),
                nullableString(source, "roleId"),
                nullableString(source, "displayName"),
                requiredLong(source, "housedAtMs"),
                requiredLong(source, "releasedAtMs"),
                nullableString(source, "stateSnapshotJson")
        );
    }

    @Nonnull
    private static String[] strings(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new String[0];
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("toolIds must be an array.");
        }
        List<String> values = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (JsonElement value : array) {
            if (value != null && value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (text != null && !text.isBlank()) values.add(text.trim());
            }
        }
        return values.toArray(String[]::new);
    }

    @Nullable
    private static UUID uuid(@Nonnull JsonObject source, @Nonnull String field) {
        String value = nullableString(source, field);
        return value == null ? null : UUID.fromString(value);
    }

    @Nullable
    private static String nullableString(@Nonnull JsonObject source, @Nonnull String field) {
        JsonElement value = source.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    @Nonnull
    private static String requiredString(@Nonnull JsonObject source, @Nonnull String field) {
        String value = nullableString(source, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing coop mutation field: " + field);
        }
        return value.trim();
    }

    private static int requiredInt(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing coop mutation field: " + field);
        }
        return value.getAsInt();
    }

    private static long requiredLong(JsonObject source, String field) {
        JsonElement value = source.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Missing coop mutation field: " + field);
        }
        return value.getAsLong();
    }

    private static boolean booleanValue(JsonObject source, String field) {
        JsonElement value = source.get(field);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private enum Mode {
        CAPTURE,
        RELEASE
    }
}
