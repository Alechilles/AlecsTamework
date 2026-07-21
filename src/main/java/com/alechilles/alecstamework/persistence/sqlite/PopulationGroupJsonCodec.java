package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import javax.annotation.Nonnull;

/** Encodes sorted group IDs into bounded, deterministic JSON journal payloads. */
final class PopulationGroupJsonCodec {
    private static final Gson JSON = new Gson();

    private PopulationGroupJsonCodec() {
    }

    @Nonnull
    static String encode(@Nonnull List<String> groupIds) {
        return JSON.toJson(groupIds.toArray(String[]::new));
    }

    @Nonnull
    static List<String> decode(@Nonnull String json) {
        try {
            String[] values = JSON.fromJson(json, String[].class);
            if (values == null) {
                throw new IllegalArgumentException("Group JSON must be an array.");
            }
            TreeSet<String> sorted = new TreeSet<>();
            Arrays.stream(values).forEach(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("Group JSON contains a blank ID.");
                }
                sorted.add(value.trim());
            });
            return List.copyOf(sorted);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid group JSON.", exception);
        }
    }
}
