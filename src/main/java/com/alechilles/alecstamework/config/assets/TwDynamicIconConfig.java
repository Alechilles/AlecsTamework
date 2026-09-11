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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Role-scoped companion icon configuration stored under Server/Tamework/DynamicIcons.
 */
public final class TwDynamicIconConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwDynamicIconConfig>>,
        TwParentFallbackAsset<TwDynamicIconConfig> {
    private static final IconOverride[] EMPTY_ICON_OVERRIDES = new IconOverride[0];

    public static final AssetBuilderCodec<String, TwDynamicIconConfig> CODEC = TwDynamicIconConfigCodec.CODEC;

    private static AssetStore<String, TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Map<String, TwDynamicIconConfig> ROLE_CACHE = Map.of();

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private String iconDefault;
    private IconOverride[] iconOverrides = EMPTY_ICON_OVERRIDES;

    TwDynamicIconConfig() {
    }

    @Nullable
    public static AssetStore<String, TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwDynamicIconConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static DefaultAssetMap<String, TwDynamicIconConfig> getAssetMap() {
        AssetStore<String, TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwDynamicIconConfig> assetMap =
                (DefaultAssetMap<String, TwDynamicIconConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearRoleCache() {
        INHERITANCE_CACHE_DIRTY = true;
        ROLE_CACHE_DIRTY = true;
    }

    /** Resolves the configured icon for one role and its current model attachments. */
    @Nullable
    public static String resolveIcon(@Nullable String roleId, @Nullable Map<String, String> attachments) {
        String normalizedRoleId = normalizeRoleId(roleId);
        if (normalizedRoleId.isEmpty()) {
            return null;
        }
        DefaultAssetMap<String, TwDynamicIconConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Map<String, TwDynamicIconConfig> cache = ROLE_CACHE;
        if (ROLE_CACHE_DIRTY || cache == null) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY || ROLE_CACHE == null) {
                    ROLE_CACHE = buildRoleCache(assetMap.getAssetMap().values());
                    ROLE_CACHE_DIRTY = false;
                }
                cache = ROLE_CACHE;
            }
        }
        TwDynamicIconConfig config = cache.get(normalizedRoleId);
        return config == null ? null : config.resolve(attachments);
    }

    @Nullable
    static String resolveIconForTest(@Nullable Collection<TwDynamicIconConfig> configs,
                                     @Nullable String roleId,
                                     @Nullable Map<String, String> attachments) {
        TwDynamicIconConfig config = buildRoleCache(configs).get(normalizeRoleId(roleId));
        return config == null ? null : config.resolve(attachments);
    }

    @Nonnull
    private static Map<String, TwDynamicIconConfig> buildRoleCache(@Nullable Collection<TwDynamicIconConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Map.of();
        }
        Map<String, TwDynamicIconConfig> selected = new HashMap<>();
        for (TwDynamicIconConfig candidate : configs) {
            if (candidate == null || !candidate.enabled) {
                continue;
            }
            for (String roleId : candidate.getRoleIds()) {
                String normalizedRoleId = normalizeRoleId(roleId);
                if (normalizedRoleId.isEmpty()) {
                    continue;
                }
                TwDynamicIconConfig current = selected.get(normalizedRoleId);
                if (shouldReplaceCandidate(candidate, current)) {
                    selected.put(normalizedRoleId, candidate);
                }
            }
        }
        return Map.copyOf(selected);
    }

    private static boolean shouldReplaceCandidate(@Nonnull TwDynamicIconConfig candidate,
                                                  @Nullable TwDynamicIconConfig current) {
        if (current == null) {
            return true;
        }
        if (candidate.priority != current.priority) {
            return candidate.priority > current.priority;
        }
        return compareIds(candidate.id, current.id) < 0;
    }

    private static int compareIds(@Nullable String left, @Nullable String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareToIgnoreCase(safeRight);
    }

    @Nullable
    private String resolve(@Nullable Map<String, String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return getIconDefault();
        }
        for (IconOverride override : getIconOverrides()) {
            if (override != null && override.getIcon() != null && override.matches(attachments)) {
                return override.getIcon();
            }
        }
        return getIconDefault();
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwDynamicIconConfig> assetMap) {
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
    public void inheritMissingTopLevelFrom(@Nonnull TwDynamicIconConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("RoleIds")) roleIds = parent.roleIds;
        if (!explicitTopLevelKeys.contains("IconDefault")) iconDefault = parent.iconDefault;
        if (!explicitTopLevelKeys.contains("IconOverrides")) iconOverrides = parent.iconOverrides;
    }

    @Nullable
    @Override
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
    public String getIconDefault() {
        return nonBlankOrNull(iconDefault);
    }

    @Nonnull
    public IconOverride[] getIconOverrides() {
        return iconOverrides == null ? EMPTY_ICON_OVERRIDES : iconOverrides;
    }

    void setId(@Nullable String value) { id = value; }
    void setData(@Nullable AssetExtraInfo.Data value) { data = value; }
    @Nullable AssetExtraInfo.Data getData() { return data; }
    void setEnabled(@Nullable Boolean value) { enabled = value == null || value; }
    void setPriority(@Nullable Integer value) { priority = value == null ? 0 : value; }
    void setRoleIds(@Nullable String[] value) { roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value; }
    void setIconDefault(@Nullable String value) { iconDefault = value; }
    void setIconOverrides(@Nullable IconOverride[] value) {
        iconOverrides = value == null ? EMPTY_ICON_OVERRIDES : value;
    }

    @Nonnull
    private static String normalizeRoleId(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private static String nonBlankOrNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** One ordered attachment-specific icon override. */
    public static final class IconOverride {
        private String icon;
        private Map<String, String> attachments = Map.of();

        IconOverride() {
        }

        @Nullable
        public String getIcon() {
            return nonBlankOrNull(icon);
        }

        @Nonnull
        public Map<String, String> getAttachments() {
            return attachments == null ? Map.of() : attachments;
        }

        private boolean matches(@Nonnull Map<String, String> actualAttachments) {
            Map<String, String> requiredAttachments = getAttachments();
            if (requiredAttachments.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, String> entry : requiredAttachments.entrySet()) {
                String actualValue = actualAttachments.get(entry.getKey());
                if (actualValue == null || !actualValue.equals(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        void setIcon(@Nullable String value) { icon = value; }
        void setAttachments(@Nullable Map<String, String> value) { attachments = value == null ? Map.of() : value; }
    }
}
