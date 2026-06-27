package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.util.ArrayList;
import java.util.Locale;
import javax.annotation.Nullable;

/** Resolves player-facing command item display labels for feedback and HUD text. */
final class CommandItemDisplayResolver {
    private static final String DEFAULT_COMMAND_ITEM_LABEL_KEY =
            "tamework.ui.notifications.tame.commandItem.default";
    private static final String DEFAULT_CRAFTING_STATION_KEY =
            "tamework.ui.notifications.tame.commandItem.craftingStation.default";

    String resolveItemDisplayName(@Nullable Player player, @Nullable String itemId) {
        String canonicalItemId = sanitizeItemId(itemId);
        if (canonicalItemId != null && !canonicalItemId.isBlank()) {
            String itemKey = "items." + canonicalItemId + ".name";
            String localized = LocalizedText.resolve(player, itemKey);
            if (localized != null && !localized.isBlank() && !localized.equals(itemKey)) {
                return localized;
            }
            try {
                Item item = Item.getAssetMap().getAsset(canonicalItemId);
                if (item != null && item.getTranslationKey() != null && !item.getTranslationKey().isBlank()) {
                    String translated = LocalizedText.resolve(player, item.getTranslationKey());
                    if (translated != null && !translated.isBlank() && !translated.equals(item.getTranslationKey())) {
                        return translated;
                    }
                }
            } catch (Throwable ignored) {
                // Tests and degraded startup can run without item assets.
            }
            return humanize(canonicalItemId);
        }
        return LocalizedText.resolve(player, DEFAULT_COMMAND_ITEM_LABEL_KEY);
    }

    String resolveCraftingStationDisplayName(@Nullable Player player) {
        return LocalizedText.resolve(player, DEFAULT_CRAFTING_STATION_KEY);
    }

    private static String sanitizeItemId(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        String sanitized = itemId.trim();
        if (sanitized.startsWith("*")) {
            sanitized = sanitized.substring(1);
        }
        int stateIndex = sanitized.indexOf("_State_");
        if (stateIndex > 0) {
            sanitized = sanitized.substring(0, stateIndex);
        }
        return sanitized.isBlank() ? null : sanitized;
    }

    private static String humanize(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Command Item";
        }
        String normalized = itemId;
        if (normalized.startsWith("Tw_")) {
            normalized = normalized.substring(3);
        }
        String[] rawParts = normalized.split("_");
        ArrayList<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            if (rawPart == null || rawPart.isBlank()) {
                continue;
            }
            parts.add(rawPart);
        }
        if (parts.isEmpty()) {
            return itemId;
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.toString();
    }
}
