package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes icon-only item aliases during asset loading, never during a card refresh.
 * Existing PNGs stay in the item renderer; no Custom UI textures or item metadata are used.
 * Aliases remain valid until server shutdown so already-open cards survive config reloads.
 */
public final class CommandNpcPortraitAssets {
    private static final String SOURCE = "Alechilles:Alec's Tamework!";
    private final AtomicBoolean reconciling = new AtomicBoolean();

    public int reconcile(ItemFeatureRegistry registry) {
        if (registry == null || Item.getAssetStore() == null || !reconciling.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<Item> missing = missingAliases(registry.snapshot().values(),
                    Item.getAssetMap().getAssetMap());
            if (!missing.isEmpty()) {
                var result = Item.getAssetStore().loadAssets(SOURCE, missing);
                if (result.hasFailed()) {
                    throw new IllegalStateException("Portrait item load failed: "
                            + result.getFailedToLoadKeys().stream().limit(5).toList());
                }
                return result.getLoadedAssets().size();
            }
            return 0;
        } finally {
            reconciling.set(false);
        }
    }

    static List<Item> missingAliases(Collection<ItemFeatureConfig> configs, Map<String, Item> existing) {
        Set<String> icons = new TreeSet<>();
        for (ItemFeatureConfig config : configs) {
            if (config.getSpawnerIconOverridesByRole() != null) {
                config.getSpawnerIconOverridesByRole().values().forEach(overrides -> collect(icons, overrides));
            }
            if (config.getSpawnerIconOverrideGroups() != null) {
                for (var group : config.getSpawnerIconOverrideGroups()) {
                    if (group == null || group.getRoles().isEmpty()) continue;
                    add(icons, group.getIconDefault());
                    collect(icons, group.getOverrides());
                }
            }
        }
        Set<String> registered = new HashSet<>();
        for (Item item : existing.values()) registered.add(item.getIcon());
        List<Item> aliases = new ArrayList<>();
        for (String icon : icons) {
            if (registered.contains(icon)) continue;
            String id = "Tamework_Portrait_" + UUID.nameUUIDFromBytes(icon.getBytes(StandardCharsets.UTF_8))
                    .toString().replace("-", "");
            if (!existing.containsKey(id)) aliases.add(new PortraitItem(id, icon));
        }
        return aliases;
    }

    private static void collect(Set<String> icons, List<ItemFeatureConfig.SpawnerIconOverride> overrides) {
        if (overrides == null) return;
        for (var override : overrides) if (override != null) add(icons, override.getIcon());
    }

    private static void add(Set<String> icons, String icon) {
        if (icon != null && !icon.isBlank()) icons.add(icon.trim());
    }

    private static final class PortraitItem extends Item {
        private PortraitItem(String id, String icon) {
            super(Item.UNKNOWN);
            this.id = id;
            this.icon = icon;
            this.maxStack = 1;
            this.categories = new String[0];
            this.variant = true;
            this.interactions = Map.of();
        }
    }
}
