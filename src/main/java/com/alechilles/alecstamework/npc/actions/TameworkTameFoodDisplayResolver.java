package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the item IDs that represent an NPC's tame food for display-only UI surfaces.
 */
public final class TameworkTameFoodDisplayResolver {
    private static final String[] EMPTY_ITEMS = new String[0];

    private final String lovedItemsParamName;

    public TameworkTameFoodDisplayResolver() {
        this(resolveLovedItemsParamName());
    }

    TameworkTameFoodDisplayResolver(@Nullable String lovedItemsParamName) {
        this.lovedItemsParamName = lovedItemsParamName == null || lovedItemsParamName.isBlank()
                ? "LovedItems"
                : lovedItemsParamName;
    }

    @Nonnull
    public String[] resolveTamingFoodItemIds(@Nullable String roleId, @Nullable Role role) {
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return resolveTamingFoodItemIds(config, role);
    }

    @Nonnull
    String[] resolveTamingFoodItemIds(@Nullable TwInteractionConfig config, @Nullable Role role) {
        if (config == null || !config.isEnabled()) {
            return EMPTY_ITEMS;
        }
        InteractionParamResolver paramResolver = new InteractionParamResolver(null, null, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, paramResolver.resolveRoleScopes(role, null));
        InteractionItemIdResolver itemIdResolver = new InteractionItemIdResolver(paramResolver);
        String[] lovedItems = resolveLovedItems(paramResolver, role, ctx);
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof TwInteractionConfig.TameInteraction tame) || !entry.isEnabled()) {
                continue;
            }
            String[] resolved = resolveForTameInteraction(itemIdResolver, tame, role, ctx, lovedItems);
            if (hasItems(resolved)) {
                return resolved;
            }
        }
        return EMPTY_ITEMS;
    }

    @Nonnull
    private String[] resolveForTameInteraction(@Nonnull InteractionItemIdResolver itemIdResolver,
                                               @Nonnull TwInteractionConfig.TameInteraction tame,
                                               @Nullable Role role,
                                               @Nullable InteractionContextSnapshot ctx,
                                               @Nullable String[] lovedItems) {
        String[] paramItems = itemIdResolver.resolveItemsParam(role, ctx, tame.getItemsParam());
        if (hasItems(paramItems)) {
            return paramItems;
        }
        String[] explicitItems = tame.getItemsInHand();
        if (hasItems(explicitItems)) {
            return explicitItems;
        }
        if (tame.getUseLovedItems() == null || tame.getUseLovedItems()) {
            return lovedItems != null ? lovedItems : EMPTY_ITEMS;
        }
        return EMPTY_ITEMS;
    }

    @Nonnull
    private String[] resolveLovedItems(@Nonnull InteractionParamResolver paramResolver,
                                       @Nullable Role role,
                                       @Nullable InteractionContextSnapshot ctx) {
        String[] items = paramResolver.getStringArrayParam(role, ctx, lovedItemsParamName);
        return items != null ? items : EMPTY_ITEMS;
    }

    private static boolean hasItems(@Nullable String[] items) {
        if (items == null || items.length == 0) {
            return false;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String resolveLovedItemsParamName() {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return globalConfig != null ? globalConfig.getLovedItemsParam() : "LovedItems";
    }
}
