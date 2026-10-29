package com.alechilles.alecstamework.settings.mods;

import com.alechilles.alecstamework.config.assets.TwModSetting;
import com.alechilles.alecstamework.config.assets.TwModSettingsConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.assetstore.AssetPack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns mod setting values: the declared catalog from {@link TwModSettingsConfig} assets, the saved
 * values, and the snapshot Patchwork reads at the start of each generation pass. Saving a change
 * asks Patchwork to regenerate so patches that depend on the setting update.
 *
 * <p>Thread use: Patchwork calls {@link #patchworkSnapshot()} from its generation thread; commands
 * and the settings page call the mutators. Saved values are guarded by this object's monitor and
 * published as immutable maps.</p>
 */
public final class ModSettingsService {
    /** What happened to patches after a save. */
    public enum ApplyOutcome {
        /** Patches regenerated and every changed asset is live. */
        APPLIED,
        /** Patches regenerated; some changed assets need a server restart. */
        RESTART_REQUIRED,
        /** Patches regenerated but some failed; /patchwork status has details. */
        PATCH_FAILURES,
        /** The value is saved but patches did not regenerate now (Patchwork busy or unavailable). */
        NOT_REGENERATED
    }

    /** Asks Patchwork to regenerate; completes with the outcome for the admin. */
    public interface Regenerator {
        @Nonnull
        CompletionStage<ApplyOutcome> regenerate();
    }

    private final ModSettingValueStore store;
    private final HytaleLogger logger;
    private volatile Regenerator regenerator;
    private volatile Map<String, JsonPrimitive> saved;
    private volatile ModSettingsCatalog catalog;
    private volatile boolean catalogDirty = true;
    @Nullable
    private volatile Map<String, JsonElement> lastPatchworkSnapshot;
    private final Supplier<ModSettingsCatalog> catalogLoader;

    public ModSettingsService(@Nonnull Path pluginDataDirectory, @Nonnull HytaleLogger logger) {
        this(new ModSettingValueStore(pluginDataDirectory, logger), logger, ModSettingsService::loadCatalogFromAssets);
    }

    ModSettingsService(@Nonnull ModSettingValueStore store,
                       @Nonnull HytaleLogger logger,
                       @Nonnull Supplier<ModSettingsCatalog> catalogLoader) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.catalogLoader = Objects.requireNonNull(catalogLoader, "catalogLoader");
        this.saved = Collections.unmodifiableMap(new LinkedHashMap<>(store.load()));
        this.catalog = ModSettingsCatalog.EMPTY;
    }

    public void setRegenerator(@Nullable Regenerator regenerator) {
        this.regenerator = regenerator;
    }

    @Nonnull
    public ModSettingsCatalog catalog() {
        if (catalogDirty) {
            synchronized (this) {
                if (catalogDirty) {
                    catalogDirty = false;
                    ModSettingsCatalog built = catalogLoader.get();
                    for (String duplicate : built.duplicateIds()) {
                        logger.at(Level.WARNING).log(
                                "Mod setting Id '%s' is declared more than once; only the first declaration is used.", duplicate);
                    }
                    catalog = built;
                }
            }
        }
        return catalog;
    }

    /** Current value of a declared setting: the saved value when it is still valid, else the default. */
    @Nullable
    public JsonPrimitive value(@Nonnull String id) {
        TwModSetting setting = catalog().setting(id);
        return setting == null ? null : effective(setting, saved.get(setting.getId()));
    }

    /** True when an admin saved a value for this setting. */
    public boolean isCustomized(@Nonnull String id) {
        TwModSetting setting = catalog().setting(id);
        return setting != null && saved.containsKey(setting.getId());
    }

    /**
     * Saves several changes at once and regenerates patches once. A null value resets that setting
     * to its default. Values must already be coerced by the setting; unknown Ids are ignored.
     */
    @Nonnull
    public CompletionStage<ApplyOutcome> apply(@Nonnull Map<String, JsonPrimitive> changes) {
        if (changes.isEmpty()) {
            return CompletableFuture.completedFuture(ApplyOutcome.APPLIED);
        }
        synchronized (this) {
            Map<String, JsonPrimitive> next = new LinkedHashMap<>(saved);
            for (Map.Entry<String, JsonPrimitive> change : changes.entrySet()) {
                TwModSetting setting = catalog().setting(change.getKey());
                if (setting == null) {
                    continue;
                }
                JsonPrimitive coerced = change.getValue() == null ? null : setting.coerce(change.getValue());
                if (coerced == null) {
                    next.remove(setting.getId());
                } else {
                    next.put(setting.getId(), coerced);
                }
            }
            try {
                store.save(next);
            } catch (IOException failure) {
                logger.at(Level.SEVERE).withCause(failure).log("Could not save mod settings to '%s'.", store.file());
                return CompletableFuture.failedFuture(failure);
            }
            saved = Collections.unmodifiableMap(next);
        }
        return regenerate();
    }

    /**
     * Values Patchwork uses for one generation pass: every declared setting's effective value, plus
     * saved values whose declarations are not loaded (yet), so admin choices apply during the
     * first pass at startup.
     */
    @Nonnull
    public Map<String, JsonElement> patchworkSnapshot() {
        Map<String, JsonElement> snapshot = currentSnapshot();
        lastPatchworkSnapshot = snapshot;
        return snapshot;
    }

    /**
     * Call after mod setting assets load or are removed. Regenerates when Patchwork's last pass
     * used values that no longer match, such as the startup pass that ran before declarations loaded.
     */
    public void onDeclarationsChanged() {
        catalogDirty = true;
        Map<String, JsonElement> previous = lastPatchworkSnapshot;
        if (previous != null && !previous.equals(currentSnapshot())) {
            regenerate().whenComplete((outcome, failure) -> {
                if (failure != null) {
                    logger.at(Level.WARNING).withCause(failure).log("Patchwork regeneration after mod setting changes failed.");
                } else if (outcome == ApplyOutcome.NOT_REGENERATED) {
                    logger.at(Level.INFO).log("Mod setting declarations changed; run /patchwork reload or restart to apply them.");
                }
            });
        }
    }

    @Nonnull
    private Map<String, JsonElement> currentSnapshot() {
        Map<String, JsonPrimitive> savedNow = saved;
        Map<String, JsonElement> snapshot = new LinkedHashMap<>();
        savedNow.forEach((id, value) -> snapshot.put(id, value.deepCopy()));
        for (TwModSetting setting : catalog().settingsById().values()) {
            snapshot.put(setting.getId(), effective(setting, savedNow.get(setting.getId())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    @Nonnull
    private CompletionStage<ApplyOutcome> regenerate() {
        Regenerator current = regenerator;
        if (current == null) {
            return CompletableFuture.completedFuture(ApplyOutcome.NOT_REGENERATED);
        }
        try {
            return current.regenerate();
        } catch (RuntimeException failure) {
            logger.at(Level.WARNING).withCause(failure).log("Could not request Patchwork regeneration for mod settings.");
            return CompletableFuture.completedFuture(ApplyOutcome.NOT_REGENERATED);
        }
    }

    @Nonnull
    private static JsonPrimitive effective(@Nonnull TwModSetting setting, @Nullable JsonPrimitive savedValue) {
        JsonPrimitive coerced = savedValue == null ? null : setting.coerce(savedValue);
        return coerced != null ? coerced : setting.defaultValue();
    }

    @Nonnull
    private static ModSettingsCatalog loadCatalogFromAssets() {
        DefaultAssetMap<String, TwModSettingsConfig> assetMap = TwModSettingsConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return ModSettingsCatalog.EMPTY;
        }
        AssetModule assetModule = AssetModule.get();
        List<ModSettingsCatalog.Source> sources = new ArrayList<>();
        for (TwModSettingsConfig group : assetMap.getAssetMap().values()) {
            if (group == null || group.getId() == null) {
                continue;
            }
            String modId = String.valueOf(assetMap.getAssetPack(group.getId()));
            sources.add(new ModSettingsCatalog.Source(modId, modName(assetModule, modId), group));
        }
        return ModSettingsCatalog.build(sources);
    }

    @Nonnull
    private static String modName(@Nullable AssetModule assetModule, @Nonnull String modId) {
        try {
            AssetPack pack = assetModule == null ? null : assetModule.getAssetPack(modId);
            String name = pack == null || pack.getManifest() == null ? null : pack.getManifest().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the identifier.
        }
        int separator = modId.indexOf(':');
        return separator >= 0 && separator < modId.length() - 1 ? modId.substring(separator + 1) : modId;
    }
}
