package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Lenient BSON value helpers used by config codecs to avoid noisy decode errors for variant JSON shapes.
 */
final class TwCodecLenient {
    private static final String[] STRING_KEYS = new String[] {
            "Id",
            "id",
            "Value",
            "value",
            "Asset",
            "asset",
            "Compute",
            "compute",
            "Name",
            "name"
    };

    private static final String[] ARRAY_KEYS = new String[] {
            "Allowlist",
            "allowlist",
            "Denylist",
            "denylist",
            "RoleIds",
            "roleIds",
            "Roles",
            "roles",
            "Items",
            "items",
            "Slots",
            "slots",
            "Values",
            "values"
    };

    private TwCodecLenient() {
    }

    @Nullable
    static String asStringOrNull(@Nullable BsonValue value) {
        if (value == null || Codec.isNullBsonValue(value)) {
            return null;
        }
        if (value.isString()) {
            return value.asString().getValue();
        }
        if (value.isBoolean()) {
            return Boolean.toString(value.asBoolean().getValue());
        }
        if (value.isInt32()) {
            return Integer.toString(value.asInt32().getValue());
        }
        if (value.isInt64()) {
            return Long.toString(value.asInt64().getValue());
        }
        if (value.isDouble()) {
            return Double.toString(value.asDouble().getValue());
        }
        if (value.isDecimal128()) {
            return value.asDecimal128().decimal128Value().toString();
        }
        if (value.isArray()) {
            return value.asArray().size() == 1 ? asStringOrNull(value.asArray().get(0)) : null;
        }
        if (!value.isDocument()) {
            return null;
        }

        BsonDocument document = value.asDocument();
        BsonValue directValue = findFirst(document, STRING_KEYS);
        if (directValue != null) {
            String extracted = asStringOrNull(directValue);
            if (extracted != null && !extracted.isBlank()) {
                return extracted;
            }
        }
        if (document.size() == 1) {
            BsonValue onlyValue = document.entrySet().iterator().next().getValue();
            String extracted = asStringOrNull(onlyValue);
            if (extracted != null && !extracted.isBlank()) {
                return extracted;
            }
        }
        return null;
    }

    static String[] asStringArrayOrEmpty(@Nullable BsonValue value) {
        if (value == null || Codec.isNullBsonValue(value)) {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
        List<String> values = new ArrayList<>();
        appendStrings(value, values);
        if (values.isEmpty()) {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
        return values.toArray(ArrayUtil.EMPTY_STRING_ARRAY);
    }

    private static void appendStrings(@Nullable BsonValue value, List<String> destination) {
        if (value == null || Codec.isNullBsonValue(value)) {
            return;
        }
        if (value.isArray()) {
            for (BsonValue child : value.asArray()) {
                appendStrings(child, destination);
            }
            return;
        }
        if (value.isDocument()) {
            BsonDocument document = value.asDocument();
            BsonValue arrayValue = findFirst(document, ARRAY_KEYS);
            if (arrayValue != null) {
                appendStrings(arrayValue, destination);
                if (!destination.isEmpty()) {
                    return;
                }
            }
        }
        String extracted = asStringOrNull(value);
        if (extracted != null && !extracted.isBlank()) {
            destination.add(extracted);
        }
    }

    @Nullable
    private static BsonValue findFirst(BsonDocument document, String[] keys) {
        for (String key : keys) {
            if (document.containsKey(key)) {
                return document.get(key);
            }
        }
        return null;
    }
}
