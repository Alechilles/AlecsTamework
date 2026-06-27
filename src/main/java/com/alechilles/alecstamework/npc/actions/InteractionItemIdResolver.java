package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedItem;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves interaction item-id lists with lightweight caching for hot prompt/requirement paths.
 */
final class InteractionItemIdResolver {
    private static final String[] EMPTY_ITEMS = new String[0];

    private final InteractionParamResolver paramResolver;
    private final Map<FeedItem[], String[]> explicitFeedItemIdsByArray = new WeakHashMap<>();

    InteractionItemIdResolver(@Nonnull InteractionParamResolver paramResolver) {
        this.paramResolver = paramResolver;
    }

    @Nullable
    String[] resolveItemsParam(@Nullable Role role,
                               @Nullable InteractionContextSnapshot ctx,
                               @Nullable String itemsParam) {
        if (itemsParam == null || itemsParam.isBlank()) {
            return null;
        }
        if (ctx != null) {
            String[] cached = ctx.resolvedItemIdsByParamName.get(itemsParam);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }
        }
        String[] rawValues = paramResolver.getStringArrayParam(role, ctx, itemsParam);
        String[] resolved = rawValues == null || rawValues.length == 0
                ? null
                : InteractionItemParser.parseItemIdsFromParam(rawValues);
        if (ctx != null) {
            ctx.resolvedItemIdsByParamName.put(itemsParam, resolved != null ? resolved : EMPTY_ITEMS);
        }
        return resolved;
    }

    @Nonnull
    InteractionRequiredItems resolveFeedRequirementItems(@Nonnull FeedInteraction interaction,
                                                         @Nullable Role role,
                                                         @Nullable InteractionContextSnapshot ctx,
                                                         @Nullable String[] lovedItems) {
        if (ctx != null) {
            InteractionRequiredItems cached = ctx.feedRequirementItemsByInteraction.get(interaction);
            if (cached != null) {
                return cached;
            }
        }
        String[] paramItems = resolveItemsParam(role, ctx, interaction.getItemsParam());
        String[] explicitItems = resolveExplicitFeedItemIds(interaction.getItemsInHand());
        String[] profileItems = TwFoodConfig.resolveAcceptedItemIdsForRole(resolveRoleId(role));
        InteractionRequiredItems resolved;
        boolean hasParamItems = hasItems(paramItems);
        boolean hasExplicitItems = hasItems(explicitItems);
        boolean hasProfileItems = hasItems(profileItems);
        if (hasParamItems || hasExplicitItems || hasProfileItems) {
            resolved = new InteractionRequiredItems(combineItemIds(paramItems, explicitItems, profileItems), true);
        } else if (interaction.getUseLovedItems() == null || interaction.getUseLovedItems()) {
            resolved = new InteractionRequiredItems(lovedItems, true);
        } else {
            resolved = new InteractionRequiredItems(EMPTY_ITEMS, false);
        }
        if (ctx != null) {
            ctx.feedRequirementItemsByInteraction.put(interaction, resolved);
        }
        return resolved;
    }

    @Nonnull
    String[] resolveExplicitFeedItemIds(@Nullable FeedItem[] items) {
        if (items == null || items.length == 0) {
            return EMPTY_ITEMS;
        }
        synchronized (explicitFeedItemIdsByArray) {
            String[] cached = explicitFeedItemIdsByArray.get(items);
            if (cached != null) {
                return cached;
            }
            String[] resolved = InteractionItemParser.extractItemIds(items);
            explicitFeedItemIdsByArray.put(items, resolved != null ? resolved : EMPTY_ITEMS);
            return resolved != null ? resolved : EMPTY_ITEMS;
        }
    }

    @Nonnull
    private String[] combineItemIds(@Nullable String[]... sources) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (sources == null || sources.length == 0) {
            return EMPTY_ITEMS;
        }
        for (String[] source : sources) {
            if (!hasItems(source)) {
                continue;
            }
            for (String item : source) {
                if (item != null && !item.isBlank()) {
                    merged.add(item.trim());
                }
            }
        }
        return merged.isEmpty() ? EMPTY_ITEMS : merged.toArray(new String[0]);
    }

    private boolean hasItems(@Nullable String[] items) {
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

    @Nullable
    private static String resolveRoleId(@Nullable Role role) {
        if (role == null || role.getRoleName() == null || role.getRoleName().isBlank()) {
            return null;
        }
        return role.getRoleName();
    }
}
