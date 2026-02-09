package com.alechilles.alecstamework;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.TameworkSettings;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.interactions.TameworkSpawnInteraction;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureStranger;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureWild;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyInteract;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyCaptureUntamed;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetTamed;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsTamed;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderFactory;
import com.hypixel.hytale.server.npc.instructions.Action;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/**
 * Main entry point for the Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {

    private static Tamework instance;
    private static final String SETTINGS_RESOURCE_PATH =
            "Server/Tamework/Tamework_Settings.json";
    private static final String SETTINGS_FILENAME = "Tamework_Settings";

    private ItemFeatureRegistry itemFeatureRegistry;
    private Config<TameworkSettings> settingsConfig;
    private TameworkSettings settings;

    private TranslationRegistry translationRegistry;
    private SpawnerFeatureHandler spawnerFeatureHandler;
    private boolean npcActionsRegistered;
    private boolean spawnerAssetsRegistered;
    private ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType;
    private ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        // Registry for item-level feature configs and spawn/capture behaviors.
        itemFeatureRegistry = new ItemFeatureRegistry();
        // Register the custom item interaction used by spawner items.
        Interaction.CODEC.register("TameworkSpawn", TameworkSpawnInteraction.class, TameworkSpawnInteraction.CODEC);
        itemFeatureRegistry.registerDefaults();
        registerSpawnerItemAssets();

        // Resolve settings paths and optional server-level overrides.
        Path settingsDir = getDataDirectory().resolve("Server").resolve("Tamework");
        settingsConfig = new Config<>(settingsDir, SETTINGS_FILENAME, TameworkSettings.CODEC);
        settings = loadSettings(settingsConfig, settingsDir);


        // Register components that persist owner and tamed state on NPCs.
        ownerComponentType = getEntityStoreRegistry().registerComponent(
                TameworkOwnerComponent.class,
                "TameworkOwner",
                TameworkOwnerComponent.CODEC
        );

        tamedComponentType = getEntityStoreRegistry().registerComponent(
                TameworkTamedComponent.class,
                "TameworkTamed",
                TameworkTamedComponent.CODEC
        );

        // Damage event is needed for owner damage filtering; avoid double-registration.
        try {
            getEntityStoreRegistry().registerEntityEventType(Damage.class);
        } catch (IllegalArgumentException ex) {
            getLogger().at(Level.INFO).log("Damage event type already registered; skipping registration.");
        }

        // Register damage filter system (configurable owner protection).
        getEntityStoreRegistry().registerSystem(
                new OwnerDamageFilterSystem(
                        () -> settings != null && settings.isBlockOwnerDamage(),
                        () -> settings != null && settings.isBlockAllPlayerDamageIfOwned(),
                        () -> settings != null && settings.isInvulnerableIfOwned(),
                        getLogger()
                )
        );

        // Load item feature configs from bundled defaults and mod overrides.
        int loaded = 0;
        loaded += loadSpawnerItemAssets();

        // Load translation entries from mods so messages can be localized.
        translationRegistry = new TranslationRegistry();
        int langLoaded = ModLanguageDiscovery.loadAll(translationRegistry, getLogger(), getDataDirectory());
        getLogger().at(Level.INFO).log("Tamework language entries loaded: " + langLoaded);

        // Core handler for capture/spawn flows.
        spawnerFeatureHandler = new SpawnerFeatureHandler(getLogger(), itemFeatureRegistry);

        // Register /tw commands if the server supports it.
        if (getCommandRegistry() != null) {
            getCommandRegistry().registerCommand(new TameworkCommandRoot());
        }

        // Global listener to handle spawner capture/spawn interactions.
        // Global listener to enforce owner-only interactions.
        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(translationRegistry, getLogger());
        getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                ownerInteractionListener::onPlayerInteract
        );
        getLogger().at(Level.INFO).log(
                "Tamework item feature configs loaded: " + loaded
                        + " (total: " + itemFeatureRegistry.snapshot().size() + ")"
        );

        // Register custom NPC action/sensor builders once NPCPlugin is available.
        registerNpcActionsIfReady();
    }

    @Override
    protected void start() {
        // Called when the plugin is enabled
        getLogger().at(Level.INFO).log("Alec's Tamework! has been enabled!");
    }

    @Override
    protected void shutdown() {
        // Called when the plugin is disabled
        getLogger().at(Level.INFO).log("Alec's Tamework! has been disabled!");
    }


    public ItemFeatureRegistry getItemFeatureRegistry() {
        return itemFeatureRegistry;
    }

    public static Tamework getInstance() {
        return instance;
    }

    public TranslationRegistry getTranslationRegistry() {
        return translationRegistry;
    }

    public int reloadItemFeatureConfigs() {
        if (itemFeatureRegistry == null) {
            return 0;
        }
        itemFeatureRegistry.clear();
        itemFeatureRegistry.registerDefaults();
        registerSpawnerItemAssets();
        int loaded = 0;
        loaded += loadSpawnerItemAssets();
        getLogger().at(Level.INFO).log(
                "Reloaded Tamework item feature configs: " + loaded
                        + " (total: " + itemFeatureRegistry.snapshot().size() + ")"
        );
        return loaded;
    }


    private void registerSpawnerItemAssets() {
        if (spawnerAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwSpawnerConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Items/Spawners")
                        .setCodec(TwSpawnerConfig.CODEC)
                        .setKeyFunction(TwSpawnerConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwSpawnerConfig.class, this::onSpawnerAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwSpawnerConfig.class, this::onSpawnerAssetsRemoved);
        spawnerAssetsRegistered = true;
    }

    private void onSpawnerAssetsLoaded(
            LoadedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        reloadItemFeatureConfigs();
    }

    private void onSpawnerAssetsRemoved(
            RemovedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        reloadItemFeatureConfigs();
    }

    private int loadSpawnerItemAssets() {
        if (itemFeatureRegistry == null) {
            return 0;
        }
        DefaultAssetMap<String, TwSpawnerConfig> assetMap = TwSpawnerConfig.getAssetMap();
        if (assetMap == null) {
            return 0;
        }
        int loaded = 0;
        for (TwSpawnerConfig asset : assetMap.getAssetMap().values()) {
            if (asset == null) {
                continue;
            }
            String emptyItemId = asset.getEmptyItemId();
            if (emptyItemId == null || emptyItemId.isBlank()) {
                continue;
            }
            itemFeatureRegistry.register(emptyItemId, asset.toItemFeatureConfig());
            loaded++;
        }
        return loaded;
    }

    // Load settings from Server/Tamework/Tamework_Settings.json and seed defaults if missing.
    private TameworkSettings loadSettings(Config<TameworkSettings> config, Path settingsDir) {
        if (settingsDir != null) {
            try {
                Files.createDirectories(settingsDir);
            } catch (Exception ex) {
                getLogger().at(Level.WARNING).withCause(ex)
                        .log("Failed to create settings directory: " + settingsDir);
            }
        }
        ensureDefaultSettingsFile(settingsDir);
        TameworkSettings loaded = null;
        try {
            loaded = config.load().join();
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).withCause(ex)
                    .log("Failed to load Tamework_Settings.json; using defaults.");
        }
        if (loaded == null) {
            loaded = new TameworkSettings();
        }
        return loaded;
    }

    private void ensureDefaultSettingsFile(Path settingsDir) {
        if (settingsDir == null) {
            return;
        }
        Path settingsFile = settingsDir.resolve(SETTINGS_FILENAME + ".json");
        if (Files.exists(settingsFile)) {
            return;
        }
        try (InputStream stream = getClassLoader().getResourceAsStream(SETTINGS_RESOURCE_PATH)) {
            if (stream != null) {
                Files.copy(stream, settingsFile);
                getLogger().at(Level.INFO).log("Seeded default Tamework settings: " + settingsFile);
                return;
            }
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).withCause(ex)
                    .log("Failed to seed default Tamework settings: " + settingsFile);
        }
        try {
            Files.writeString(
                    settingsFile,
                    "{\n  \"BlockOwnerDamage\": true,\n  \"BlockAllPlayerDamageIfOwned\": false,\n  \"InvulnerableIfOwned\": false\n}\n",
                    StandardCharsets.UTF_8
            );
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).withCause(ex)
                    .log("Failed to write fallback Tamework settings: " + settingsFile);
        }
    }

    public SpawnerFeatureHandler getSpawnerFeatureHandler() {
        return spawnerFeatureHandler;
    }

    public ComponentType<EntityStore, TameworkOwnerComponent> getOwnerComponentType() {
        return ownerComponentType;
    }

    public ComponentType<EntityStore, TameworkTamedComponent> getTamedComponentType() {
        return tamedComponentType;
    }

    // NPC action/sensor builders must be registered after NPCPlugin is ready.
    private void registerNpcActionsIfReady() {
        if (npcActionsRegistered) {
            return;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin != null) {
            getLogger().at(Level.INFO).log("Tamework NPC builder registration: NPCPlugin detected on setup.");
            registerNpcActions(npcPlugin);
            return;
        }
        getLogger().at(Level.INFO).log("Tamework NPC builder registration: NPCPlugin not ready, waiting for PluginSetupEvent.");
        if (getEventRegistry() != null) {
            getEventRegistry().registerGlobal(
                    PluginSetupEvent.class,
                    this::onPluginSetup
            );
        }
    }

    // Called when plugins are set up; used to detect NPCPlugin availability.
    private void onPluginSetup(PluginSetupEvent event) {
        if (npcActionsRegistered) {
            return;
        }
        if (event.getPlugin() instanceof NPCPlugin) {
            getLogger().at(Level.INFO).log("Tamework NPC builder registration: PluginSetupEvent received NPCPlugin.");
            registerNpcActions((NPCPlugin) event.getPlugin());
        }
    }

    // Register custom action/sensor builders and trigger NPC validation.
    private void registerNpcActions(NPCPlugin npcPlugin) {
        if (npcActionsRegistered || npcPlugin == null) {
            return;
        }
        BuilderFactory<Action> actionFactory = npcPlugin.getBuilderManager().getFactory(Action.class);
        if (actionFactory == null) {
            getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Action factory missing.");
        } else {
            getLogger().at(Level.INFO).log("Tamework NPC builder registration: Action factory ready.");
            actionFactory.add(BuilderActionTameworkCaptureOwner.BUILDER_ID, BuilderActionTameworkCaptureOwner::new);
            actionFactory.add(BuilderActionTameworkCaptureStranger.BUILDER_ID, BuilderActionTameworkCaptureStranger::new);
            actionFactory.add(BuilderActionTameworkCaptureWild.BUILDER_ID, BuilderActionTameworkCaptureWild::new);
            actionFactory.add(BuilderActionTameworkDenyInteract.BUILDER_ID, BuilderActionTameworkDenyInteract::new);
            actionFactory.add(BuilderActionTameworkDenyCaptureUntamed.BUILDER_ID, BuilderActionTameworkDenyCaptureUntamed::new);
            actionFactory.add(BuilderActionTameworkSetTamed.BUILDER_ID, BuilderActionTameworkSetTamed::new);
            actionFactory.add(BuilderActionTameworkSetOwner.BUILDER_ID, BuilderActionTameworkSetOwner::new);
        }

        BuilderFactory<Sensor> sensorFactory = npcPlugin.getBuilderManager().getFactory(Sensor.class);
        if (sensorFactory == null) {
            getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Sensor factory missing.");
        } else {
            getLogger().at(Level.INFO).log("Tamework NPC builder registration: Sensor factory ready.");
            sensorFactory.add(BuilderSensorTameworkIsOwner.BUILDER_ID, BuilderSensorTameworkIsOwner::new);
            sensorFactory.add(BuilderSensorTameworkHasOwner.BUILDER_ID, BuilderSensorTameworkHasOwner::new);
            sensorFactory.add(BuilderSensorTameworkIsTamed.BUILDER_ID, BuilderSensorTameworkIsTamed::new);
        }

        npcActionsRegistered = true;
        getLogger().at(Level.INFO).log("Registered Tamework NPC capture actions.");
        try {
            if (npcPlugin.getBuilderManager() == null) {
                getLogger().at(Level.WARNING).log("NPC builder manager unavailable; skipping validation trigger.");
                return;
            }
            npcPlugin.getBuilderManager().getAllBuilders()
                    .forEach((index, info) -> npcPlugin.forceValidation(index));
            getLogger().at(Level.INFO).log("Triggered NPC validation after registering Tamework builders.");
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).withCause(ex)
                    .log("Failed to revalidate NPC assets after registering Tamework builders.");
        }
    }
}


