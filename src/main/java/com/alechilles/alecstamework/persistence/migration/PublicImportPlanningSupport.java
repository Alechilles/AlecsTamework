package com.alechilles.alecstamework.persistence.migration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/** Shared validation and deterministic hashing helpers for public import planning. */
final class PublicImportPlanningSupport {
    private PublicImportPlanningSupport() {
    }

    static PublicImportPlanningModel.ProfileDraft requireProfile(
            Map<String, PublicImportPlanningModel.ProfileDraft> profiles,
            String profileId,
            String code
    ) throws PublicImportException {
        PublicImportPlanningModel.ProfileDraft profile = profiles.get(profileId);
        if (profile == null) {
            throw refusal(code, profileId);
        }
        return profile;
    }

    static void requireUuid(String value, String code) throws PublicImportException {
        if (value == null) {
            throw refusal(code, "null");
        }
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw refusal(code, value);
            }
        } catch (IllegalArgumentException failure) {
            throw refusal(code, value);
        }
    }

    static void requireOptionalUuid(@Nullable String value, String code)
            throws PublicImportException {
        if (value != null) {
            requireUuid(value, code);
        }
    }

    static PublicImportException refusal(String code, String evidence) {
        return new PublicImportException(code, code + ": " + evidence);
    }

    static boolean flag(int value) {
        return value == 0 || value == 1;
    }

    static boolean validJson(String value) {
        try {
            JsonParser.parseString(value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static boolean validJsonObject(String value) {
        try {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonObject();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static String deterministicId(String fingerprint, String scope) {
        return UUID.nameUUIDFromBytes(
                (fingerprint + ":" + scope).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }
}
