package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.ui.CompanionViewBinding;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/** Maps configured icon states to the physical flute's item appearance. */
final class CommandFluteIconService {
    private CommandFluteIconService() { }

    static List<CompanionViewBinding.IconOption> options(@Nullable ItemStack stack,
                                                         @Nullable TwCommandItemConfig config,
                                                         @Nullable String language) {
        if (config == null || config.getIconOptions().length == 0) return List.of();
        Item base = baseItem(stack, config);
        if (base == null) return List.of();
        List<CompanionViewBinding.IconOption> choices = new ArrayList<>();
        choices.add(new CompanionViewBinding.IconOption(base.getId(),
                LocalizedText.resolve(language, "tamework.ui.views.originalIcon")));
        for (TwCommandItemConfig.IconOption configured : config.getIconOptions()) {
            Item variant = base.getItemForState(configured.getState());
            if (variant == null || variant.getIcon() == null || variant.getIcon().isBlank()) continue;
            choices.add(new CompanionViewBinding.IconOption(variant.getId(),
                    LocalizedText.resolveConfigValue(language, configured.getLabelKey(), configured.getState())));
        }
        return choices.size() == 1 ? List.of() : List.copyOf(choices);
    }

    static ItemStack choose(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config,
                            @Nullable String requestedItemId) {
        if (config == null || config.getIconOptions().length == 0) return stack;
        Item base = baseItem(stack, config);
        if (base == null || requestedItemId == null || requestedItemId.equals(stack.getItemId())) return stack;
        if (requestedItemId.equals(base.getId())) {
            return new ItemStack(base.getId(), stack.getQuantity(), stack.getDurability(),
                    stack.getMaxDurability(), stack.getQualityIndex(), stack.getMetadata());
        }
        for (TwCommandItemConfig.IconOption option : config.getIconOptions()) {
            String variantId = base.getItemIdForState(option.getState());
            if (requestedItemId.equals(variantId)) return stack.withState(option.getState());
        }
        return stack;
    }

    @Nullable
    private static Item baseItem(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        if (stack == null || stack.isEmpty() || config == null) return null;
        String baseId = ItemFeatureRegistry.normalizeStateItemId(stack.getItemId());
        if (baseId == null || Arrays.stream(config.getItemIds()).noneMatch(baseId::equals)) return null;
        return Item.getAssetMap().getAsset(baseId);
    }
}
