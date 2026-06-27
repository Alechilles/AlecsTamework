package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves role-scoped parameters and feed item definitions. */
final class InteractionParamAccess {
    private final InteractionParamResolver paramResolver;
    private final InteractionItemIdResolver itemIdResolver;
    private final boolean hasLovedItemsOverride;
    private final String[] lovedItemsOverride;
    private final Boolean isHarvestableOverride;
    private final Boolean isMountableOverride;
    private final String lovedItemsParamName;
    private final String isHarvestableParamName;
    private final String isMountableParamName;

    InteractionParamAccess(InteractionParamResolver paramResolver,
                           boolean hasLovedItemsOverride,
                           String[] lovedItemsOverride,
                           Boolean isHarvestableOverride,
                           Boolean isMountableOverride,
                           String lovedItemsParamName,
                           String isHarvestableParamName,
                           String isMountableParamName) {
        this.paramResolver = paramResolver;
        this.itemIdResolver = new InteractionItemIdResolver(paramResolver);
        this.hasLovedItemsOverride = hasLovedItemsOverride;
        this.lovedItemsOverride = lovedItemsOverride;
        this.isHarvestableOverride = isHarvestableOverride;
        this.isMountableOverride = isMountableOverride;
        this.lovedItemsParamName = lovedItemsParamName;
        this.isHarvestableParamName = isHarvestableParamName;
        this.isMountableParamName = isMountableParamName;
    }

    // Builds an interaction snapshot from the player and role scopes.
    InteractionContextSnapshot buildContextSnapshot(Player player,
                                                   @Nullable Ref<EntityStore> playerRef,
                                                   Role role) {
        StdScope[] roleScopes = paramResolver.resolveRoleScopes(role, null);
        return InteractionContextSnapshot.from(player, roleScopes, playerRef);
    }

    // Resolves the primary role scope for param evaluation.
    StdScope resolveRoleScope(Role role) {
        return paramResolver.resolveRoleScope(role);
    }

    // Resolves ordered role scopes used for parameter fallback evaluation.
    StdScope[] resolveRoleScopes(Role role) {
        return paramResolver.resolveRoleScopes(role, null);
    }

    // Resolves a string param from role scopes.
    String getRoleStringParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getStringParam(role, ctx, paramName);
    }

    // Resolves a string array param from role scopes.
    String[] getRoleStringArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getStringArrayParam(role, ctx, paramName);
    }

    // Resolves a boolean param from role scopes.
    boolean getRoleBooleanParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getBooleanParam(role, ctx, paramName);
    }

    // Resolves a numeric param from role scopes with a default value.
    double getRoleNumberParam(Role role, InteractionContextSnapshot ctx, String paramName, double defaultValue) {
        return paramResolver.getNumberParam(role, ctx, paramName, defaultValue);
    }

    // Resolves a numeric array param from role scopes.
    double[] getRoleNumberArrayParam(Role role, InteractionContextSnapshot ctx, String paramName) {
        return paramResolver.getNumberArrayParam(role, ctx, paramName);
    }

    // Resolves loved items from overrides or role params.
    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        if (hasLovedItemsOverride) {
            return lovedItemsOverride != null ? lovedItemsOverride : new String[0];
        }
        String[] items = getRoleStringArrayParam(role, ctx, lovedItemsParamName);
        return items != null ? items : new String[0];
    }

    InteractionRequiredItems resolveFeedRequirementItems(FeedInteraction interaction,
                                                         Role role,
                                                         InteractionContextSnapshot ctx) {
        if (interaction == null) {
            return new InteractionRequiredItems(new String[0], false);
        }
        return itemIdResolver.resolveFeedRequirementItems(interaction, role, ctx, resolveLovedItems(role, ctx));
    }

    // Resolves whether the role is harvestable using overrides or params.
    boolean resolveIsHarvestable(Role role, InteractionContextSnapshot ctx) {
        if (isHarvestableOverride != null) {
            return isHarvestableOverride;
        }
        return getRoleBooleanParam(role, ctx, isHarvestableParamName);
    }

    // Resolves whether the role is mountable using overrides or params.
    boolean resolveIsMountable(Role role, InteractionContextSnapshot ctx) {
        if (isMountableOverride != null) {
            return isMountableOverride;
        }
        return getRoleBooleanParam(role, ctx, isMountableParamName);
    }

    // Resolves feed items from params, explicit items, or loved items.
    InteractionFeedItems resolveFeedItems(FeedInteraction interaction,
                                          Role role,
                                          InteractionContextSnapshot ctx) {
        if (interaction == null) {
            return new InteractionFeedItems(new String[0], new FeedItem[0], false);
        }
        FeedItem[] paramItems = resolveFeedItemsFromParam(role, ctx, interaction.getItemsParam());
        FeedItem[] explicitItems = interaction.getItemsInHand();
        FeedItem[] profileItems = InteractionItemParser.toFeedItems(
                TwFoodConfig.resolveAcceptedItemIdsForRole(resolveRoleId(role))
        );
        FeedItem[] mergedItems = mergeFeedItems(paramItems, explicitItems, profileItems);
        String[] mergedItemIds = InteractionItemParser.extractItemIds(mergedItems);
        if (mergedItemIds.length > 0) {
            return new InteractionFeedItems(mergedItemIds, mergedItems, true);
        }
        boolean useLovedItems = interaction.getUseLovedItems() == null || interaction.getUseLovedItems();
        if (useLovedItems) {
            return new InteractionFeedItems(resolveLovedItems(role, ctx), new FeedItem[0], true);
        }
        return new InteractionFeedItems(new String[0], new FeedItem[0], false);
    }

    @Nonnull
    private FeedItem[] mergeFeedItems(@Nullable FeedItem[]... sources) {
        LinkedHashMap<String, FeedItem> mergedByItemId = new LinkedHashMap<>();
        if (sources != null) {
            for (FeedItem[] source : sources) {
                mergeFeedItemsIntoMap(mergedByItemId, source);
            }
        }
        if (mergedByItemId.isEmpty()) {
            return new FeedItem[0];
        }
        return mergedByItemId.values().toArray(new FeedItem[0]);
    }

    @Nonnull
    private FeedItem[] mergeFeedItems(@Nullable FeedItem[] paramItems, @Nullable FeedItem[] explicitItems) {
        return mergeFeedItems(new FeedItem[][] { paramItems, explicitItems });
    }

    private void mergeFeedItemsIntoMap(@Nonnull Map<String, FeedItem> mergedByItemId,
                                       @Nullable FeedItem[] sourceItems) {
        if (sourceItems == null || sourceItems.length == 0) {
            return;
        }
        for (FeedItem source : sourceItems) {
            if (source == null || source.getItem() == null || source.getItem().isBlank()) {
                continue;
            }
            String normalizedItemId = source.getItem().trim().toLowerCase(Locale.ROOT);
            FeedItem existing = mergedByItemId.get(normalizedItemId);
            if (existing == null) {
                mergedByItemId.put(normalizedItemId, source);
                continue;
            }
            if (existing.getHeal() == null && source.getHeal() != null) {
                mergedByItemId.put(normalizedItemId, source);
            }
        }
    }

    // Resolves feed items from a parameter that can include JSON or item lists.
    private FeedItem[] resolveFeedItemsFromParam(Role role,
                                                 InteractionContextSnapshot ctx,
                                                 String paramName) {
        if (paramName == null || paramName.isBlank()) {
            return null;
        }
        String[] rawValues = getRoleStringArrayParam(role, ctx, paramName);
        if (rawValues == null || rawValues.length == 0) {
            return null;
        }
        if (rawValues.length == 1 && InteractionItemParser.looksLikeJsonArray(rawValues[0])) {
            FeedItem[] parsed = InteractionItemParser.parseFeedItemsFromJson(rawValues[0]);
            if (parsed != null && parsed.length > 0) {
                return parsed;
            }
            return null;
        }
        FeedItem[] items = InteractionItemParser.toFeedItems(rawValues);
        return items != null && items.length > 0 ? items : null;
    }

    @Nullable
    String[] resolveItemsParam(Role role, InteractionContextSnapshot ctx, String itemsParam) {
        return itemIdResolver.resolveItemsParam(role, ctx, itemsParam);
    }

    @Nullable
    private static String resolveRoleId(@Nullable Role role) {
        if (role == null || role.getRoleName() == null || role.getRoleName().isBlank()) {
            return null;
        }
        return role.getRoleName();
    }
}
