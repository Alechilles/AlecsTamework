package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped companion leveling config covering XP sources, level curves, stat growth, and talent point grants.
 * Stored under Server/Tamework/Leveling.
 */
public final class TwLevelingConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwLevelingConfig>>,
        TwParentFallbackAsset<TwLevelingConfig> {
    private static final GrowthEffect[] EMPTY_EFFECTS = new GrowthEffect[0];

    private static final BuilderCodec<LevelSettings> LEVEL_SETTINGS_CODEC = BuilderCodec.builder(
            LevelSettings.class,
            LevelSettings::new
    )
            .<Integer>append(
                    new KeyedCodec<>("MaxLevel", Codec.INTEGER),
                    (settings, value) -> settings.maxLevel = value,
                    settings -> settings.maxLevel
            )
            .documentation("Maximum level this companion can reach.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("BaseXp", Codec.DOUBLE),
                    (settings, value) -> settings.baseXp = value,
                    settings -> settings.baseXp
            )
            .documentation("XP required for the first level-up from level 1 to level 2.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("GrowthFactor", Codec.DOUBLE),
                    (settings, value) -> settings.growthFactor = value,
                    settings -> settings.growthFactor
            )
            .documentation("Multiplier applied to each successive level-up XP requirement.")
            .add()
            .build();

    private static final BuilderCodec<SimpleXpSourceSettings> SIMPLE_XP_SOURCE_CODEC = BuilderCodec.builder(
            SimpleXpSourceSettings.class,
            SimpleXpSourceSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value != null && value,
                    settings -> settings.enabled
            )
            .documentation("If true, this XP source awards XP when triggered.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("FlatXp", Codec.DOUBLE),
                    (settings, value) -> settings.flatXp = value,
                    settings -> settings.flatXp
            )
            .documentation("Flat XP awarded when this source succeeds.")
            .add()
            .build();

    private static final BuilderCodec<CombatXpSourceSettings> COMBAT_XP_SOURCE_CODEC = BuilderCodec.builder(
            CombatXpSourceSettings.class,
            CombatXpSourceSettings::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (settings, value) -> settings.enabled = value != null && value,
                    settings -> settings.enabled
            )
            .documentation("If true, companions gain XP from real combat damage events.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DamageDealtXpPerPoint", Codec.DOUBLE),
                    (settings, value) -> settings.damageDealtXpPerPoint = value,
                    settings -> settings.damageDealtXpPerPoint
            )
            .documentation("XP gained per point of final damage dealt.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("DamageTakenXpPerPoint", Codec.DOUBLE),
                    (settings, value) -> settings.damageTakenXpPerPoint = value,
                    settings -> settings.damageTakenXpPerPoint
            )
            .documentation("XP gained per point of final damage taken.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("MinimumDamageEvent", Codec.DOUBLE),
                    (settings, value) -> settings.minimumDamageEvent = value,
                    settings -> settings.minimumDamageEvent
            )
            .documentation("Minimum final damage required before a combat event grants XP.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("AwardVsPlayers", Codec.BOOLEAN),
                    (settings, value) -> settings.awardVsPlayers = value != null && value,
                    settings -> settings.awardVsPlayers
            )
            .documentation("If true, damage involving player targets or player attackers can grant combat XP.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("AwardVsOwnedAllies", Codec.BOOLEAN),
                    (settings, value) -> settings.awardVsOwnedAllies = value != null && value,
                    settings -> settings.awardVsOwnedAllies
            )
            .documentation("If true, damage between companions or players sharing the same owner can grant combat XP.")
            .add()
            .build();

    private static final BuilderCodec<XpSourcesSettings> XP_SOURCES_CODEC = BuilderCodec.builder(
            XpSourcesSettings.class,
            XpSourcesSettings::new
    )
            .<SimpleXpSourceSettings>append(
                    new KeyedCodec<>("Feed", SIMPLE_XP_SOURCE_CODEC),
                    (settings, value) -> settings.feed = value == null ? new SimpleXpSourceSettings() : value,
                    settings -> settings.feed
            )
            .documentation("Feed XP source settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<SimpleXpSourceSettings>append(
                    new KeyedCodec<>("Harvest", SIMPLE_XP_SOURCE_CODEC),
                    (settings, value) -> settings.harvest = value == null ? new SimpleXpSourceSettings() : value,
                    settings -> settings.harvest
            )
            .documentation("Harvest XP source settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<SimpleXpSourceSettings>append(
                    new KeyedCodec<>("Breeding", SIMPLE_XP_SOURCE_CODEC),
                    (settings, value) -> settings.breeding = value == null ? new SimpleXpSourceSettings() : value,
                    settings -> settings.breeding
            )
            .documentation("Breeding XP source settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<CombatXpSourceSettings>append(
                    new KeyedCodec<>("Combat", COMBAT_XP_SOURCE_CODEC),
                    (settings, value) -> settings.combat = value == null ? new CombatXpSourceSettings() : value,
                    settings -> settings.combat
            )
            .documentation("Combat XP source settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .build();

    private static final BuilderCodec<GrowthEffect> GROWTH_EFFECT_CODEC = BuilderCodec.builder(
            GrowthEffect.class,
            GrowthEffect::new
    )
            .<String>append(
                    new KeyedCodec<>("EffectKey", Codec.STRING),
                    (effect, value) -> effect.effectKey = value,
                    effect -> effect.effectKey
            )
            .documentation("Shared progression effect key to scale by level.")
            .add()
            .<Double>append(
                    new KeyedCodec<>("PerLevel", Codec.DOUBLE),
                    (effect, value) -> effect.perLevel = value,
                    effect -> effect.perLevel
            )
            .documentation("Additive multiplier growth applied per level above level 1. Example: 0.03 => 3% per level.")
            .add()
            .build();
    private static final ArrayCodec<GrowthEffect> GROWTH_EFFECT_ARRAY_CODEC =
            new ArrayCodec<>(GROWTH_EFFECT_CODEC, GrowthEffect[]::new);

    private static final BuilderCodec<StatGrowthSettings> STAT_GROWTH_CODEC = BuilderCodec.builder(
            StatGrowthSettings.class,
            StatGrowthSettings::new
    )
            .<GrowthEffect[]>append(
                    new KeyedCodec<>("Effects", GROWTH_EFFECT_ARRAY_CODEC),
                    (settings, value) -> settings.effects = value == null ? EMPTY_EFFECTS : value,
                    settings -> settings.effects
            )
            .documentation("Level-based effect multipliers. Inheritance: explicit arrays replace the parent value.")
            .add()
            .build();

    private static final BuilderCodec<TalentPointSettings> TALENT_POINTS_CODEC = BuilderCodec.builder(
            TalentPointSettings.class,
            TalentPointSettings::new
    )
            .<Integer>append(
                    new KeyedCodec<>("PointsPerLevel", Codec.INTEGER),
                    (settings, value) -> settings.pointsPerLevel = value,
                    settings -> settings.pointsPerLevel
            )
            .documentation("Talent points granted on each level-up after level 1.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwLevelingConfig> CODEC = AssetBuilderCodec.builder(
            TwLevelingConfig.class,
            TwLevelingConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Role-scoped companion leveling config for XP gain, level curves, stat growth, and talent point grants.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation("Turns this section on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Priority used when multiple configs apply; higher values take precedence.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (asset, value) -> asset.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    asset -> asset.roleIds
            )
            .documentation("NPC role IDs this config applies to. Inheritance: explicit arrays replace the parent value.")
            .add()
            .<LevelSettings>append(
                    new KeyedCodec<>("Levels", LEVEL_SETTINGS_CODEC),
                    (asset, value) -> asset.levels = value == null ? new LevelSettings() : value,
                    asset -> asset.levels
            )
            .documentation("Level-curve settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<XpSourcesSettings>append(
                    new KeyedCodec<>("XpSources", XP_SOURCES_CODEC),
                    (asset, value) -> asset.xpSources = value == null ? new XpSourcesSettings() : value,
                    asset -> asset.xpSources
            )
            .documentation("XP source settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<StatGrowthSettings>append(
                    new KeyedCodec<>("StatGrowth", STAT_GROWTH_CODEC),
                    (asset, value) -> asset.statGrowth = value == null ? new StatGrowthSettings() : value,
                    asset -> asset.statGrowth
            )
            .documentation("Level-based stat growth settings. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .<TalentPointSettings>append(
                    new KeyedCodec<>("TalentPoints", TALENT_POINTS_CODEC),
                    (asset, value) -> asset.talentPoints = value == null ? new TalentPointSettings() : value,
                    asset -> asset.talentPoints
            )
            .documentation("Talent point grants on level-up. Inheritance: omitted section inherits from parent; when present, only explicitly defined nested fields override parent.")
            .add()
            .build();

    private static AssetStore<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwLevelingConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private LevelSettings levels = new LevelSettings();
    private XpSourcesSettings xpSources = new XpSourcesSettings();
    private StatGrowthSettings statGrowth = new StatGrowthSettings();
    private TalentPointSettings talentPoints = new TalentPointSettings();

    public static AssetStore<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwLevelingConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwLevelingConfig> getAssetMap() {
        AssetStore<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwLevelingConfig> assetMap = (DefaultAssetMap<String, TwLevelingConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    @Nullable
    public static TwLevelingConfig resolveForRole(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwLevelingConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return null;
        }
        Map<String, TwLevelingConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap);
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.get(roleId.trim().toLowerCase(Locale.ROOT));
    }

    @Nullable
    public static TwLevelingConfig resolveById(@Nullable String configId) {
        if (configId == null || configId.isBlank()) {
            return null;
        }
        DefaultAssetMap<String, TwLevelingConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwLevelingConfig> map = assetMap.getAssetMap();
        TwLevelingConfig direct = map.get(configId);
        if (direct != null) {
            return direct;
        }
        String normalized = configId.trim();
        for (TwLevelingConfig candidate : map.values()) {
            if (candidate == null || candidate.getId() == null) {
                continue;
            }
            if (candidate.getId().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, TwLevelingConfig> buildRoleCache(@Nullable DefaultAssetMap<String, TwLevelingConfig> assetMap) {
        Map<String, TwLevelingConfig> cache = new HashMap<>();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return cache;
        }
        for (TwLevelingConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                String normalized = roleId.trim().toLowerCase(Locale.ROOT);
                TwLevelingConfig existing = cache.get(normalized);
                if (shouldReplaceCandidate(candidate, existing)) {
                    cache.put(normalized, candidate);
                }
            }
        }
        return cache;
    }

    private static boolean shouldReplaceCandidate(@Nullable TwLevelingConfig candidate,
                                                  @Nullable TwLevelingConfig existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        if (candidate.getPriority() != existing.getPriority()) {
            return candidate.getPriority() > existing.getPriority();
        }
        return compareIds(candidate.getId(), existing.getId()) < 0;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwLevelingConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwLevelingConfig parent, @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwLevelingConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("Levels")) {
            levels = parent.levels;
        } else {
            inheritLevelSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "Levels"));
        }
        if (!explicitTopLevelKeys.contains("XpSources")) {
            xpSources = parent.xpSources;
        } else {
            inheritXpSourcesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "XpSources"));
        }
        if (!explicitTopLevelKeys.contains("StatGrowth")) {
            statGrowth = parent.statGrowth;
        } else {
            inheritStatGrowthSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "StatGrowth"));
        }
        if (!explicitTopLevelKeys.contains("TalentPoints")) {
            talentPoints = parent.talentPoints;
        } else {
            inheritTalentPointSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "TalentPoints"));
        }
    }

    private void inheritLevelSection(@Nonnull TwLevelingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (levels == null) {
            levels = parent.levels;
            return;
        }
        if (parent.levels == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("MaxLevel")) {
            levels.maxLevel = parent.levels.maxLevel;
        }
        if (!nestedExplicitKeys.contains("BaseXp")) {
            levels.baseXp = parent.levels.baseXp;
        }
        if (!nestedExplicitKeys.contains("GrowthFactor")) {
            levels.growthFactor = parent.levels.growthFactor;
        }
    }

    private void inheritXpSourcesSection(@Nonnull TwLevelingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (xpSources == null) {
            xpSources = parent.xpSources;
            return;
        }
        if (parent.xpSources == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Feed")) {
            xpSources.feed = parent.xpSources.feed;
        } else {
            inheritSimpleSourceSection(xpSources.feed, parent.xpSources.feed, nestedExplicitKeys, "Feed");
        }
        if (!nestedExplicitKeys.contains("Harvest")) {
            xpSources.harvest = parent.xpSources.harvest;
        } else {
            inheritSimpleSourceSection(xpSources.harvest, parent.xpSources.harvest, nestedExplicitKeys, "Harvest");
        }
        if (!nestedExplicitKeys.contains("Breeding")) {
            xpSources.breeding = parent.xpSources.breeding;
        } else {
            inheritSimpleSourceSection(xpSources.breeding, parent.xpSources.breeding, nestedExplicitKeys, "Breeding");
        }
        if (!nestedExplicitKeys.contains("Combat")) {
            xpSources.combat = parent.xpSources.combat;
        } else {
            inheritCombatSourceSection(parent, nestedExplicitKeys);
        }
    }

    private void inheritSimpleSourceSection(@Nullable SimpleXpSourceSettings child,
                                            @Nullable SimpleXpSourceSettings parent,
                                            @Nonnull Set<String> nestedExplicitKeys,
                                            @Nonnull String prefix) {
        if (child == null || parent == null) {
            return;
        }
        if (!nestedExplicitKeys.contains(prefix + ".Enabled")) {
            child.enabled = parent.enabled;
        }
        if (!nestedExplicitKeys.contains(prefix + ".FlatXp")) {
            child.flatXp = parent.flatXp;
        }
    }

    private void inheritCombatSourceSection(@Nonnull TwLevelingConfig parent,
                                            @Nonnull Set<String> nestedExplicitKeys) {
        if (xpSources == null) {
            return;
        }
        if (xpSources.combat == null) {
            xpSources.combat = parent.xpSources != null ? parent.xpSources.combat : null;
            return;
        }
        if (parent.xpSources == null || parent.xpSources.combat == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Combat.Enabled")) {
            xpSources.combat.enabled = parent.xpSources.combat.enabled;
        }
        if (!nestedExplicitKeys.contains("Combat.DamageDealtXpPerPoint")) {
            xpSources.combat.damageDealtXpPerPoint = parent.xpSources.combat.damageDealtXpPerPoint;
        }
        if (!nestedExplicitKeys.contains("Combat.DamageTakenXpPerPoint")) {
            xpSources.combat.damageTakenXpPerPoint = parent.xpSources.combat.damageTakenXpPerPoint;
        }
        if (!nestedExplicitKeys.contains("Combat.MinimumDamageEvent")) {
            xpSources.combat.minimumDamageEvent = parent.xpSources.combat.minimumDamageEvent;
        }
        if (!nestedExplicitKeys.contains("Combat.AwardVsPlayers")) {
            xpSources.combat.awardVsPlayers = parent.xpSources.combat.awardVsPlayers;
        }
        if (!nestedExplicitKeys.contains("Combat.AwardVsOwnedAllies")) {
            xpSources.combat.awardVsOwnedAllies = parent.xpSources.combat.awardVsOwnedAllies;
        }
    }

    private void inheritStatGrowthSection(@Nonnull TwLevelingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (statGrowth == null) {
            statGrowth = parent.statGrowth;
            return;
        }
        if (parent.statGrowth == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Effects")) {
            statGrowth.effects = parent.statGrowth.effects;
        }
    }

    private void inheritTalentPointSection(@Nonnull TwLevelingConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (talentPoints == null) {
            talentPoints = parent.talentPoints;
            return;
        }
        if (parent.talentPoints == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("PointsPerLevel")) {
            talentPoints.pointsPerLevel = parent.talentPoints.pointsPerLevel;
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    protected TwLevelingConfig() {
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    public LevelSettings getLevels() {
        return levels == null ? new LevelSettings() : levels;
    }

    public XpSourcesSettings getXpSources() {
        return xpSources == null ? new XpSourcesSettings() : xpSources;
    }

    public StatGrowthSettings getStatGrowth() {
        return statGrowth == null ? new StatGrowthSettings() : statGrowth;
    }

    public TalentPointSettings getTalentPoints() {
        return talentPoints == null ? new TalentPointSettings() : talentPoints;
    }

    /** Shared top-level level curve settings. */
    public static final class LevelSettings {
        private int maxLevel = 20;
        private double baseXp = 100.0;
        private double growthFactor = 1.18;

        public int getMaxLevel() {
            return Math.max(1, maxLevel);
        }

        public double getBaseXp() {
            return Double.isFinite(baseXp) && baseXp > 0.0 ? baseXp : 100.0;
        }

        public double getGrowthFactor() {
            return Double.isFinite(growthFactor) && growthFactor > 0.0 ? growthFactor : 1.18;
        }
    }

    /** XP source settings grouped by gameplay trigger. */
    public static final class XpSourcesSettings {
        private SimpleXpSourceSettings feed = new SimpleXpSourceSettings();
        private SimpleXpSourceSettings harvest = new SimpleXpSourceSettings();
        private SimpleXpSourceSettings breeding = new SimpleXpSourceSettings();
        private CombatXpSourceSettings combat = new CombatXpSourceSettings();

        public SimpleXpSourceSettings getFeed() {
            return feed == null ? new SimpleXpSourceSettings() : feed;
        }

        public SimpleXpSourceSettings getHarvest() {
            return harvest == null ? new SimpleXpSourceSettings() : harvest;
        }

        public SimpleXpSourceSettings getBreeding() {
            return breeding == null ? new SimpleXpSourceSettings() : breeding;
        }

        public CombatXpSourceSettings getCombat() {
            return combat == null ? new CombatXpSourceSettings() : combat;
        }
    }

    /** Flat XP source settings for feed, harvest, and breeding triggers. */
    public static class SimpleXpSourceSettings {
        private boolean enabled;
        private double flatXp;

        public boolean isEnabled() {
            return enabled;
        }

        public double getFlatXp() {
            return Double.isFinite(flatXp) && flatXp > 0.0 ? flatXp : 0.0;
        }
    }

    /** Combat XP settings for dealt and taken damage. */
    public static final class CombatXpSourceSettings {
        private boolean enabled;
        private double damageDealtXpPerPoint;
        private double damageTakenXpPerPoint;
        private double minimumDamageEvent = 1.0;
        private boolean awardVsPlayers;
        private boolean awardVsOwnedAllies;

        public boolean isEnabled() {
            return enabled;
        }

        public double getDamageDealtXpPerPoint() {
            return Double.isFinite(damageDealtXpPerPoint) && damageDealtXpPerPoint > 0.0 ? damageDealtXpPerPoint : 0.0;
        }

        public double getDamageTakenXpPerPoint() {
            return Double.isFinite(damageTakenXpPerPoint) && damageTakenXpPerPoint > 0.0 ? damageTakenXpPerPoint : 0.0;
        }

        public double getMinimumDamageEvent() {
            return Double.isFinite(minimumDamageEvent) && minimumDamageEvent > 0.0 ? minimumDamageEvent : 1.0;
        }

        public boolean isAwardVsPlayers() {
            return awardVsPlayers;
        }

        public boolean isAwardVsOwnedAllies() {
            return awardVsOwnedAllies;
        }
    }

    /** Level-based progression effect settings. */
    public static final class StatGrowthSettings {
        private GrowthEffect[] effects = EMPTY_EFFECTS;

        public GrowthEffect[] getEffects() {
            return effects == null ? EMPTY_EFFECTS : effects;
        }
    }

    /** One effect-key multiplier entry driven by level. */
    public static final class GrowthEffect {
        private String effectKey;
        private double perLevel;

        @Nullable
        public String getEffectKey() {
            if (effectKey == null || effectKey.isBlank()) {
                return null;
            }
            return effectKey;
        }

        public double getPerLevel() {
            return Double.isFinite(perLevel) ? perLevel : 0.0;
        }
    }

    /** Talent point grants tied to leveling. */
    public static final class TalentPointSettings {
        private int pointsPerLevel = 1;

        public int getPointsPerLevel() {
            return Math.max(0, pointsPerLevel);
        }
    }
}
