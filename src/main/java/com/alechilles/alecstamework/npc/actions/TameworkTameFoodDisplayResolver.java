package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import com.alechilles.alecstamework.npc.sensors.SensorTameworkEffectActive;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.instructions.Instruction;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponent;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponentCollection;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
    private static final String FOOD_FAVORITE_PARAM = "FoodFavorite";
    private static final int MAX_ROLE_COMPONENT_VISITS = 512;

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
        TwFoodConfig.ResolvedFoodProfile foodProfile = TwFoodConfig.resolveProfileForRole(roleId);
        if (foodProfile != null && foodProfile.hasAnyFood()) {
            return fromFoodProfile(foodProfile, tamed);
        }
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return resolveFoodDisplayItemIds(config, role, tamed);
    }

    @Nonnull
    FoodDisplay resolveFoodDisplayItemIds(@Nullable TwInteractionConfig config, @Nullable Role role, boolean tamed) {
        InteractionParamResolver paramResolver = new InteractionParamResolver(null, null, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, paramResolver.resolveRoleScopes(role, null));
        InteractionItemIdResolver itemIdResolver = new InteractionItemIdResolver(paramResolver);
        String[] lovedItems = resolveLovedItems(paramResolver, role, ctx);
        String[] favoriteItems = resolveFavoriteItems(paramResolver, role, ctx);
        String[] favoriteCandidates = resolveTamingFoodItemIds(config, role, itemIdResolver, ctx, lovedItems);
        String favorite = firstPreferredFood(favoriteItems);
        if (favorite == null) {
            favorite = firstPreferredFood(lovedItems);
        }
        if (favorite == null) {
            favorite = firstPreferredFood(favoriteCandidates);
        }

        LinkedHashSet<String> compatible = new LinkedHashSet<>();
        if (tamed) {
            addItems(compatible, favoriteItems);
            addItems(compatible, lovedItems);
            addItems(compatible, favoriteCandidates);
            addItems(compatible, resolveFeedFoodItemIds(config, role, ctx, itemIdResolver, lovedItems));
        }
        if (favorite == null && !compatible.isEmpty()) {
            favorite = firstPreferredFood(compatible.toArray(new String[0]));
        }
        removeItem(compatible, favorite);
        return new FoodDisplay(
                favorite == null ? EMPTY_ITEMS : new String[] { favorite },
                compatible.toArray(new String[0]),
                legacyEntries(favorite, compatible)
        );
    }

    @Nonnull
    private static FoodDisplay fromFoodProfile(@Nonnull TwFoodConfig.ResolvedFoodProfile profile, boolean tamed) {
        TwFoodConfig.FoodEntry[] entries = profile.displayEntries(tamed);
        return new FoodDisplay(
                profile.preferredItemIds(),
                tamed ? nonPreferredEntries(entries) : EMPTY_ITEMS,
                entries
        );
    }

    @Nonnull
    private static String[] nonPreferredEntries(@Nullable TwFoodConfig.FoodEntry[] entries) {
        if (entries == null || entries.length == 0) {
            return EMPTY_ITEMS;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (TwFoodConfig.FoodEntry entry : entries) {
            if (entry == null || entry.itemId() == null || entry.itemId().isBlank()) {
                continue;
            }
            if (entry.category() == TwFoodConfig.FoodCategory.Preferred) {
                continue;
            }
            out.add(entry.itemId().trim());
        }
        return out.isEmpty() ? EMPTY_ITEMS : out.toArray(new String[0]);
    }

    @Nonnull
    private static TwFoodConfig.FoodEntry[] legacyEntries(@Nullable String favorite,
                                                          @Nonnull LinkedHashSet<String> compatible) {
        ArrayList<TwFoodConfig.FoodEntry> entries = new ArrayList<>();
        if (favorite != null && !favorite.isBlank()) {
            entries.add(new TwFoodConfig.FoodEntry(
                    favorite.trim(),
                    TwFoodConfig.FoodCategory.Preferred,
                    Double.NaN
            ));
        }
        for (String item : compatible) {
            if (item != null && !item.isBlank()) {
                entries.add(new TwFoodConfig.FoodEntry(
                        item.trim(),
                        TwFoodConfig.FoodCategory.Compatible,
                        Double.NaN
                ));
            }
        }
        return entries.isEmpty() ? new TwFoodConfig.FoodEntry[0] : entries.toArray(new TwFoodConfig.FoodEntry[0]);
    }

    public double resolveRequiredTranquilizerSeconds(@Nullable String roleId, @Nullable Role role) {
        TwInteractionConfig config = TwInteractionConfig.resolveForRole(roleId);
        return resolveRequiredTranquilizerSecondsInternal(role, config, resolveRoleParameterScope(roleId));
    }

    double resolveRequiredTranquilizerSeconds(@Nullable Role role, @Nullable TwInteractionConfig config) {
        return resolveRequiredTranquilizerSecondsInternal(role, config, null);
    }

    double resolveRequiredTranquilizerSeconds(@Nullable Role role,
                                              @Nullable TwInteractionConfig config,
                                              @Nullable StdScope roleParameterScope) {
        return resolveRequiredTranquilizerSecondsInternal(role, config, roleParameterScope);
    }

    private double resolveRequiredTranquilizerSecondsInternal(@Nullable Role role,
                                                             @Nullable TwInteractionConfig config,
                                                             @Nullable StdScope roleParameterScope) {
        InteractionParamResolver paramResolver = new InteractionParamResolver(roleParameterScope, null, null, null);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, paramResolver.resolveRoleScopes(role, null));
        double requiredSeconds = Math.max(
                0.0,
                paramResolver.getNumberParam(role, ctx, TRANQUILIZER_SLEEP_THRESHOLD_PARAM, 0.0)
        );
        requiredSeconds = Math.max(requiredSeconds, resolveRoleInstructionTranquilizerThreshold(role));
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

    private static double resolveRoleInstructionTranquilizerThreshold(@Nullable Role role) {
        if (role == null) {
            return 0.0;
        }
        try {
            return resolveTranquilizerEffectThresholdFromComponentTree(role.getRootInstruction());
        } catch (RuntimeException | LinkageError ignored) {
            return 0.0;
        }
    }

    static double resolveTranquilizerEffectThresholdFromComponentTreeForTests(@Nullable IAnnotatedComponent root) {
        return resolveTranquilizerEffectThresholdFromComponentTree(root);
    }

    private static double resolveTranquilizerEffectThresholdFromComponentTree(@Nullable IAnnotatedComponent root) {
        if (root == null) {
            return 0.0;
        }
        IdentityHashMap<IAnnotatedComponent, Boolean> visited = new IdentityHashMap<>();
        ArrayDeque<IAnnotatedComponent> pending = new ArrayDeque<>();
        pending.add(root);
        double requiredSeconds = 0.0;
        int visits = 0;
        while (!pending.isEmpty() && visits < MAX_ROLE_COMPONENT_VISITS) {
            IAnnotatedComponent component = pending.removeFirst();
            if (component == null || visited.put(component, Boolean.TRUE) != null) {
                continue;
            }
            visits++;
            if (component instanceof SensorTameworkEffectActive effectActive
                    && TRANQUILIZER_EFFECT_ID.equalsIgnoreCase(safeTrim(effectActive.getEffectId()))) {
                requiredSeconds = Math.max(requiredSeconds, effectActive.getMinRemainingSeconds());
            }
            if (component instanceof Instruction instruction) {
                addComponent(pending, instruction.getSensor());
            }
            if (component instanceof IAnnotatedComponentCollection collection) {
                addChildComponents(pending, collection);
            }
        }
        return requiredSeconds;
    }

    private static void addChildComponents(@Nonnull ArrayDeque<IAnnotatedComponent> pending,
                                           @Nonnull IAnnotatedComponentCollection collection) {
        int count;
        try {
            count = collection.componentCount();
        } catch (RuntimeException ignored) {
            return;
        }
        for (int i = 0; i < count; i++) {
            try {
                addComponent(pending, collection.getComponent(i));
            } catch (RuntimeException ignored) {
                // Ignore individual broken debug components; the HUD can still use other thresholds.
            }
        }
    }

    private static void addComponent(@Nonnull ArrayDeque<IAnnotatedComponent> pending,
                                     @Nullable IAnnotatedComponent component) {
        if (component != null) {
            pending.add(component);
        }
    }

    @Nullable
    private static StdScope resolveRoleParameterScope(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        try {
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null) {
                return null;
            }
            int roleIndex = npcPlugin.getIndex(roleId.trim());
            if (roleIndex < 0) {
                return null;
            }
            Builder<Role> roleBuilder = npcPlugin.tryGetCachedValidRole(roleIndex);
            BuilderParameters builderParameters = roleBuilder != null ? roleBuilder.getBuilderParameters() : null;
            return builderParameters != null ? builderParameters.createScope() : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
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

    @Nonnull
    private String[] resolveFavoriteItems(@Nonnull InteractionParamResolver paramResolver,
                                          @Nullable Role role,
                                          @Nullable InteractionContextSnapshot ctx) {
        String[] items = paramResolver.getStringArrayParam(role, ctx, FOOD_FAVORITE_PARAM);
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
        boolean requiresTranquilizedNpcState = false;
        for (TwInteractionConfig.StringRequirement state : bucket.getNpcState()) {
            if (isTranquilizedSleepState(state)) {
                requiresTranquilizedNpcState = true;
                break;
            }
        }
        for (TwInteractionConfig.CustomRequirement custom : bucket.getCustom()) {
            if (custom == null) {
                continue;
            }
            requiredSeconds = Math.max(
                    requiredSeconds,
                    resolveRequiredTranquilizerSeconds(custom.getId(), custom.getJsonPayload())
            );
        }
        if (requiresTranquilizedNpcState && requiredSeconds <= 0.0) {
            return TranquilizerStackDisplayService.STACK_DURATION_SECONDS;
        }
        return requiredSeconds;
    }

    private static boolean isTranquilizedSleepState(@Nullable TwInteractionConfig.StringRequirement state) {
        if (state == null) {
            return false;
        }
        return "Sleep".equalsIgnoreCase(safeTrim(state.getState()))
                && "Tranquilized".equalsIgnoreCase(safeTrim(state.getSubState()));
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

    public record FoodDisplay(@Nonnull String[] favoriteItemIds,
                              @Nonnull String[] compatibleItemIds,
                              @Nonnull TwFoodConfig.FoodEntry[] entries) {
        public FoodDisplay {
            favoriteItemIds = favoriteItemIds == null ? EMPTY_ITEMS : favoriteItemIds.clone();
            compatibleItemIds = compatibleItemIds == null ? EMPTY_ITEMS : compatibleItemIds.clone();
            entries = entries == null ? new TwFoodConfig.FoodEntry[0] : entries.clone();
        }
    }
}
