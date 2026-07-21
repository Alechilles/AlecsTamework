package com.alechilles.alecstamework.api;

import com.google.gson.JsonParser;
import java.util.Objects;

/** Package-private constructor validation shared by the immutable profile-data API records. */
final class ProfileDataValidation {
    static final int MAX_JSON_LENGTH = 1_048_576;

    private ProfileDataValidation() {
    }

    static String requireText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters.");
        }
        return normalized;
    }

    static String requireJson(String value) {
        String normalized = requireText(value, "jsonPayload", MAX_JSON_LENGTH);
        try {
            String canonical = JsonParser.parseString(normalized).toString();
            if (canonical.length() > MAX_JSON_LENGTH) {
                throw new IllegalArgumentException(
                        "jsonPayload exceeds " + MAX_JSON_LENGTH + " canonical characters.");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("jsonPayload must be valid JSON.", exception);
        }
    }
}
