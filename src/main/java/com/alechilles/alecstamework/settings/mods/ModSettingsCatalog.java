package com.alechilles.alecstamework.settings.mods;

import com.alechilles.alecstamework.config.assets.TwModSetting;
import com.alechilles.alecstamework.config.assets.TwModSettingsConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Immutable view of every declared mod setting, grouped the way the Mod Settings tab shows them:
 * mod (by manifest name), then group (by Order), then settings in declared order.
 */
public record ModSettingsCatalog(
        @Nonnull List<ModSection> mods,
        @Nonnull Map<String, TwModSetting> settingsById,
        @Nonnull List<String> duplicateIds
) {
    public static final ModSettingsCatalog EMPTY = new ModSettingsCatalog(List.of(), Map.of(), List.of());

    public ModSettingsCatalog {
        mods = List.copyOf(mods);
        settingsById = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(settingsById));
        duplicateIds = List.copyOf(duplicateIds);
    }

    /** One group asset plus the mod that shipped it. */
    public record Source(@Nonnull String modId, @Nonnull String modName, @Nonnull TwModSettingsConfig group) {
        public Source {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(modName, "modName");
            Objects.requireNonNull(group, "group");
        }
    }

    /** All groups one mod declared. */
    public record ModSection(@Nonnull String modId, @Nonnull String modName, @Nonnull List<Group> groups) {
        public ModSection {
            groups = List.copyOf(groups);
        }
    }

    /** One group heading and its settings in display order. */
    public record Group(@Nonnull String groupId, @Nullable String labelKey, int order, @Nonnull List<TwModSetting> settings) {
        public Group {
            settings = List.copyOf(settings);
        }
    }

    @Nullable
    public TwModSetting setting(@Nullable String id) {
        return id == null ? null : settingsById.get(id.trim());
    }

    /**
     * Builds the catalog. The first setting with a given Id wins in display order; later ones are
     * dropped and reported in {@link #duplicateIds()} so the author sees the clash.
     */
    @Nonnull
    public static ModSettingsCatalog build(@Nonnull Collection<Source> sources) {
        List<Source> sorted = new ArrayList<>(sources);
        sorted.sort(Comparator
                .comparing((Source source) -> source.modName().toLowerCase(Locale.ROOT))
                .thenComparing(Source::modId)
                .thenComparingInt(source -> source.group().getOrder())
                .thenComparing(source -> String.valueOf(source.group().getId())));

        Map<String, TwModSetting> byId = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        Map<String, List<Group>> groupsByMod = new LinkedHashMap<>();
        Map<String, String> modNames = new LinkedHashMap<>();
        for (Source source : sorted) {
            List<TwModSetting> kept = new ArrayList<>();
            for (TwModSetting setting : source.group().getSettings()) {
                String id = setting == null ? null : setting.getId();
                if (id == null) {
                    continue;
                }
                if (byId.putIfAbsent(id, setting) != null) {
                    duplicates.add(id);
                    continue;
                }
                kept.add(setting);
            }
            if (kept.isEmpty()) {
                continue;
            }
            modNames.putIfAbsent(source.modId(), source.modName());
            groupsByMod.computeIfAbsent(source.modId(), ignored -> new ArrayList<>())
                    .add(new Group(String.valueOf(source.group().getId()), source.group().getLabel(),
                            source.group().getOrder(), kept));
        }
        List<ModSection> mods = new ArrayList<>();
        groupsByMod.forEach((modId, groups) -> mods.add(new ModSection(modId, modNames.get(modId), groups)));
        return new ModSettingsCatalog(mods, byId, duplicates);
    }
}
