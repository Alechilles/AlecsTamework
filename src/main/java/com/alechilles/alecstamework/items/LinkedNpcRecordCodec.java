package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.joml.Vector3d;

/**
 * Encodes and decodes the backward-compatible, line-oriented command-item NPC record format.
 */
final class LinkedNpcRecordCodec {
    private static final String PARTS_SEPARATOR = "\\|";
    private static final String TOKEN_PROFILE_ID = "pid=";
    private static final String TOKEN_DISPLAY_NAME = "dn=";
    private static final String TOKEN_NAME_KEY = "nk=";
    private static final String TOKEN_ROLE_ID = "rid=";
    private static final String TOKEN_COMMAND_STATE = "cs=";
    private static final String TOKEN_ACTIVE = "ac=";
    private static final String TOKEN_BREEDING_ENABLED = "be=";
    private static final String TOKEN_GROUP_ID = "gid=";
    private static final String TOKEN_LAST_KNOWN_WORLD = "lw=";

    String encode(LinkedNpcRecord record) {
        StringBuilder builder = new StringBuilder(record.npcUuid.toString());
        Vector3d encodedLastKnown = record.lastKnownPosition != null
                ? record.lastKnownPosition
                : record.homePosition;
        appendVector(builder, encodedLastKnown);
        appendVector(builder, record.homePosition);
        appendRawToken(builder, TOKEN_PROFILE_ID, record.profileId);
        appendTextToken(builder, TOKEN_DISPLAY_NAME, record.cachedDisplayName);
        appendTextToken(builder, TOKEN_NAME_KEY, record.cachedNameKey);
        appendTextToken(builder, TOKEN_ROLE_ID, record.cachedRoleId);
        appendTextToken(builder, TOKEN_LAST_KNOWN_WORLD, record.lastKnownWorldName);
        appendTextToken(builder, TOKEN_COMMAND_STATE, record.cachedCommandState);
        if (!record.active) {
            builder.append('|').append(TOKEN_ACTIVE).append('0');
        }
        if (record.breedingEnabled) {
            builder.append('|').append(TOKEN_BREEDING_ENABLED).append('1');
        }
        appendTextToken(builder, TOKEN_GROUP_ID, record.groupId);
        return builder.toString();
    }

    LinkedNpcRecord parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(PARTS_SEPARATOR);
        UUID uuid = parseUuid(parts);
        if (uuid == null) {
            return null;
        }
        ParsedVectors vectors = parseVectors(parts);
        ParsedTokens tokens = parseTokens(parts, vectors.nextIndex());
        return new LinkedNpcRecord(
                uuid,
                tokens.profileId,
                vectors.position,
                tokens.lastKnownWorldName,
                vectors.homePosition,
                tokens.cachedDisplayName,
                tokens.cachedNameKey,
                tokens.cachedRoleId,
                tokens.cachedCommandState,
                tokens.active,
                tokens.breedingEnabled,
                tokens.groupId
        );
    }

    static String normalizeProfileId(String profileId) {
        return profileId == null || profileId.isBlank() ? null : profileId.trim();
    }

    private UUID parseUuid(String[] parts) {
        if (parts.length == 0) {
            return null;
        }
        try {
            return UUID.fromString(parts[0].trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ParsedVectors parseVectors(String[] parts) {
        Vector3d position = parseVector(parts, 1);
        int nextIndex = position != null ? 4 : 1;
        Vector3d homePosition = parseVector(parts, nextIndex);
        if (homePosition != null) {
            nextIndex += 3;
        }
        return new ParsedVectors(position, homePosition, nextIndex);
    }

    private Vector3d parseVector(String[] parts, int offset) {
        if (parts.length < offset + 3) {
            return null;
        }
        try {
            return new Vector3d(
                    Double.parseDouble(parts[offset]),
                    Double.parseDouble(parts[offset + 1]),
                    Double.parseDouble(parts[offset + 2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ParsedTokens parseTokens(String[] parts, int startIndex) {
        ParsedTokens tokens = new ParsedTokens();
        for (int index = startIndex; index < parts.length; index++) {
            String token = parts[index];
            if (token == null || token.isBlank()) {
                continue;
            }
            applyToken(tokens, token);
        }
        return tokens;
    }

    private void applyToken(ParsedTokens tokens, String token) {
        if (token.startsWith(TOKEN_PROFILE_ID)) {
            tokens.profileId = normalizeProfileId(token.substring(TOKEN_PROFILE_ID.length()));
        } else if (token.startsWith(TOKEN_DISPLAY_NAME)) {
            tokens.cachedDisplayName = decodeText(token, TOKEN_DISPLAY_NAME);
        } else if (token.startsWith(TOKEN_NAME_KEY)) {
            tokens.cachedNameKey = decodeText(token, TOKEN_NAME_KEY);
        } else if (token.startsWith(TOKEN_ROLE_ID)) {
            tokens.cachedRoleId = decodeText(token, TOKEN_ROLE_ID);
        } else if (token.startsWith(TOKEN_LAST_KNOWN_WORLD)) {
            tokens.lastKnownWorldName = decodeText(token, TOKEN_LAST_KNOWN_WORLD);
        } else if (token.startsWith(TOKEN_COMMAND_STATE)) {
            tokens.cachedCommandState = decodeText(token, TOKEN_COMMAND_STATE);
        } else if (token.startsWith(TOKEN_ACTIVE)) {
            String flag = token.substring(TOKEN_ACTIVE.length()).trim();
            tokens.active = !"0".equals(flag) && !"false".equalsIgnoreCase(flag);
        } else if (token.startsWith(TOKEN_BREEDING_ENABLED)) {
            String flag = token.substring(TOKEN_BREEDING_ENABLED.length()).trim();
            tokens.breedingEnabled = "1".equals(flag) || "true".equalsIgnoreCase(flag);
        } else if (token.startsWith(TOKEN_GROUP_ID)) {
            tokens.groupId = decodeText(token, TOKEN_GROUP_ID);
        }
    }

    private void appendVector(StringBuilder builder, Vector3d vector) {
        if (vector == null) {
            return;
        }
        builder.append('|').append(vector.x);
        builder.append('|').append(vector.y);
        builder.append('|').append(vector.z);
    }

    private void appendRawToken(StringBuilder builder, String prefix, String value) {
        String normalized = normalizeProfileId(value);
        if (normalized != null) {
            builder.append('|').append(prefix).append(normalized);
        }
    }

    private void appendTextToken(StringBuilder builder, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            builder.append('|').append(prefix).append(encodeText(value));
        }
    }

    private String encodeText(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeText(String token, String prefix) {
        String encoded = token.substring(prefix.length());
        if (encoded.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            return decoded.isBlank() ? null : decoded;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record ParsedVectors(Vector3d position, Vector3d homePosition, int nextIndex) {
    }

    private static final class ParsedTokens {
        private String profileId;
        private String cachedDisplayName;
        private String cachedNameKey;
        private String cachedRoleId;
        private String cachedCommandState;
        private String lastKnownWorldName;
        private boolean active = true;
        private boolean breedingEnabled;
        private String groupId;
    }
}
