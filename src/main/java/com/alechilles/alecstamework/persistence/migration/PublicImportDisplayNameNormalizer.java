package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.localization.RoleNameResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nullable;

/**
 * Separates legacy public custom-name evidence from generic role display text.
 */
final class PublicImportDisplayNameNormalizer {
    private PublicImportDisplayNameNormalizer() {
    }

    @Nullable
    static String normalize(LegacyPublicData.Profile source) {
        String customName = customName(source.stateJson());
        if (customName != null) {
            return customName;
        }
        String displayName = normalizeText(source.displayName());
        return RoleNameResolver.isRoleIdentityDisplayName(
                displayName, source.roleId(), null
        ) ? null : displayName;
    }

    @Nullable
    private static String customName(@Nullable String stateJson) {
        if (stateJson == null || stateJson.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(stateJson);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject state = parsed.getAsJsonObject();
            return firstText(state, "custom_name", "customName");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static String firstText(JsonObject state, String... keys) {
        for (String key : keys) {
            JsonElement value = state.get(key);
            if (value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString()) {
                String normalized = normalizeText(value.getAsString());
                if (normalized != null) {
                    return normalized;
                }
            }
        }
        return null;
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
