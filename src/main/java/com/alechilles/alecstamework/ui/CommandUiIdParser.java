package com.alechilles.alecstamework.ui;

import java.util.UUID;

/**
 * Parses and compares command UI ids used by command selection pages.
 */
final class CommandUiIdParser {
    private CommandUiIdParser() {
    }

    static boolean commandIdEquals(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    static UUID parseNpcUuid(String commandId, String prefix) {
        if (commandId == null || prefix == null || !commandId.startsWith(prefix)) {
            return null;
        }
        String raw = commandId.substring(prefix.length());
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
