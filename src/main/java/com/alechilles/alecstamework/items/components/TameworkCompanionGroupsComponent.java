package com.alechilles.alecstamework.items.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import javax.annotation.Nonnull;

/** Player-saved organization preferences. These tags never confer companion ownership or command authority. */
public final class TameworkCompanionGroupsComponent implements Component<EntityStore> {
    public static final BuilderCodec<TameworkCompanionGroupsComponent> CODEC = BuilderCodec
            .builder(TameworkCompanionGroupsComponent.class, TameworkCompanionGroupsComponent::new)
            .append(new KeyedCodec<>("Definitions", Codec.STRING), (c, v) -> c.definitions = v, c -> c.definitions).add()
            .append(new KeyedCodec<>("Memberships", new MapCodec<>(Codec.STRING_ARRAY, LinkedHashMap::new)),
                    (c, v) -> c.memberships = copy(v), c -> copy(c.memberships)).add()
            .append(new KeyedCodec<>("ImportedTools", Codec.STRING_ARRAY),
                    (c, v) -> c.importedTools = new LinkedHashSet<>(Arrays.asList(v)),
                    c -> c.importedTools.toArray(String[]::new)).add().build();
    private static ComponentType<EntityStore, TameworkCompanionGroupsComponent> type;
    private String definitions = "";
    private Map<String, String[]> memberships = new LinkedHashMap<>();
    private Set<String> importedTools = new LinkedHashSet<>();

    public static void register(Tamework plugin) {
        type = plugin.getEntityStoreRegistry().registerComponent(TameworkCompanionGroupsComponent.class,
                "TameworkCompanionGroups", CODEC);
    }

    @javax.annotation.Nullable
    public static ComponentType<EntityStore, TameworkCompanionGroupsComponent> getComponentType() { return type; }
    public String definitions() { return definitions; }
    public boolean imported(String toolId) { return importedTools.contains(toolId); }
    public void markImported(String toolId) { importedTools.add(toolId); }
    public void definitions(String value) { definitions = value == null ? "" : value; }

    /** Merges pre-profile UUID preferences when a stable profile becomes available. */
    public List<String> groups(String key, String fallback) {
        Set<String> result = new LinkedHashSet<>(Arrays.asList(memberships.getOrDefault(key, new String[0])));
        if (fallback != null) result.addAll(Arrays.asList(memberships.getOrDefault(fallback, new String[0])));
        return List.copyOf(result);
    }

    public void groups(String key, String fallback, Collection<String> groups) {
        if (fallback != null && !fallback.equals(key)) memberships.remove(fallback);
        if (groups.isEmpty()) memberships.remove(key);
        else memberships.put(key, new LinkedHashSet<>(groups).toArray(String[]::new));
    }

    public Set<String> members(String groupId) {
        Set<String> result = new LinkedHashSet<>();
        memberships.forEach((key, values) -> { if (Arrays.asList(values).contains(groupId)) result.add(key); });
        return Set.copyOf(result);
    }

    public void retainGroups(Set<String> validIds) {
        memberships.replaceAll((key, values) -> Arrays.stream(values).filter(validIds::contains).toArray(String[]::new));
        memberships.values().removeIf(values -> values.length == 0);
    }

    @Override @Nonnull public TameworkCompanionGroupsComponent clone() {
        var result = new TameworkCompanionGroupsComponent();
        result.definitions = definitions;
        result.memberships = copy(memberships);
        result.importedTools = new LinkedHashSet<>(importedTools);
        return result;
    }

    private static Map<String, String[]> copy(Map<String, String[]> source) {
        Map<String, String[]> result = new LinkedHashMap<>();
        source.forEach((key, values) -> result.put(key, values.clone()));
        return result;
    }
}
