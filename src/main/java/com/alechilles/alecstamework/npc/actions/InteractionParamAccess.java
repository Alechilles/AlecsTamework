package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;

/** Resolves role-scoped parameters and feed item definitions. */
final class InteractionParamAccess {
    private final InteractionParamResolver paramResolver;
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
        this.hasLovedItemsOverride = hasLovedItemsOverride;
        this.lovedItemsOverride = lovedItemsOverride;
        this.isHarvestableOverride = isHarvestableOverride;
        this.isMountableOverride = isMountableOverride;
        this.lovedItemsParamName = lovedItemsParamName;
        this.isHarvestableParamName = isHarvestableParamName;
        this.isMountableParamName = isMountableParamName;
    }

    // Builds an interaction snapshot from the player and role scopes.
    InteractionContextSnapshot buildContextSnapshot(Player player, Role role) {
        StdScope[] roleScopes = paramResolver.resolveRoleScopes(role, null);
        return InteractionContextSnapshot.from(player, roleScopes);
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

    // Resolves loved items from overrides or role params.
    String[] resolveLovedItems(Role role, InteractionContextSnapshot ctx) {
        if (hasLovedItemsOverride) {
            return lovedItemsOverride != null ? lovedItemsOverride : new String[0];
        }
        String[] items = getRoleStringArrayParam(role, ctx, lovedItemsParamName);
        return items != null ? items : new String[0];
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
        if (paramItems != null && paramItems.length > 0) {
            String[] paramIds = InteractionItemParser.extractItemIds(paramItems);
            if (paramIds.length > 0) {
                return new InteractionFeedItems(paramIds, paramItems, true);
            }
        }
        FeedItem[] explicitItems = interaction.getItemsInHand();
        String[] explicitIds = InteractionItemParser.extractItemIds(explicitItems);
        if (explicitIds.length > 0) {
            return new InteractionFeedItems(explicitIds, explicitItems, true);
        }
        boolean useLovedItems = interaction.getUseLovedItems() == null || interaction.getUseLovedItems();
        if (useLovedItems) {
            return new InteractionFeedItems(resolveLovedItems(role, ctx), new FeedItem[0], true);
        }
        return new InteractionFeedItems(new String[0], new FeedItem[0], false);
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
}
