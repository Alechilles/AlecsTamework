package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.api.InteractionEffectSpec;
import com.alechilles.alecstamework.api.InteractionRequirementSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Parses exact held-item to model-attachment mappings used by interaction gates and effects. */
final class HeldItemAttachmentMapping {
    private final String slotId;
    private final Map<String, String> valuesByItemId;
    private final Map<String, String> itemIdsByValue;

    private HeldItemAttachmentMapping(@Nonnull String slotId,
                                      @Nonnull Map<String, String> valuesByItemId,
                                      @Nonnull Map<String, String> itemIdsByValue) {
        this.slotId = slotId;
        this.valuesByItemId = Map.copyOf(valuesByItemId);
        this.itemIdsByValue = Map.copyOf(itemIdsByValue);
    }

    @Nullable
    static HeldItemAttachmentMapping parse(@Nullable InteractionEffectSpec spec) {
        return spec == null ? null : parse(spec.param(), spec.values(), false);
    }

    /** Parses a reversible mapping used by attachment exchange requirements. */
    @Nullable
    static HeldItemAttachmentMapping parseExchange(@Nullable InteractionRequirementSpec spec) {
        return spec == null ? null : parse(spec.param(), spec.values(), true);
    }

    /** Parses a reversible mapping used by attachment exchange effects. */
    @Nullable
    static HeldItemAttachmentMapping parseExchange(@Nullable InteractionEffectSpec spec) {
        return spec == null ? null : parse(spec.param(), spec.values(), true);
    }

    @Nullable
    private static HeldItemAttachmentMapping parse(@Nullable String slotId,
                                                   @Nullable List<String> rawValues,
                                                   boolean requireReversible) {
        if (slotId == null || slotId.isBlank()) {
            return null;
        }
        if (rawValues == null || rawValues.isEmpty()) {
            return null;
        }

        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        LinkedHashMap<String, String> reverseMappings = new LinkedHashMap<>();
        for (String rawValue : rawValues) {
            ParsedEntry entry = parseEntry(rawValue);
            if (entry == null) {
                return null;
            }
            String previous = mappings.putIfAbsent(entry.itemId(), entry.attachmentValue());
            if (previous != null && !previous.equals(entry.attachmentValue())) {
                return null;
            }
            String previousItem = reverseMappings.putIfAbsent(entry.attachmentValue(), entry.itemId());
            if (requireReversible && previousItem != null && !previousItem.equals(entry.itemId())) {
                return null;
            }
        }
        return mappings.isEmpty()
                ? null
                : new HeldItemAttachmentMapping(slotId.trim(), mappings, reverseMappings);
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

    @Nullable
    String resolveItemId(@Nullable String attachmentValue) {
        return attachmentValue == null ? null : itemIdsByValue.get(attachmentValue);
    }

    int size() {
        return valuesByItemId.size();
    }

    private record ParsedEntry(@Nonnull String itemId, @Nonnull String attachmentValue) {
    }
}
