package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.google.gson.JsonParser;

/** Validates JSON values at the bonded adapter boundary before SQLite writes. */
final class SqliteBondedJson {
    private SqliteBondedJson() {
    }

    static boolean isNonEmptyObject(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            var parsed = JsonParser.parseString(value);
            return parsed.isJsonObject() && !parsed.getAsJsonObject().isEmpty();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static boolean isJson(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            JsonParser.parseString(value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
