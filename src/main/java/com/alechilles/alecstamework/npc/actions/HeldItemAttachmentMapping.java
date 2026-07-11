package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.InteractionEffectSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Parses exact held-item to model-attachment mappings used by interaction effects. */
final class HeldItemAttachmentMapping {
    private final String slotId;
    private final Map<String, String> valuesByItemId;

    private HeldItemAttachmentMapping(@Nonnull String slotId,
                                      @Nonnull Map<String, String> valuesByItemId) {
        this.slotId = slotId;
        this.valuesByItemId = Map.copyOf(valuesByItemId);
    }

    @Nullable
    static HeldItemAttachmentMapping parse(@Nullable InteractionEffectSpec spec) {
        if (spec == null || spec.param() == null || spec.param().isBlank()) {
            return null;
        }
        List<String> rawValues = spec.values();
        if (rawValues == null || rawValues.isEmpty()) {
            return null;
        }

        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        for (String rawValue : rawValues) {
            ParsedEntry entry = parseEntry(rawValue);
            if (entry == null) {
                return null;
            }
            String previous = mappings.putIfAbsent(entry.itemId(), entry.attachmentValue());
            if (previous != null && !previous.equals(entry.attachmentValue())) {
                return null;
            }
        }
        return mappings.isEmpty() ? null : new HeldItemAttachmentMapping(spec.param().trim(), mappings);
    }

    @Nullable
    private static ParsedEntry parseEntry(@Nullable String rawValue) {
        if (rawValue == null) {
            return null;
        }
        int separator = rawValue.indexOf('=');
        if (separator <= 0 || separator != rawValue.lastIndexOf('=') || separator >= rawValue.length() - 1) {
            return null;
        }
        String itemId = rawValue.substring(0, separator).trim();
        String attachmentValue = rawValue.substring(separator + 1).trim();
        return itemId.isEmpty() || attachmentValue.isEmpty()
                ? null
                : new ParsedEntry(itemId, attachmentValue);
    }

    @Nonnull
    String slotId() {
        return slotId;
    }

    @Nullable
    String resolve(@Nullable String itemId) {
        return itemId == null ? null : valuesByItemId.get(itemId);
    }

    int size() {
        return valuesByItemId.size();
    }

    private record ParsedEntry(@Nonnull String itemId, @Nonnull String attachmentValue) {
    }
}
