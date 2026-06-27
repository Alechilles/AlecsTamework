package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

/** Resolves compact favorite-food display rows for the command target HUD. */
final class CommandTargetHudFoodResolver {
    private final BiFunction<Player, String, String> nameResolver;
    private final Function<String, String> iconResolver;

    CommandTargetHudFoodResolver() {
        this(CommandTargetHudFoodResolver::resolveItemName, CommandTargetHudFoodResolver::resolveItemIcon);
    }

    CommandTargetHudFoodResolver(BiFunction<Player, String, String> nameResolver,
                                 Function<String, String> iconResolver) {
        this.nameResolver = nameResolver != null ? nameResolver : CommandTargetHudFoodResolver::resolveItemName;
        this.iconResolver = iconResolver != null ? iconResolver : CommandTargetHudFoodResolver::resolveItemIcon;
    }

    @Nullable
    CommandTargetHudViewModel.FoodRow resolveFavoriteFood(@Nullable Player player,
                                                          @Nullable String[] itemIds) {
        String itemId = firstNonBlank(itemIds);
        if (itemId == null) {
            return null;
        }
        return new CommandTargetHudViewModel.FoodRow(
                itemId,
                safe(nameResolver.apply(player, itemId), humanize(itemId)),
                iconResolver.apply(itemId)
        );
    }

    List<CommandTargetHudViewModel.FoodRow> resolveFoods(@Nullable Player player,
                                                         @Nullable String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return List.of();
        }
        ArrayList<CommandTargetHudViewModel.FoodRow> rows = new ArrayList<>();
        for (String itemId : itemIds) {
            String clean = itemId == null ? null : itemId.trim();
            if (clean == null || clean.isBlank()) {
                continue;
            }
            rows.add(new CommandTargetHudViewModel.FoodRow(
                    clean,
                    safe(nameResolver.apply(player, clean), humanize(clean)),
                    iconResolver.apply(clean)
            ));
        }
        return rows.isEmpty() ? List.of() : List.copyOf(rows);
    }

    List<CommandTargetHudViewModel.FoodRow> resolveFoodEntries(@Nullable Player player,
                                                               @Nullable TwFoodConfig.FoodEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return List.of();
        }
        ArrayList<CommandTargetHudViewModel.FoodRow> rows = new ArrayList<>();
        for (TwFoodConfig.FoodEntry entry : entries) {
            if (entry == null || entry.itemId() == null || entry.itemId().isBlank()) {
                continue;
            }
            String clean = entry.itemId().trim();
            Double delta = Double.isFinite(entry.happinessDelta()) ? entry.happinessDelta() : null;
            rows.add(new CommandTargetHudViewModel.FoodRow(
                    clean,
                    safe(nameResolver.apply(player, clean), humanize(clean)),
                    iconResolver.apply(clean),
                    delta
            ));
        }
        return rows.isEmpty() ? List.of() : List.copyOf(rows);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return null;
        }
        for (String itemId : itemIds) {
            if (itemId != null && !itemId.isBlank()) {
                return itemId.trim();
            }
        }
        return null;
    }

    private static String resolveItemName(@Nullable Player player, String itemId) {
        String key = "items." + itemId + ".name";
        String localized = LocalizedText.resolve(player, key);
        if (localized != null && !localized.isBlank() && !localized.equals(key)) {
            return localized;
        }
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null && item.getTranslationKey() != null && !item.getTranslationKey().isBlank()) {
                String translated = LocalizedText.resolve(player, item.getTranslationKey());
                if (translated != null && !translated.isBlank() && !translated.equals(item.getTranslationKey())) {
                    return translated;
                }
            }
        } catch (Throwable ignored) {
            // Asset maps are not always available in focused unit tests.
        }
        return humanize(itemId);
    }

    @Nullable
    private static String resolveItemIcon(String itemId) {
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item == null) {
                return null;
            }
            Method method = item.getClass().getMethod("getIcon");
            Object value = method.invoke(item);
            return value instanceof String icon && !icon.isBlank() ? icon : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String humanize(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "Food";
        }
        String normalized = itemId.startsWith("Tw_") ? itemId.substring(3) : itemId;
        String[] parts = normalized.replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? itemId : out.toString();
    }

    private static String safe(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
