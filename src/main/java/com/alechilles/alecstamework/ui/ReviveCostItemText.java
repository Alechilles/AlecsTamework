package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.util.Locale;
import javax.annotation.Nullable;

/** Resolves translated, player-facing item labels for revival cost presentation. */
final class ReviveCostItemText {
    private ReviveCostItemText() {
    }

    static String resolve(
            @Nullable String itemId,
            @Nullable String suppliedName,
            @Nullable String language
    ) {
        String normalizedItemId = itemId == null ? "" : itemId.trim();
        if (normalizedItemId.isEmpty()) {
            return "Item";
        }
        String directKey = "items." + normalizedItemId + ".name";
        String directTranslation = LocalizedText.resolve(language, directKey);
        if (isResolved(directTranslation, directKey)) {
            return directTranslation;
        }
        try {
            Item item = Item.getAssetMap().getAsset(normalizedItemId);
            if (item != null && item.getTranslationKey() != null
                    && !item.getTranslationKey().isBlank()) {
                String translated = LocalizedText.resolve(language,
                        item.getTranslationKey());
                if (isResolved(translated, item.getTranslationKey())) {
                    return translated;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Focused tests and degraded asset startup still need safe text.
        }
        if (suppliedName != null && !suppliedName.isBlank()
                && !normalizedItemId.equals(suppliedName.trim())) {
            return suppliedName.trim();
        }
        return humanize(normalizedItemId);
    }

    private static boolean isResolved(String value, String key) {
        return value != null && !value.isBlank() && !value.equals(key);
    }

    private static String humanize(String itemId) {
        String normalized = itemId.startsWith("Tw_") ? itemId.substring(3)
                : itemId;
        StringBuilder result = new StringBuilder();
        for (String word : normalized.replace('-', '_').split("_")) {
            if (word.isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return result.isEmpty() ? itemId : result.toString();
    }
}
