package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves the item IDs that represent an NPC's tame food for display-only UI surfaces.
 */
public final class TameworkTameFoodDisplayResolver {
    private static final String[] EMPTY_ITEMS = new String[0];
    private static final String TRANQUILIZER_REQUIREMENT_ID = "TameworkEffectActive";
    private static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";
    private static final String TRANQUILIZER_SLEEP_THRESHOLD_PARAM = "TranquilizerSleepThresholdSeconds";

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
    public FoodDisplay resolveFoodDisplayItemIds(@Nullable String roleId, @Nullable Role role, boolean tamed) {
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return resolveFoodDisplayItemIds(config, role, tamed);
    }

    @Nonnull
    FoodDisplay resolveFoodDisplayItemIds(@Nullable TwInteractionConfig config, @Nullable Role role, boolean tamed) {
        InteractionParamResolver paramResolver = new InteractionParamResolver(null, null, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, paramResolver.resolveRoleScopes(role, null));
        InteractionItemIdResolver itemIdResolver = new InteractionItemIdResolver(paramResolver);
        String[] lovedItems = resolveLovedItems(paramResolver, role, ctx);
        String[] favoriteCandidates = resolveTamingFoodItemIds(config, role, itemIdResolver, ctx, lovedItems);
        String favorite = firstPreferredFood(lovedItems);
        if (favorite == null) {
            favorite = firstPreferredFood(favoriteCandidates);
        }

        LinkedHashSet<String> compatible = new LinkedHashSet<>();
        if (tamed) {
            addItems(compatible, lovedItems);
            addItems(compatible, favoriteCandidates);
            addItems(compatible, resolveFeedFoodItemIds(config, role, ctx, itemIdResolver, lovedItems));
        }
        removeItem(compatible, favorite);
        return new FoodDisplay(
                favorite == null ? EMPTY_ITEMS : new String[] { favorite },
                compatible.toArray(new String[0])
        );
    }

    public double resolveRequiredTranquilizerSeconds(@Nullable String roleId, @Nullable Role role) {
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return resolveRequiredTranquilizerSecondsInternal(role, config);
    }

    double resolveRequiredTranquilizerSeconds(@Nullable Role role, @Nullable TwInteractionConfig config) {
        return resolveRequiredTranquilizerSecondsInternal(role, config);
    }

    private double resolveRequiredTranquilizerSecondsInternal(@Nullable Role role, @Nullable TwInteractionConfig config) {
        InteractionParamResolver paramResolver = new InteractionParamResolver(null, null, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, paramResolver.resolveRoleScopes(role, null));
        double requiredSeconds = Math.max(
                0.0,
                paramResolver.getNumberParam(role, ctx, TRANQUILIZER_SLEEP_THRESHOLD_PARAM, 0.0)
        );
        if (config == null || !config.isEnabled()) {
            return requiredSeconds;
        }
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof TwInteractionConfig.TameInteraction) || !entry.isEnabled()) {
                continue;
            }
            requiredSeconds = Math.max(requiredSeconds, resolveRequiredTranquilizerSeconds(entry.getRequires()));
        }
        return requiredSeconds;
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
        return resolveTamingFoodItemIds(config, role, itemIdResolver, ctx, lovedItems);
    }

    @Nonnull
    private String[] resolveTamingFoodItemIds(@Nullable TwInteractionConfig config,
                                              @Nullable Role role,
                                              @Nonnull InteractionItemIdResolver itemIdResolver,
                                              @Nullable InteractionContextSnapshot ctx,
                                              @Nullable String[] lovedItems) {
        if (config == null || !config.isEnabled()) {
            return EMPTY_ITEMS;
        }
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
    private String[] resolveFeedFoodItemIds(@Nullable TwInteractionConfig config,
                                            @Nullable Role role,
                                            @Nullable InteractionContextSnapshot ctx,
                                            @Nonnull InteractionItemIdResolver itemIdResolver,
                                            @Nullable String[] lovedItems) {
        if (config == null || !config.isEnabled()) {
            return EMPTY_ITEMS;
        }
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof FeedInteraction feed) || !entry.isEnabled()) {
                continue;
            }
            InteractionRequiredItems items = itemIdResolver.resolveFeedRequirementItems(feed, role, ctx, lovedItems);
            addItems(resolved, items.getItems());
        }
        return resolved.toArray(new String[0]);
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

    @Nullable
    private static String firstPreferredFood(@Nullable String[] items) {
        if (!hasItems(items)) {
            return null;
        }
        for (String item : items) {
            String clean = cleanItemId(item);
            if (clean != null && !isUtilityFoodItem(clean)) {
                return clean;
            }
        }
        return cleanItemId(firstNonBlank(items));
    }

    @Nullable
    private static String firstNonBlank(@Nullable String[] items) {
        if (items == null) {
            return null;
        }
        for (String item : items) {
            String clean = cleanItemId(item);
            if (clean != null) {
                return clean;
            }
        }
        return null;
    }

    private static boolean isUtilityFoodItem(@Nonnull String itemId) {
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("tool_feedbag")
                || normalized.startsWith("tw_feed_");
    }

    private static void addItems(@Nonnull LinkedHashSet<String> target, @Nullable String[] source) {
        if (source == null || source.length == 0) {
            return;
        }
        for (String item : source) {
            String clean = cleanItemId(item);
            if (clean != null) {
                target.add(clean);
            }
        }
    }

    private static void removeItem(@Nonnull LinkedHashSet<String> items, @Nullable String item) {
        if (item == null || item.isBlank() || items.isEmpty()) {
            return;
        }
        ArrayList<String> matches = new ArrayList<>();
        for (String candidate : items) {
            if (candidate != null && candidate.equalsIgnoreCase(item)) {
                matches.add(candidate);
            }
        }
        items.removeAll(matches);
    }

    @Nullable
    private static String cleanItemId(@Nullable String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        return item.trim();
    }

    private static double resolveRequiredTranquilizerSeconds(@Nullable TwInteractionConfig.RequirementGroup group) {
        if (group == null) {
            return 0.0;
        }
        return Math.max(
                resolveRequiredTranquilizerSeconds(group.getAll()),
                resolveRequiredTranquilizerSeconds(group.getAny())
        );
    }

    private static double resolveRequiredTranquilizerSeconds(@Nullable TwInteractionConfig.RequirementBucket bucket) {
        if (bucket == null) {
            return 0.0;
        }
        double requiredSeconds = 0.0;
        for (TwInteractionConfig.CustomRequirement custom : bucket.getCustom()) {
            if (custom == null) {
                continue;
            }
            requiredSeconds = Math.max(
                    requiredSeconds,
                    resolveRequiredTranquilizerSeconds(custom.getId(), custom.getJsonPayload())
            );
        }
        return requiredSeconds;
    }

    public static double resolveRequiredTranquilizerSeconds(@Nullable String requirementId,
                                                            @Nullable String jsonPayload) {
        if (!TRANQUILIZER_REQUIREMENT_ID.equalsIgnoreCase(safeTrim(requirementId))) {
            return 0.0;
        }
        JsonObject payload = parsePayload(jsonPayload);
        if (payload == null || !matchesTranquilizerEffect(payload)) {
            return 0.0;
        }
        JsonElement value = payload.get("MinRemainingSeconds");
        if (value == null || !value.isJsonPrimitive()) {
            return 0.0;
        }
        try {
            double seconds = value.getAsDouble();
            return Double.isFinite(seconds) && seconds > 0.0 ? seconds : 0.0;
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    @Nullable
    private static JsonObject parsePayload(@Nullable String jsonPayload) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(jsonPayload);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matchesTranquilizerEffect(@Nonnull JsonObject payload) {
        JsonElement effectId = payload.get("EffectId");
        return effectId != null
                && effectId.isJsonPrimitive()
                && TRANQUILIZER_EFFECT_ID.equalsIgnoreCase(safeTrim(effectId.getAsString()));
    }

    @Nullable
    private static String safeTrim(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    private static String resolveLovedItemsParamName() {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        return globalConfig != null ? globalConfig.getLovedItemsParam() : "LovedItems";
    }

    public record FoodDisplay(@Nonnull String[] favoriteItemIds, @Nonnull String[] compatibleItemIds) {
        public FoodDisplay {
            favoriteItemIds = favoriteItemIds == null ? EMPTY_ITEMS : favoriteItemIds.clone();
            compatibleItemIds = compatibleItemIds == null ? EMPTY_ITEMS : compatibleItemIds.clone();
        }
    }
}
