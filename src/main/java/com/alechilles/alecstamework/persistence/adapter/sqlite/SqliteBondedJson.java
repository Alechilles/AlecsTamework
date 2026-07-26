package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.google.gson.JsonParser;
import java.util.Base64;

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

    static boolean isObject(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return JsonParser.parseString(value).isJsonObject();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static boolean isSnapshotEnvelope(String value) {
        if (!isObject(value)) return false;
        try {
            var object = JsonParser.parseString(value).getAsJsonObject();
            return object.has("encoding") && object.has("payload")
                    && "base64".equals(object.get("encoding").getAsString())
                    && Base64.getDecoder().decode(
                            object.get("payload").getAsString()).length > 0;
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
