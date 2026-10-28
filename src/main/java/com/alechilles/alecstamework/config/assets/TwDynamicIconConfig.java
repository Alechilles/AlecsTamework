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
    private static final TwAssetInheritanceGate INHERITANCE_GATE = new TwAssetInheritanceGate();
    private static final Object ROLE_CACHE_LOCK = new Object();
    private static volatile boolean ROLE_CACHE_DIRTY = true;
    private static volatile Caches CACHES = new Caches(Map.of(), Map.of());

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private String iconDefault;
    private String portraitDefault;
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
        INHERITANCE_GATE.markDirty();
        ROLE_CACHE_DIRTY = true;
    }

    /** Resolves the configured icon for one role and its current model attachments. */
    @Nullable
    public static String resolveIcon(@Nullable String roleId, @Nullable Map<String, String> attachments) {
        String normalizedRoleId = TwConfigLookup.normalizeRoleId(roleId);
        if (normalizedRoleId.isEmpty()) {
            return null;
        }
        Caches caches = currentCaches();
        TwDynamicIconConfig config = caches == null ? null : caches.roles().get(normalizedRoleId);
        return config == null ? null : config.resolve(attachments);
    }

    /**
     * Returns the high-resolution UI portrait paired with a resolved icon, or null when none is configured.
     * The path is relative to {@code Common/UI/Custom}; the 64x64 item icon stays the fallback.
     */
    @Nullable
    public static String resolvePortrait(@Nullable String icon) {
        if (icon == null || icon.isBlank()) {
            return null;
        }
        Caches caches = currentCaches();
        return caches == null ? null : caches.portraits().get(icon);
    }

    @Nullable
    private static Caches currentCaches() {
        DefaultAssetMap<String, TwDynamicIconConfig> assetMap = getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return null;
        }
        Caches caches = CACHES;
        if (ROLE_CACHE_DIRTY) {
            synchronized (ROLE_CACHE_LOCK) {
                if (ROLE_CACHE_DIRTY) {
                    CACHES = buildCaches(assetMap.getAssetMap().values());
                    ROLE_CACHE_DIRTY = false;
                }
                caches = CACHES;
            }
        }
        return caches;
    }

    @Nullable
    static String resolveIconForTest(@Nullable Collection<TwDynamicIconConfig> configs,
                                     @Nullable String roleId,
                                     @Nullable Map<String, String> attachments) {
        TwDynamicIconConfig config = buildRoleCache(configs).get(TwConfigLookup.normalizeRoleId(roleId));
        return config == null ? null : config.resolve(attachments);
    }

    @Nullable
    static String resolvePortraitForTest(@Nullable Collection<TwDynamicIconConfig> configs, @Nullable String icon) {
        return buildCaches(configs).portraits().get(icon);
    }

    @Nonnull
    private static Caches buildCaches(@Nullable Collection<TwDynamicIconConfig> configs) {
        Map<String, TwDynamicIconConfig> roles = buildRoleCache(configs);
        // Panel cards only carry the resolved icon path, so portraits are looked up by that icon.
        // Only role-winning configs contribute, and the first mapping for an icon wins.
        Map<String, String> portraits = new HashMap<>();
        for (TwDynamicIconConfig config : roles.values()) {
            putPortrait(portraits, config.getIconDefault(), config.getPortraitDefault());
            for (IconOverride override : config.getIconOverrides()) {
                if (override != null) putPortrait(portraits, override.getIcon(), override.getPortrait());
            }
        }
        return new Caches(roles, Map.copyOf(portraits));
    }

    private static void putPortrait(Map<String, String> portraits, @Nullable String icon, @Nullable String portrait) {
        if (icon != null && portrait != null) portraits.putIfAbsent(icon, portrait);
    }

    @Nonnull
    private static Map<String, TwDynamicIconConfig> buildRoleCache(@Nullable Collection<TwDynamicIconConfig> configs) {
        if (configs == null || configs.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(TwConfigLookup.buildRoleIndex(
                configs,
                TwDynamicIconConfig::isEnabled,
                TwDynamicIconConfig::getRoleIds,
                TwDynamicIconConfig::getPriority,
                TwDynamicIconConfig::getId
        ));
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
        INHERITANCE_GATE.repairIfDirty(assetMap);
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
        if (!explicitTopLevelKeys.contains("PortraitDefault")) portraitDefault = parent.portraitDefault;
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

    @Nullable
    public String getPortraitDefault() {
        return nonBlankOrNull(portraitDefault);
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
    void setPortraitDefault(@Nullable String value) { portraitDefault = value; }
    void setIconOverrides(@Nullable IconOverride[] value) {
        iconOverrides = value == null ? EMPTY_ICON_OVERRIDES : value;
    }

    @Nullable
    private static String nonBlankOrNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Role winners and their icon-to-portrait pairs, swapped together on reload. */
    private record Caches(Map<String, TwDynamicIconConfig> roles, Map<String, String> portraits) {
    }

    /** One ordered attachment-specific icon override. */
    public static final class IconOverride {
        private String icon;
        private String portrait;
        private Map<String, String> attachments = Map.of();

        IconOverride() {
        }

        @Nullable
        public String getIcon() {
            return nonBlankOrNull(icon);
        }

        @Nullable
        public String getPortrait() {
            return nonBlankOrNull(portrait);
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
        void setPortrait(@Nullable String value) { portrait = value; }
        void setAttachments(@Nullable Map<String, String> value) { attachments = value == null ? Map.of() : value; }
    }
}
