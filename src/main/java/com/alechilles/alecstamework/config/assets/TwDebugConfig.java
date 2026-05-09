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
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed defaults for Tamework debug command toggles.
 * Stored under {@code Server/Tamework/Debug}.
 */
public final class TwDebugConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwDebugConfig>>,
        TwParentFallbackAsset<TwDebugConfig> {
    private static final BuilderCodec<DebugCommandsSection> DEBUG_COMMANDS_CODEC = BuilderCodec.builder(
            DebugCommandsSection.class,
            DebugCommandsSection::new
    )
            .<Boolean>append(
                    new KeyedCodec<>("Hook", Codec.BOOLEAN),
                    (section, value) -> section.hook = value,
                    section -> section.hook
            )
            .documentation("Debug setting for interaction hook diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Spawner", Codec.BOOLEAN),
                    (section, value) -> section.spawner = value,
                    section -> section.spawner
            )
            .documentation("Debug setting for spawner diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Prompt", Codec.BOOLEAN),
                    (section, value) -> section.prompt = value,
                    section -> section.prompt
            )
            .documentation("Debug setting for prompt diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Ride", Codec.BOOLEAN),
                    (section, value) -> section.ride = value,
                    section -> section.ride
            )
            .documentation("Debug setting for mounted ride diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Despawn", Codec.BOOLEAN),
                    (section, value) -> section.despawn = value,
                    section -> section.despawn
            )
            .documentation("Debug setting for despawn diagnostics.")
            .add()
            .<String>append(
                    new KeyedCodec<>("DespawnRoleFilter", Codec.STRING),
                    (section, value) -> section.despawnRoleFilter = value,
                    section -> section.despawnRoleFilter
            )
            .documentation("Optional role filter used by despawn debug tools.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Lag", Codec.BOOLEAN),
                    (section, value) -> section.lag = value,
                    section -> section.lag
            )
            .documentation("Debug setting for lag instrumentation.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Coop", Codec.BOOLEAN),
                    (section, value) -> section.coop = value,
                    section -> section.coop
            )
            .documentation("Debug setting for coop diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("Breeding", Codec.BOOLEAN),
                    (section, value) -> section.breeding = value,
                    section -> section.breeding
            )
            .documentation("Breeding-related settings for this config.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("NeedsConsume", Codec.BOOLEAN),
                    (section, value) -> section.needsConsumeDiagnostics = value,
                    section -> section.needsConsumeDiagnostics
            )
            .documentation("Debug setting for needs consume diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("NeedsDamage", Codec.BOOLEAN),
                    (section, value) -> section.needsDamageDiagnostics = value,
                    section -> section.needsDamageDiagnostics
            )
            .documentation("Debug setting for needs damage and regen suppression diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("NeedsSeek", Codec.BOOLEAN),
                    (section, value) -> section.needsSeekDiagnostics = value,
                    section -> section.needsSeekDiagnostics
            )
            .documentation("Debug setting for needs seek target resolution diagnostics.")
            .add()
            .<Boolean>append(
                    new KeyedCodec<>("FlyingCompanion", Codec.BOOLEAN),
                    (section, value) -> section.flyingCompanion = value,
                    section -> section.flyingCompanion
            )
            .documentation("Debug setting for flying companion landing and grounded handoff diagnostics.")
            .add()
            .build();

    public static final AssetBuilderCodec<String, TwDebugConfig> CODEC = AssetBuilderCodec.builder(
            TwDebugConfig.class,
            TwDebugConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Default runtime values for Tamework debug command toggles.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value,
                    asset -> asset.enabled
            )
            .documentation("Enables this debug-default config.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value,
                    asset -> asset.priority
            )
            .documentation("Priority used when multiple debug configs are enabled.")
            .add()
            .<DebugCommandsSection>append(
                    new KeyedCodec<>("DebugCommands", DEBUG_COMMANDS_CODEC),
                    (asset, value) -> asset.debugCommands = value == null ? new DebugCommandsSection() : value,
                    asset -> asset.debugCommands
            )
            .documentation("Default toggle values for `/tw debug...` commands. Inheritance: omitted section inherits "
                    + "from parent; when present, missing nested fields inherit.")
            .add()
            .build();

    private static AssetStore<String, TwDebugConfig, DefaultAssetMap<String, TwDebugConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object CACHE_LOCK = new Object();
    private static volatile boolean CACHE_DIRTY = true;
    private static volatile TwDebugConfig ACTIVE_CONFIG;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private DebugCommandsSection debugCommands = new DebugCommandsSection();

    public static AssetStore<String, TwDebugConfig, DefaultAssetMap<String, TwDebugConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwDebugConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwDebugConfig> getAssetMap() {
        AssetStore<String, TwDebugConfig, DefaultAssetMap<String, TwDebugConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwDebugConfig> assetMap = (DefaultAssetMap<String, TwDebugConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearCache() {
        INHERITANCE_CACHE_DIRTY = true;
        CACHE_DIRTY = true;
    }

    @Nonnull
    public static TwDebugConfig resolveActive() {
        DefaultAssetMap<String, TwDebugConfig> assetMap = getAssetMap();
        if (assetMap == null) {
            return defaultConfig();
        }
        TwDebugConfig cached = ACTIVE_CONFIG;
        if (CACHE_DIRTY || cached == null) {
            synchronized (CACHE_LOCK) {
                if (CACHE_DIRTY || ACTIVE_CONFIG == null) {
                    ACTIVE_CONFIG = selectBest(assetMap);
                    CACHE_DIRTY = false;
                }
                cached = ACTIVE_CONFIG;
            }
        }
        return cached != null ? cached : defaultConfig();
    }

    @Nonnull
    public static TwDebugConfig defaultConfig() {
        return new TwDebugConfig();
    }

    @Nullable
    private static TwDebugConfig selectBest(@Nullable DefaultAssetMap<String, TwDebugConfig> assetMap) {
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        TwDebugConfig best = null;
        int bestPriority = Integer.MIN_VALUE;
        String bestId = null;
        for (TwDebugConfig candidate : assetMap.getAssetMap().values()) {
            if (candidate == null || !candidate.isEnabled()) {
                continue;
            }
            int candidatePriority = candidate.getPriority();
            if (best == null || candidatePriority > bestPriority) {
                best = candidate;
                bestPriority = candidatePriority;
                bestId = candidate.getId();
                continue;
            }
            if (candidatePriority == bestPriority) {
                String candidateId = candidate.getId();
                if (compareIds(candidateId, bestId) < 0) {
                    best = candidate;
                    bestId = candidateId;
                }
            }
        }
        return best;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    private static void ensureInheritanceFallbackApplied(@Nullable DefaultAssetMap<String, TwDebugConfig> assetMap) {
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

    protected TwDebugConfig() {
    }

    @Nullable
    public String getId() {
        return id;
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
    public void inheritMissingTopLevelFrom(@Nonnull TwDebugConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwDebugConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) {
            enabled = parent.enabled;
        }
        if (!explicitTopLevelKeys.contains("Priority")) {
            priority = parent.priority;
        }
        if (!explicitTopLevelKeys.contains("DebugCommands")) {
            debugCommands = parent.debugCommands;
        } else {
            inheritDebugCommandsSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "DebugCommands"));
        }
    }

    private void inheritDebugCommandsSection(@Nonnull TwDebugConfig parent,
                                             @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (debugCommands == null) {
            debugCommands = parent.debugCommands;
            return;
        }
        if (parent.debugCommands == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Hook")) {
            debugCommands.hook = parent.debugCommands.hook;
        }
        if (!nestedExplicitKeys.contains("Spawner")) {
            debugCommands.spawner = parent.debugCommands.spawner;
        }
        if (!nestedExplicitKeys.contains("Prompt")) {
            debugCommands.prompt = parent.debugCommands.prompt;
        }
        if (!nestedExplicitKeys.contains("Ride")) {
            debugCommands.ride = parent.debugCommands.ride;
        }
        if (!nestedExplicitKeys.contains("Despawn")) {
            debugCommands.despawn = parent.debugCommands.despawn;
        }
        if (!nestedExplicitKeys.contains("DespawnRoleFilter")) {
            debugCommands.despawnRoleFilter = parent.debugCommands.despawnRoleFilter;
        }
        if (!nestedExplicitKeys.contains("Lag")) {
            debugCommands.lag = parent.debugCommands.lag;
        }
        if (!nestedExplicitKeys.contains("Coop")) {
            debugCommands.coop = parent.debugCommands.coop;
        }
        if (!nestedExplicitKeys.contains("Breeding")) {
            debugCommands.breeding = parent.debugCommands.breeding;
        }
        if (!nestedExplicitKeys.contains("NeedsConsume")) {
            debugCommands.needsConsumeDiagnostics = parent.debugCommands.needsConsumeDiagnostics;
        }
        if (!nestedExplicitKeys.contains("NeedsDamage")) {
            debugCommands.needsDamageDiagnostics = parent.debugCommands.needsDamageDiagnostics;
        }
        if (!nestedExplicitKeys.contains("NeedsSeek")) {
            debugCommands.needsSeekDiagnostics = parent.debugCommands.needsSeekDiagnostics;
        }
        if (!nestedExplicitKeys.contains("FlyingCompanion")) {
            debugCommands.flyingCompanion = parent.debugCommands.flyingCompanion;
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

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    @Nonnull
    public DebugCommandsSection getDebugCommands() {
        return debugCommands == null ? new DebugCommandsSection() : debugCommands;
    }

    /** Values applied as defaults for `/tw debug...` commands. */
    public static final class DebugCommandsSection {
        private boolean hook;
        private boolean spawner;
        private boolean prompt;
        private boolean ride;
        private boolean despawn;
        private String despawnRoleFilter;
        private boolean lag;
        private boolean coop;
        private boolean breeding;
        private boolean needsConsumeDiagnostics;
        private boolean needsDamageDiagnostics;
        private boolean needsSeekDiagnostics;
        private boolean flyingCompanion;

        public boolean isHook() {
            return hook;
        }

        public boolean isSpawner() {
            return spawner;
        }

        public boolean isPrompt() {
            return prompt;
        }

        public boolean isRide() {
            return ride;
        }

        public boolean isDespawn() {
            return despawn;
        }

        @Nullable
        public String getDespawnRoleFilter() {
            return despawnRoleFilter;
        }

        public boolean isLag() {
            return lag;
        }

        public boolean isCoop() {
            return coop;
        }

        public boolean isBreeding() {
            return breeding;
        }

        public boolean isNeedsConsumeDiagnostics() {
            return needsConsumeDiagnostics;
        }

        public boolean isNeedsDamageDiagnostics() {
            return needsDamageDiagnostics;
        }

        public boolean isNeedsSeekDiagnostics() {
            return needsSeekDiagnostics;
        }

        public boolean isFlyingCompanion() {
            return flyingCompanion;
        }
    }
}


