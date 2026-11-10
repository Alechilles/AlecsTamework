package com.alechilles.alecstamework.settings;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.progression.AnimalProgressionClock;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierRefreshService;
import com.alechilles.alecstamework.settings.mods.ModSettingsService;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.List;
import java.util.concurrent.Executors;
import javax.annotation.Nonnull;

/** Builds the plugin's {@link NetworkSettingsSync}: its thread, the six settings files and what a change applies. */
public final class NetworkSettingsRuntime {
    private NetworkSettingsRuntime() {
    }

    /** Creates and starts the sync. The caller closes it at shutdown. */
    @Nonnull
    public static NetworkSettingsSync start(@Nonnull Tamework plugin, @Nonnull ModSettingsService modSettings) {
        NetworkSettingsFiles files = new NetworkSettingsFiles(List.of(
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_MAIN,
                        TameworkSettingsStore.resolveGlobalSettingsFile(plugin), TameworkSettingsStore::normalize),
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_COMPANION_CARD,
                        CompanionCardSettingsStore.resolveFile(plugin),
                        raw -> CompanionCardSettingsStore.toJson(CompanionCardSettingsStore.parse(raw))),
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_COMPANION_HUD,
                        CompanionHudDefaultsStore.resolveFile(plugin),
                        raw -> CompanionHudDefaultsStore.toJson(CompanionHudDefaultsStore.parse(raw))),
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_DEPLOY_PLACES,
                        DeployPlacesStore.resolveFile(plugin),
                        raw -> DeployPlacesStore.toJson(DeployPlacesStore.parse(raw))),
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_NAME_FILTER,
                        NameFilterSettingsStore.resolveFile(plugin),
                        raw -> NameFilterSettingsStore.toJson(NameFilterSettingsStore.parse(raw))),
                new NetworkSettingsFiles.Section(NetworkSettingsLayer.SECTION_MOD_SETTINGS,
                        modSettings.savedValuesFile(), ModSettingsService::normalizeSavedValues)
        ), plugin.getLogger());
        NetworkSettingsSync sync = new NetworkSettingsSync(
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "tamework-network-settings");
                    thread.setDaemon(true);
                    return thread;
                }),
                () -> TameworkSettingsStore.resolveSettingsDirectory(plugin)
                        .resolve(NetworkSettingsLayer.CACHE_FILE_NAME).normalize(),
                files,
                () -> applyChangedSettings(plugin, modSettings),
                plugin.getLogger());
        sync.start();
        return sync;
    }

    /**
     * Makes settings that changed under the stores current: the same steps as a settings save and
     * the settings part of the config reload. Runs on the network settings thread; the only live
     * work is queued to each world's own thread.
     */
    private static void applyChangedSettings(@Nonnull Tamework plugin, @Nonnull ModSettingsService modSettings) {
        TameworkSettingsStore.invalidateRuntimeGlobalOverridesCache();
        NameFilterSettingsStore.invalidateCache();
        CompanionHudDefaultsStore.invalidateCache();
        CompanionCardSettingsStore.load(CompanionCardSettingsStore.resolveFile(plugin), plugin.getLogger());
        DeployPlacesStore.load(DeployPlacesStore.resolveFile(plugin), plugin.getLogger());
        ResolvedTameworkSettings settings = TameworkSettingsStore.loadRuntimeGlobalSettings();
        AnimalProgressionClock.get().onPolicyChanged(TwNeedsConfig.TickPolicySettings.of(
                TwNeedsConfig.TickPolicyMode.fromConfigValue(settings.needsTickPolicyMode()),
                settings.needsOwnerOfflineGraceHours(),
                settings.needsOwnerOfflineDecayMultiplier()));
        plugin.onRuntimeSettingsChanged();
        modSettings.reloadSavedValues();
        Universe universe = Universe.get();
        if (universe != null) {
            for (World world : universe.getWorlds().values()) {
                world.execute(() -> CompanionStatModifierRefreshService.refreshLoadedNpcStatModifiers(
                        world.getEntityStore().getStore()));
            }
        }
    }
}
