package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Keeps tranquilizer recipes hidden unless their corresponding TwGlobalConfig asset-set gate is enabled.
 */
public final class TranquilizerRecipeVisibilityService {
    private static final String ASSET_SOURCE_ID = "Hytale:Hytale";
    private static final String ITEM_TRANQUILIZER_SHORTBOW = "Weapon_Shortbow_Tranquilizer";
    private static final String ITEM_TRANQUILIZER_ARROW = "Weapon_Arrow_Tranquilizer";
    private static final String ITEM_TRANQUILIZER_POTION = "Potion_Tranquilizer";

    private boolean reconciling;

    public synchronized void reconcile() {
        if (reconciling) {
            return;
        }
        reconciling = true;
        try {
            DefaultAssetMap<String, CraftingRecipe> recipeMap = CraftingRecipe.getAssetMap();
            DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();
            AssetStore<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> recipeStore = CraftingRecipe.getAssetStore();
            if (recipeMap == null || itemMap == null || recipeStore == null || recipeMap.getAssetMap() == null) {
                return;
            }
            TwGlobalConfig.AssetSetToggles toggles = TwGlobalConfig.resolveEnabledAssetSets();

            Set<String> removeRecipeIds = new LinkedHashSet<>();
            for (CraftingRecipe recipe : recipeMap.getAssetMap().values()) {
                if (recipe == null) {
                    continue;
                }
                String outputItemId = resolvePrimaryOutputItemId(recipe);
                if (outputItemId == null || isItemEnabled(outputItemId, toggles)) {
                    continue;
                }
                String recipeId = recipe.getId();
                if (recipeId != null && !recipeId.isBlank()) {
                    removeRecipeIds.add(recipeId);
                }
            }
            if (!removeRecipeIds.isEmpty()) {
                recipeStore.removeAssets(new ArrayList<>(removeRecipeIds));
            }

            List<CraftingRecipe> recipesToRestore = new ArrayList<>();
            addMissingGeneratedRecipesIfEnabled(
                    ITEM_TRANQUILIZER_SHORTBOW,
                    toggles.isTranquilizerShortbowEnabled(),
                    itemMap,
                    recipeMap,
                    recipesToRestore
            );
            addMissingGeneratedRecipesIfEnabled(
                    ITEM_TRANQUILIZER_ARROW,
                    toggles.isTranquilizerArrowEnabled(),
                    itemMap,
                    recipeMap,
                    recipesToRestore
            );
            addMissingGeneratedRecipesIfEnabled(
                    ITEM_TRANQUILIZER_POTION,
                    toggles.isTranquilizerPotionEnabled(),
                    itemMap,
                    recipeMap,
                    recipesToRestore
            );
            if (!recipesToRestore.isEmpty()) {
                recipeStore.loadAssets(ASSET_SOURCE_ID, recipesToRestore);
            }
        } finally {
            reconciling = false;
        }
    }

    private void addMissingGeneratedRecipesIfEnabled(@Nonnull String itemId,
                                                     boolean enabled,
                                                     @Nonnull DefaultAssetMap<String, Item> itemMap,
                                                     @Nonnull DefaultAssetMap<String, CraftingRecipe> recipeMap,
                                                     @Nonnull List<CraftingRecipe> recipesToRestore) {
        if (!enabled) {
            return;
        }
        Item item = itemMap.getAsset(itemId);
        if (item == null || !item.hasRecipesToGenerate()) {
            return;
        }
        List<CraftingRecipe> generatedRecipes = new ArrayList<>();
        item.collectRecipesToGenerate(generatedRecipes);
        for (CraftingRecipe recipe : generatedRecipes) {
            if (recipe == null) {
                continue;
            }
            String recipeId = recipe.getId();
            if (recipeId == null || recipeId.isBlank()) {
                continue;
            }
            if (recipeMap.getAsset(recipeId) != null) {
                continue;
            }
            recipesToRestore.add(recipe);
        }
    }

    @Nullable
    private String resolvePrimaryOutputItemId(@Nonnull CraftingRecipe recipe) {
        MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
        if (primaryOutput == null) {
            return null;
        }
        String itemId = primaryOutput.getItemId();
        return itemId == null || itemId.isBlank() ? null : itemId;
    }

    private boolean isItemEnabled(@Nonnull String itemId, @Nonnull TwGlobalConfig.AssetSetToggles toggles) {
        if (ITEM_TRANQUILIZER_SHORTBOW.equals(itemId)) {
            return toggles.isTranquilizerShortbowEnabled();
        }
        if (ITEM_TRANQUILIZER_ARROW.equals(itemId)) {
            return toggles.isTranquilizerArrowEnabled();
        }
        if (ITEM_TRANQUILIZER_POTION.equals(itemId)) {
            return toggles.isTranquilizerPotionEnabled();
        }
        return true;
    }
}
