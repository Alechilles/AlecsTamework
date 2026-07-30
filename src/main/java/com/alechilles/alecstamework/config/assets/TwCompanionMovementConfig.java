package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped companion movement settings stored under Server/Tamework/CompanionMovement.
 */
public final class TwCompanionMovementConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCompanionMovementConfig>>,
        TwParentFallbackAsset<TwCompanionMovementConfig> {
    private static final AttachmentModifier[] EMPTY_ATTACHMENT_MODIFIERS = new AttachmentModifier[0];
    private static final ResolvedMovement NEUTRAL_MOVEMENT =
            new ResolvedMovement(1.0, 0.5, 2.0, List.of());

    public static final AssetBuilderCodec<String, TwCompanionMovementConfig> CODEC =
            TwCompanionMovementConfigCodec.CODEC;

    private static AssetStore<String, TwCompanionMovementConfig,
            DefaultAssetMap<String, TwCompanionMovementConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, ResolvedMovement> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private Double baseMoveSpeedMultiplier;
    private Double minMoveSpeedMultiplier;
    private Double maxMoveSpeedMultiplier;
    private AttachmentModifier[] attachmentModifiers;

    TwCompanionMovementConfig() {
    }

    public static AssetStore<String, TwCompanionMovementConfig,
            DefaultAssetMap<String, TwCompanionMovementConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCompanionMovementConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCompanionMovementConfig> getAssetMap() {
        AssetStore<String, TwCompanionMovementConfig, DefaultAssetMap<String, TwCompanionMovementConfig>> store =
                getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCompanionMovementConfig> assetMap =
                (DefaultAssetMap<String, TwCompanionMovementConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    /** Resolves one matching role config, or neutral movement settings when none matches. */
    @Nonnull
    public static ResolvedMovement resolveForRole(@Nullable String roleId) {
        String normalizedRoleId = normalizeKey(roleId);
        if (normalizedRoleId.isEmpty()) {
            return NEUTRAL_MOVEMENT;
        }
        DefaultAssetMap<String, TwCompanionMovementConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return NEUTRAL_MOVEMENT;
        }
        Map<String, ResolvedMovement> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap.getAssetMap().values());
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        return cache.getOrDefault(normalizedRoleId, NEUTRAL_MOVEMENT);
    }

    static ResolvedMovement resolveForRoleForTest(@Nonnull Collection<TwCompanionMovementConfig> configs,
                                                   @Nullable String roleId) {
        return buildRoleCache(configs).getOrDefault(normalizeKey(roleId), NEUTRAL_MOVEMENT);
    }

    @Nonnull
    private static Map<String, ResolvedMovement> buildRoleCache(
            @Nullable Collection<TwCompanionMovementConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Map.of();
        }
        Map<String, TwCompanionMovementConfig> selected = new HashMap<>();
        for (TwCompanionMovementConfig candidate : configs) {
            if (candidate == null || !candidate.enabled) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                String normalizedRoleId = normalizeKey(roleId);
                if (normalizedRoleId.isEmpty()) {
                    continue;
                }
                TwCompanionMovementConfig current = selected.get(normalizedRoleId);
                if (shouldReplaceCandidate(candidate, current)) {
                    selected.put(normalizedRoleId, candidate);
                }
            }
        }
        Map<String, ResolvedMovement> resolved = new HashMap<>();
        for (Map.Entry<String, TwCompanionMovementConfig> entry : selected.entrySet()) {
            resolved.put(entry.getKey(), entry.getValue().toResolvedMovement());
        }
        return Map.copyOf(resolved);
    }

    private static boolean shouldReplaceCandidate(@Nonnull TwCompanionMovementConfig candidate,
                                                  @Nullable TwCompanionMovementConfig current) {
        if (current == null) {
            return true;
        }
        if (candidate.priority != current.priority) {
            return candidate.priority > current.priority;
        }
        return normalizeKey(candidate.id).compareTo(normalizeKey(current.id)) < 0;
    }

    @Nonnull
    private ResolvedMovement toResolvedMovement() {
        return new ResolvedMovement(
                valueOrDefault(baseMoveSpeedMultiplier, 1.0),
                valueOrDefault(minMoveSpeedMultiplier, 0.5),
                valueOrDefault(maxMoveSpeedMultiplier, 2.0),
                List.of(getAttachmentModifiers())
        );
    }

    private static double valueOrDefault(@Nullable Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwCompanionMovementConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwCompanionMovementConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("BaseMoveSpeedMultiplier")) {
            baseMoveSpeedMultiplier = parent.baseMoveSpeedMultiplier;
        }
        if (!explicitTopLevelKeys.contains("MinMoveSpeedMultiplier")) {
            minMoveSpeedMultiplier = parent.minMoveSpeedMultiplier;
        }
        if (!explicitTopLevelKeys.contains("MaxMoveSpeedMultiplier")) {
            maxMoveSpeedMultiplier = parent.maxMoveSpeedMultiplier;
        }
        if (!explicitTopLevelKeys.contains("AttachmentModifiers")) {
            attachmentModifiers = parent.attachmentModifiers;
        }
    }

    @Override
    @Nullable
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }

    @Nonnull
    public String[] getRoleIds() {
        return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
    }

    @Nullable
    public Double getBaseMoveSpeedMultiplier() {
        return baseMoveSpeedMultiplier;
    }

    @Nullable
    public Double getMinMoveSpeedMultiplier() {
        return minMoveSpeedMultiplier;
    }

    @Nullable
    public Double getMaxMoveSpeedMultiplier() {
        return maxMoveSpeedMultiplier;
    }

    @Nonnull
    public AttachmentModifier[] getAttachmentModifiers() {
        return attachmentModifiers == null ? EMPTY_ATTACHMENT_MODIFIERS : attachmentModifiers;
    }

    void setId(@Nullable String value) { id = value; }
    void setData(@Nullable AssetExtraInfo.Data value) { data = value; }
    @Nullable
    AssetExtraInfo.Data getData() { return data; }
    void setEnabled(@Nullable Boolean value) { enabled = value == null || value; }
    void setPriority(@Nullable Integer value) { priority = value == null ? 0 : value; }
    void setRoleIds(@Nullable String[] value) { roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value; }
    void setBaseMoveSpeedMultiplier(@Nullable Double value) { baseMoveSpeedMultiplier = value; }
    void setMinMoveSpeedMultiplier(@Nullable Double value) { minMoveSpeedMultiplier = value; }
    void setMaxMoveSpeedMultiplier(@Nullable Double value) { maxMoveSpeedMultiplier = value; }
    void setAttachmentModifiers(@Nullable AttachmentModifier[] value) { attachmentModifiers = value; }

    private static String normalizeKey(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Immutable speed adjustment associated with one equipped attachment slot and its values. */
    public record AttachmentModifier(@Nullable String slot, @Nonnull List<String> values, double multiplier) {
        public AttachmentModifier {
            slot = slot == null ? null : slot.trim();
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** Immutable effective movement settings selected for a companion role. */
    public record ResolvedMovement(double baseMoveSpeedMultiplier,
                                   double minMoveSpeedMultiplier,
                                   double maxMoveSpeedMultiplier,
                                   @Nonnull List<AttachmentModifier> attachmentModifiers) {
        public ResolvedMovement {
            attachmentModifiers = attachmentModifiers == null ? List.of() : List.copyOf(attachmentModifiers);
        }
    }
}
