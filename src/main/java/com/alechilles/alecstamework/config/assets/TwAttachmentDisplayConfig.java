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
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Presentation config for resolving player-friendly names for model attachment selections.
 * Stored under Server/Tamework/AttachmentDisplays.
 */
public final class TwAttachmentDisplayConfig
        implements JsonAssetWithMap<String, DefaultAssetMap<String, TwAttachmentDisplayConfig>>,
        TwParentFallbackAsset<TwAttachmentDisplayConfig> {
    private static final Entry[] EMPTY_ENTRIES = new Entry[0];

    private static final BuilderCodec<AppliesTo> APPLIES_TO_CODEC = BuilderCodec.builder(
            AppliesTo.class,
            AppliesTo::new
    )
            .<String[]>append(
                    new KeyedCodec<>("RoleIds", Codec.STRING_ARRAY),
                    (appliesTo, value) -> appliesTo.roleIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    appliesTo -> appliesTo.roleIds
            )
            .documentation("Exact NPC role IDs this entry applies to. Empty arrays do not restrict the entry.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("ModelIds", Codec.STRING_ARRAY),
                    (appliesTo, value) -> appliesTo.modelIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    appliesTo -> appliesTo.modelIds
            )
            .documentation("Exact model asset IDs this entry applies to. Empty arrays do not restrict the entry.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("RoleNamespaces", Codec.STRING_ARRAY),
                    (appliesTo, value) -> appliesTo.roleNamespaces =
                            value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    appliesTo -> appliesTo.roleNamespaces
            )
            .documentation("Namespaces matched against namespaced role IDs such as ModId:RoleId.")
            .add()
            .<String[]>append(
                    new KeyedCodec<>("ModelNamespaces", Codec.STRING_ARRAY),
                    (appliesTo, value) -> appliesTo.modelNamespaces =
                            value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
                    appliesTo -> appliesTo.modelNamespaces
            )
            .documentation("Namespaces matched against namespaced model IDs such as ModId:ModelId.")
            .add()
            .build();

    private static final BuilderCodec<AttachmentSetDisplay> ATTACHMENT_SET_CODEC = BuilderCodec.builder(
            AttachmentSetDisplay.class,
            AttachmentSetDisplay::new
    )
            .<String>append(
                    new KeyedCodec<>("Label", Codec.STRING),
                    (set, value) -> set.label = value,
                    set -> set.label
            )
            .documentation("Player-facing label for the attachment set, such as Coat or Horns.")
            .add()
            .<Map<String, String>>append(
                    new KeyedCodec<>("Values", MapCodec.STRING_HASH_MAP_CODEC),
                    (set, value) -> set.values = value == null ? Map.of() : value,
                    set -> set.values
            )
            .documentation("Map of raw attachment value IDs to player-facing labels. Explicit maps replace parent values.")
            .add()
            .build();

    private static final MapCodec<AttachmentSetDisplay, Map<String, AttachmentSetDisplay>> ATTACHMENT_SETS_CODEC =
            new MapCodec<>(ATTACHMENT_SET_CODEC, HashMap::new);

    private static final BuilderCodec<Entry> ENTRY_CODEC = BuilderCodec.builder(
            Entry.class,
            Entry::new
    )
            .<String>append(
                    new KeyedCodec<>("Id", Codec.STRING),
                    (entry, value) -> entry.id = value,
                    entry -> entry.id
            )
            .documentation("Stable entry ID used only for deterministic tie-breaking and diagnostics.")
            .add()
            .<AppliesTo>append(
                    new KeyedCodec<>("AppliesTo", APPLIES_TO_CODEC),
                    (entry, value) -> entry.appliesTo = value == null ? new AppliesTo() : value,
                    entry -> entry.appliesTo
            )
            .documentation("Optional role/model filters. If omitted or empty, the entry is a global fallback.")
            .add()
            .<Map<String, AttachmentSetDisplay>>append(
                    new KeyedCodec<>("Sets", ATTACHMENT_SETS_CODEC),
                    (entry, value) -> entry.sets = value == null ? Map.of() : value,
                    entry -> entry.sets
            )
            .documentation("Attachment set display maps keyed by raw model attachment set ID.")
            .add()
            .build();

    private static final ArrayCodec<Entry> ENTRY_ARRAY_CODEC = new ArrayCodec<>(ENTRY_CODEC, Entry[]::new);

    public static final AssetBuilderCodec<String, TwAttachmentDisplayConfig> CODEC = AssetBuilderCodec.builder(
            TwAttachmentDisplayConfig.class,
            TwAttachmentDisplayConfig::new,
            Codec.STRING,
            (asset, id) -> asset.id = id,
            asset -> asset.id,
            (asset, data) -> asset.data = data,
            asset -> asset.data
    )
            .documentation("Config for player-facing model attachment labels used by spawner tooltips and UI.")
            .<Boolean>append(
                    new KeyedCodec<>("Enabled", Codec.BOOLEAN),
                    (asset, value) -> asset.enabled = value == null || value,
                    asset -> asset.enabled
            )
            .documentation("Turns this attachment display config on or off.")
            .add()
            .<Integer>append(
                    new KeyedCodec<>("Priority", Codec.INTEGER),
                    (asset, value) -> asset.priority = value == null ? 0 : value,
                    asset -> asset.priority
            )
            .documentation("Priority used when multiple display entries can label the same attachment; higher wins.")
            .add()
            .<Entry[]>append(
                    new KeyedCodec<>("Entries", ENTRY_ARRAY_CODEC),
                    (asset, value) -> asset.entries = value == null ? EMPTY_ENTRIES : value,
                    asset -> asset.entries
            )
            .documentation("Attachment display entries. Inheritance: explicit arrays replace parent value.")
            .add()
            .build();

    private static AssetStore<String, TwAttachmentDisplayConfig, DefaultAssetMap<String, TwAttachmentDisplayConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private int priority;
    private Entry[] entries = EMPTY_ENTRIES;

    public static AssetStore<String, TwAttachmentDisplayConfig, DefaultAssetMap<String, TwAttachmentDisplayConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwAttachmentDisplayConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static DefaultAssetMap<String, TwAttachmentDisplayConfig> getAssetMap() {
        AssetStore<String, TwAttachmentDisplayConfig, DefaultAssetMap<String, TwAttachmentDisplayConfig>> store =
                getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwAttachmentDisplayConfig> assetMap =
                (DefaultAssetMap<String, TwAttachmentDisplayConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    public static void clearCache() {
        clearInheritanceFallbackCache();
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwAttachmentDisplayConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwAttachmentDisplayConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("Priority")) priority = parent.priority;
        if (!explicitTopLevelKeys.contains("Entries")) entries = parent.entries;
    }

    TwAttachmentDisplayConfig() {
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

    public Entry[] getEntries() {
        return entries == null ? EMPTY_ENTRIES : entries;
    }

    @Nullable
    @Override
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    public static final class Entry {
        private String id;
        private AppliesTo appliesTo = new AppliesTo();
        private Map<String, AttachmentSetDisplay> sets = Map.of();

        Entry() {
        }

        @Nullable
        public String getId() {
            return id;
        }

        @Nonnull
        public AppliesTo getAppliesTo() {
            return appliesTo == null ? new AppliesTo() : appliesTo;
        }

        @Nonnull
        public Map<String, AttachmentSetDisplay> getSets() {
            return sets == null ? Map.of() : sets;
        }
    }

    public static final class AppliesTo {
        private String[] roleIds = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] modelIds = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] roleNamespaces = ArrayUtil.EMPTY_STRING_ARRAY;
        private String[] modelNamespaces = ArrayUtil.EMPTY_STRING_ARRAY;

        AppliesTo() {
        }

        public String[] getRoleIds() {
            return roleIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleIds;
        }

        public String[] getModelIds() {
            return modelIds == null ? ArrayUtil.EMPTY_STRING_ARRAY : modelIds;
        }

        public String[] getRoleNamespaces() {
            return roleNamespaces == null ? ArrayUtil.EMPTY_STRING_ARRAY : roleNamespaces;
        }

        public String[] getModelNamespaces() {
            return modelNamespaces == null ? ArrayUtil.EMPTY_STRING_ARRAY : modelNamespaces;
        }

        public boolean isGlobal() {
            return getRoleIds().length == 0
                    && getModelIds().length == 0
                    && getRoleNamespaces().length == 0
                    && getModelNamespaces().length == 0;
        }
    }

    public static final class AttachmentSetDisplay {
        private String label;
        private Map<String, String> values = Map.of();

        AttachmentSetDisplay() {
        }

        @Nullable
        public String getLabel() {
            return label;
        }

        @Nonnull
        public Map<String, String> getValues() {
            return values == null ? Map.of() : values;
        }
    }
}
