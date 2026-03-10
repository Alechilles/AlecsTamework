package com.alechilles.alecstamework.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and decodes group-selection command ids used by linked panel group picker rows.
 */
final class LinkedNpcGroupSelectionCommandCodec {
    private static final String DEFAULT_GROUP_VALUE = "None";

    private LinkedNpcGroupSelectionCommandCodec() {
    }

    static String buildCommandId(String prefix, UUID npcUuid, String groupValue) {
        if (prefix == null || npcUuid == null) {
            return prefix == null ? "" : prefix;
        }
        return prefix + npcUuid + "|" + encodeGroupValue(groupValue);
    }

    static Selection parseCommandId(String commandId, String prefix) {
        if (commandId == null || prefix == null || !commandId.startsWith(prefix)) {
            return null;
        }
        String payload = commandId.substring(prefix.length());
        if (payload.isBlank()) {
            return null;
        }
        int separatorIndex = payload.indexOf('|');
        String uuidText = separatorIndex < 0 ? payload : payload.substring(0, separatorIndex);
        if (uuidText == null || uuidText.isBlank()) {
            return null;
        }
        UUID npcUuid;
        try {
            npcUuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        String groupValue = DEFAULT_GROUP_VALUE;
        if (separatorIndex >= 0 && separatorIndex + 1 < payload.length()) {
            String decoded = decodeGroupValue(payload.substring(separatorIndex + 1));
            if (decoded != null && !decoded.isBlank()) {
                groupValue = decoded;
            }
        }
        return new Selection(npcUuid, groupValue);
    }

    private static String encodeGroupValue(String groupValue) {
        String value = groupValue == null ? DEFAULT_GROUP_VALUE : groupValue.trim();
        if (value.isBlank()) {
            value = DEFAULT_GROUP_VALUE;
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeGroupValue(String encodedGroupValue) {
        if (encodedGroupValue == null || encodedGroupValue.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedGroupValue);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    record Selection(UUID npcUuid, String groupValue) {
    }
}
