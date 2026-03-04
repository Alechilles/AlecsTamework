package com.alechilles.alecstamework;

import java.util.logging.Level;

import javax.annotation.Nonnull;

import com.alechilles.alecstamework.assets.TameworkAssetPackCoordinator;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.damage.TraitDamageModifierSystem;
import com.alechilles.alecstamework.interactions.TameworkCommandInteraction;
import com.alechilles.alecstamework.interactions.TameworkNameNpcInteraction;
import com.alechilles.alecstamework.interactions.TameworkSpawnInteraction;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CoopFeatureHandler;
import com.alechilles.alecstamework.items.NamingFeatureHandler;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureStranger;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkCaptureWild;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyInteract;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkDenyCaptureUntamed;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkHarvestDrop;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkInteract;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkInteractPrompt;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkNeedsResourceConsume;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetOwner;
import com.alechilles.alecstamework.npc.actions.BuilderActionTameworkSetTamed;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.filters.builders.BuilderEntityFilterTameworkAttitudeFromTargetSlot;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHook;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsOwner;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsTamed;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkLifeStage;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedBelow;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.alechilles.alecstamework.npc.systems.CompanionProgressionBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionPassiveBreedingSystem;
import com.alechilles.alecstamework.npc.systems.CompanionLifeStageResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionAttachmentSyncSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDespawnProtectionSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CommandNpcRelocationOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionNeedsSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitStatSyncSystem;
import com.alechilles.alecstamework.npc.systems.NpcDebugDisplayResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.NpcNamePersistenceSystem;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.asset.builder.BuilderFactory;
import com.hypixel.hytale.server.npc.corecomponents.IEntityFilter;
import com.hypixel.hytale.server.npc.instructions.Action;
import com.hypixel.hytale.server.npc.instructions.Sensor;

/**
 * Main entry point for the Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {

    private static Tamework instance;

    private ItemFeatureRegistry itemFeatureRegistry;
    private NameItemRegistry nameItemRegistry;
    private CommandItemRegistry commandItemRegistry;
    private TameworkAssetPackCoordinator assetPackCoordinator;

    private TranslationRegistry translationRegistry;
    private SpawnerFeatureHandler spawnerFeatureHandler;
    private NamingFeatureHandler namingFeatureHandler;
    private CommandItemFeatureHandler commandItemFeatureHandler;
    private CoopFeatureHandler coopFeatureHandler;
    private CommandNpcRelocationService commandNpcRelocationService;
    private CommandLinkedNpcCaptureService commandLinkedNpcCaptureService;
    private CommandLinkedNpcDeathService commandLinkedNpcDeathService;
    private boolean npcActionsRegistered;
    private boolean globalAssetsRegistered;
    private boolean spawnerAssetsRegistered;
    private boolean namingAssetsRegistered;
    private boolean commandAssetsRegistered;
    private boolean interactionAssetsRegistered;
    private boolean coopAssetsRegistered;
    private boolean happinessAssetsRegistered;
    private boolean needsAssetsRegistered;
    private boolean breedingAssetsRegistered;
    private boolean traitAssetsRegistered;
    private String lastGlobalConfigWarningKey;
    private ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType;
    private ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType;
    private ComponentType<EntityStore, TameworkHookComponent> hookComponentType;
    private ComponentType<EntityStore, TameworkNpcNameComponent> npcNameComponentType;
    private ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksComponentType;
    private ComponentType<EntityStore, TameworkHappinessComponent> happinessComponentType;
    private ComponentType<EntityStore, TameworkNeedsComponent> needsComponentType;
    private ComponentType<EntityStore, TameworkBreedingComponent> breedingComponentType;
    private ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType;
    private ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsComponentType;
    private ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType;
    private volatile boolean debugHookLogs;
    private volatile boolean debugSpawnerLogs;
    private volatile boolean debugPromptLogs;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        itemFeatureRegistry = new ItemFeatureRegistry();
        nameItemRegistry = new NameItemRegistry();
        commandItemRegistry = new CommandItemRegistry();
        assetPackCoordinator = new TameworkAssetPackCoordinator(this);
        assetPackCoordinator.registerEarlyAssetPackOrderingHook();
        // Register the custom item interaction used by spawner items.
        Interaction.CODEC.register("TameworkSpawn", TameworkSpawnInteraction.class, TameworkSpawnInteraction.CODEC);
        // Register the custom item interaction used by naming items.
        Interaction.CODEC.register("TameworkNameNpc", TameworkNameNpcInteraction.class, TameworkNameNpcInteraction.CODEC);
        // Register the custom item interaction used by command items.
        Interaction.CODEC.register("TameworkCommand", TameworkCommandInteraction.class, TameworkCommandInteraction.CODEC);
        itemFeatureRegistry.registerDefaults();
        registerGlobalConfigAssets();
        registerCoopAssets();
        registerSpawnerItemAssets();
        registerNamingItemAssets();
        registerCommandItemAssets();
        registerInteractionAssets();
        registerHappinessAssets();
        registerNeedsAssets();
        registerBreedingAssets();
        registerTraitAssets();

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

        hookComponentType = getEntityStoreRegistry().registerComponent(
                TameworkHookComponent.class,
                "TameworkHook",
                TameworkHookComponent.CODEC
        );

        npcNameComponentType = getEntityStoreRegistry().registerComponent(
                TameworkNpcNameComponent.class,
                "TameworkNpcName",
                TameworkNpcNameComponent.CODEC
        );

        commandLinksComponentType = getEntityStoreRegistry().registerComponent(
                TameworkCommandLinksComponent.class,
                "TameworkCommandLinks",
                TameworkCommandLinksComponent.CODEC
        );

        happinessComponentType = getEntityStoreRegistry().registerComponent(
                TameworkHappinessComponent.class,
                "TameworkHappiness",
                TameworkHappinessComponent.CODEC
        );

        needsComponentType = getEntityStoreRegistry().registerComponent(
                TameworkNeedsComponent.class,
                "TameworkNeeds",
                TameworkNeedsComponent.CODEC
        );

        breedingComponentType = getEntityStoreRegistry().registerComponent(
                TameworkBreedingComponent.class,
                "TameworkBreeding",
                TameworkBreedingComponent.CODEC
        );

        traitsComponentType = getEntityStoreRegistry().registerComponent(
                TameworkTraitsComponent.class,
                "TameworkTraits",
                TameworkTraitsComponent.CODEC
        );

        attachmentsComponentType = getEntityStoreRegistry().registerComponent(
                TameworkAttachmentsComponent.class,
                "TameworkAttachments",
                TameworkAttachmentsComponent.CODEC
        );

        lifeStageComponentType = getEntityStoreRegistry().registerComponent(
                TameworkLifeStageComponent.class,
                "TameworkLifeStage",
                TameworkLifeStageComponent.CODEC
        );

        getEntityStoreRegistry().registerSystem(
                new NpcNamePersistenceSystem(npcNameComponentType, NPCEntity.getComponentType())
        );
        getEntityStoreRegistry().registerSystem(
                new NpcDebugDisplayResumeOnLoadSystem(NPCEntity.getComponentType())
        );
        getEntityStoreRegistry().registerSystem(
                new CompanionTraitStatSyncSystem(
                        NPCEntity.getComponentType(),
                        EntityStatMap.getComponentType(),
                        traitsComponentType
                )
        );
        getEntityStoreRegistry().registerSystem(
                new CompanionTraitBootstrapOnLoadSystem(NPCEntity.getComponentType())
        );
        getEntityStoreRegistry().registerSystem(
                new CompanionProgressionBootstrapOnLoadSystem(NPCEntity.getComponentType(), tamedComponentType)
        );
        getEntityStoreRegistry().registerSystem(
                new CompanionLifeStageResumeOnLoadSystem(NPCEntity.getComponentType(), lifeStageComponentType)
        );
        getEntityStoreRegistry().registerSystem(new CompanionAttachmentSyncSystem());
        getEntityStoreRegistry().registerSystem(new CompanionDespawnProtectionSystem());
        getEntityStoreRegistry().registerSystem(new CompanionNeedsSystem());
        getEntityStoreRegistry().registerSystem(new CompanionPassiveBreedingSystem());
        commandNpcRelocationService = new CommandNpcRelocationService();
        commandLinkedNpcCaptureService = new CommandLinkedNpcCaptureService(
                getDataDirectory().resolve("CommandLinkedNpcCaptures.dat")
        );
        commandLinkedNpcDeathService = new CommandLinkedNpcDeathService(
                getDataDirectory().resolve("CommandLinkedNpcDeaths.dat")
        );
        getEntityStoreRegistry().registerSystem(
                new CommandNpcRelocationOnLoadSystem(commandNpcRelocationService, commandLinkedNpcDeathService)
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
                        () -> getGlobalConfig().isBlockOwnerDamage(),
                        () -> getGlobalConfig().isBlockAllPlayerDamageIfOwned(),
                        () -> getGlobalConfig().isInvulnerableIfOwned(),
                        getLogger()
                )
        );
        getEntityStoreRegistry().registerSystem(new TraitDamageModifierSystem());

        // Load item feature configs from bundled defaults and mod overrides.
        int loadedSpawner = loadSpawnerItemAssets();
        int loadedNaming = loadNameItemAssets();
        int loadedCommands = loadCommandItemAssets();

        // Load translation entries from mods so messages can be localized.
        translationRegistry = new TranslationRegistry();
        int langLoaded = ModLanguageDiscovery.loadAll(translationRegistry, getLogger(), getDataDirectory());
        getLogger().at(Level.INFO).log("Tamework language entries loaded: " + langLoaded);

        // Core handler for capture/spawn flows.
        spawnerFeatureHandler = new SpawnerFeatureHandler(getLogger(), itemFeatureRegistry, commandLinkedNpcCaptureService);
        // Core handler for naming flows.
        namingFeatureHandler = new NamingFeatureHandler(nameItemRegistry, translationRegistry);
        // Core handler for command-item linking and dispatch.
        commandItemFeatureHandler = new CommandItemFeatureHandler(
                commandItemRegistry,
                commandNpcRelocationService,
                commandLinkedNpcDeathService,
                commandLinkedNpcCaptureService
        );
        // Core handler for coop intake policy overlays.
        coopFeatureHandler = new CoopFeatureHandler(getLogger());

        // Register /tw commands if the server supports it.
        if (getCommandRegistry() != null) {
            getCommandRegistry().registerCommand(new TameworkCommandRoot());
        }

        // Global listener to enforce coop capture-intake policies where configured.
        if (coopFeatureHandler != null) {
            getEventRegistry().registerGlobal(
                    PlayerInteractEvent.class,
                    coopFeatureHandler::onPlayerInteract
            );
        }
        // Global listener to enforce owner-only interactions.
        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(translationRegistry, getLogger());
        getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                ownerInteractionListener::onPlayerInteract
        );
        if (namingFeatureHandler != null) {
            getEventRegistry().registerGlobal(PlayerChatEvent.class, namingFeatureHandler::onPlayerChat);
            getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, namingFeatureHandler::onPlayerDisconnect);
        }
        getLogger().at(Level.INFO).log(
                "Tamework item configs loaded: spawners="
                        + loadedSpawner
                        + " (total: " + itemFeatureRegistry.snapshot().size()
                        + "), naming="
                        + loadedNaming
                        + (nameItemRegistry != null ? " (total: " + nameItemRegistry.snapshot().size() + ")" : "")
                        + ", commands="
                        + loadedCommands
                        + (commandItemRegistry != null ? " (total: " + commandItemRegistry.snapshot().size() + ")" : "")
        );

        // Register custom NPC action/sensor builders once NPCPlugin is available.
        registerNpcActionsIfReady();
    }

    @Override
    protected void start() {
        getLogger().at(Level.INFO).log("Alec's Tamework! has been enabled!");
        if (assetPackCoordinator != null) {
            assetPackCoordinator.ensureAssetEditorPackVisible();
        }
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("Alec's Tamework! has been disabled!");
    }

    public ItemFeatureRegistry getItemFeatureRegistry() {
        return itemFeatureRegistry;
    }

    public NameItemRegistry getNameItemRegistry() {
        return nameItemRegistry;
    }

    public CommandItemRegistry getCommandItemRegistry() {
        return commandItemRegistry;
    }

    public static Tamework getInstance() {
        return instance;
    }

    public TranslationRegistry getTranslationRegistry() {
        return translationRegistry;
    }

    // Returns the active global config asset or defaults if none are loaded.
    public TwGlobalConfig getGlobalConfig() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        warnIfGlobalConfigMissingFields(config);
        return config;
    }

    public int reloadItemFeatureConfigs() {
        if (itemFeatureRegistry == null) {
            return 0;
        }
        itemFeatureRegistry.clear();
        itemFeatureRegistry.registerDefaults();
        registerSpawnerItemAssets();
        registerCommandItemAssets();
        int loadedSpawner = 0;
        int loadedNaming = 0;
        int loadedCommands = 0;
        loadedSpawner += loadSpawnerItemAssets();
        if (nameItemRegistry != null) {
            nameItemRegistry.clear();
            registerNamingItemAssets();
            loadedNaming += loadNameItemAssets();
        }
        if (commandItemRegistry != null) {
            commandItemRegistry.clear();
            registerCommandItemAssets();
            loadedCommands += loadCommandItemAssets();
        }
        getLogger().at(Level.INFO).log(
                "Reloaded Tamework item configs: spawners="
                        + loadedSpawner
                        + " (total: " + itemFeatureRegistry.snapshot().size()
                        + "), naming="
                        + loadedNaming
                        + (nameItemRegistry != null ? " (total: " + nameItemRegistry.snapshot().size() + ")" : "")
                        + ", commands="
                        + loadedCommands
                        + (commandItemRegistry != null ? " (total: " + commandItemRegistry.snapshot().size() + ")" : "")
        );
        return loadedSpawner + loadedNaming + loadedCommands;
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

    private void registerNamingItemAssets() {
        if (namingAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwNameItemConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Items/Naming")
                        .setCodec(TwNameItemConfig.CODEC)
                        .setKeyFunction(TwNameItemConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwNameItemConfig.class, this::onNamingAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwNameItemConfig.class, this::onNamingAssetsRemoved);
        namingAssetsRegistered = true;
    }

    private void registerCommandItemAssets() {
        if (commandAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwCommandItemConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Items/Commands")
                        .setCodec(TwCommandItemConfig.CODEC)
                        .setKeyFunction(TwCommandItemConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwCommandItemConfig.class, this::onCommandAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwCommandItemConfig.class, this::onCommandAssetsRemoved);
        commandAssetsRegistered = true;
    }

    private void registerGlobalConfigAssets() {
        if (globalAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwGlobalConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Global")
                        .setCodec(TwGlobalConfig.CODEC)
                        .setKeyFunction(TwGlobalConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwGlobalConfig.class, this::onGlobalAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwGlobalConfig.class, this::onGlobalAssetsRemoved);
        globalAssetsRegistered = true;
    }

    private void registerInteractionAssets() {
        if (interactionAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwInteractionConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Interactions")
                        .setCodec(TwInteractionConfig.CODEC)
                        .setKeyFunction(TwInteractionConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwInteractionConfig.class, this::onInteractionAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwInteractionConfig.class, this::onInteractionAssetsRemoved);
        interactionAssetsRegistered = true;
    }

    private void registerCoopAssets() {
        if (coopAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwCoopConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Farming/Coops")
                        .setCodec(TwCoopConfig.CODEC)
                        .setKeyFunction(TwCoopConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwCoopConfig.class, this::onCoopAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwCoopConfig.class, this::onCoopAssetsRemoved);
        coopAssetsRegistered = true;
    }

    private void registerHappinessAssets() {
        if (happinessAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwHappinessConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Happiness")
                        .setCodec(TwHappinessConfig.CODEC)
                        .setKeyFunction(TwHappinessConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwHappinessConfig.class, this::onHappinessAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwHappinessConfig.class, this::onHappinessAssetsRemoved);
        happinessAssetsRegistered = true;
    }

    private void registerNeedsAssets() {
        if (needsAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwNeedsConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Needs")
                        .setCodec(TwNeedsConfig.CODEC)
                        .setKeyFunction(TwNeedsConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwNeedsConfig.class, this::onNeedsAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwNeedsConfig.class, this::onNeedsAssetsRemoved);
        needsAssetsRegistered = true;
    }

    private void registerBreedingAssets() {
        if (breedingAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwBreedingConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Breeding")
                        .setCodec(TwBreedingConfig.CODEC)
                        .setKeyFunction(TwBreedingConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwBreedingConfig.class, this::onBreedingAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwBreedingConfig.class, this::onBreedingAssetsRemoved);
        breedingAssetsRegistered = true;
    }

    private void registerTraitAssets() {
        if (traitAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwTraitConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Traits")
                        .setCodec(TwTraitConfig.CODEC)
                        .setKeyFunction(TwTraitConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwTraitConfig.class, this::onTraitAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwTraitConfig.class, this::onTraitAssetsRemoved);
        traitAssetsRegistered = true;
    }

    private void onSpawnerAssetsLoaded(
            LoadedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        TwSpawnerConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onSpawnerAssetsRemoved(
            RemovedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        TwSpawnerConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onNamingAssetsLoaded(
            LoadedAssetsEvent<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> event) {
        TwNameItemConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onNamingAssetsRemoved(
            RemovedAssetsEvent<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> event) {
        TwNameItemConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onCommandAssetsLoaded(
            LoadedAssetsEvent<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> event) {
        TwCommandItemConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onCommandAssetsRemoved(
            RemovedAssetsEvent<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> event) {
        TwCommandItemConfig.clearInheritanceFallbackCache();
        reloadItemFeatureConfigs();
    }

    private void onGlobalAssetsLoaded(
            LoadedAssetsEvent<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> event) {
        TwGlobalConfig.clearCache();
        lastGlobalConfigWarningKey = null;
    }

    private void onGlobalAssetsRemoved(
            RemovedAssetsEvent<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> event) {
        TwGlobalConfig.clearCache();
        lastGlobalConfigWarningKey = null;
    }

    private void onInteractionAssetsLoaded(
            LoadedAssetsEvent<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> event) {
        TwInteractionConfig.clearRoleCache();
    }

    private void onInteractionAssetsRemoved(
            RemovedAssetsEvent<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> event) {
        TwInteractionConfig.clearRoleCache();
    }

    private void onCoopAssetsLoaded(
            LoadedAssetsEvent<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> event) {
        TwCoopConfig.clearCoopCache();
    }

    private void onCoopAssetsRemoved(
            RemovedAssetsEvent<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> event) {
        TwCoopConfig.clearCoopCache();
    }

    private void onHappinessAssetsLoaded(
            LoadedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
    }

    private void onHappinessAssetsRemoved(
            RemovedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
    }

    private void onNeedsAssetsLoaded(
            LoadedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
    }

    private void onNeedsAssetsRemoved(
            RemovedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
    }

    private void onBreedingAssetsLoaded(
            LoadedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
    }

    private void onBreedingAssetsRemoved(
            RemovedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
    }

    private void onTraitAssetsLoaded(
            LoadedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
    }

    private void onTraitAssetsRemoved(
            RemovedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
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

    private int loadNameItemAssets() {
        if (nameItemRegistry == null) {
            return 0;
        }
        DefaultAssetMap<String, TwNameItemConfig> assetMap = TwNameItemConfig.getAssetMap();
        if (assetMap == null) {
            return 0;
        }
        int loaded = 0;
        for (TwNameItemConfig asset : assetMap.getAssetMap().values()) {
            if (asset == null) {
                continue;
            }
            String itemId = asset.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            nameItemRegistry.register(itemId, asset);
            loaded++;
        }
        return loaded;
    }

    private int loadCommandItemAssets() {
        if (commandItemRegistry == null) {
            return 0;
        }
        DefaultAssetMap<String, TwCommandItemConfig> assetMap = TwCommandItemConfig.getAssetMap();
        if (assetMap == null) {
            return 0;
        }
        int loaded = 0;
        for (TwCommandItemConfig asset : assetMap.getAssetMap().values()) {
            if (asset == null || !asset.isEnabled()) {
                continue;
            }
            String[] itemIds = asset.getItemIds();
            if (itemIds == null || itemIds.length == 0) {
                continue;
            }
            for (String itemId : itemIds) {
                if (itemId == null || itemId.isBlank()) {
                    continue;
                }
                commandItemRegistry.register(itemId, asset);
                loaded++;
            }
        }
        return loaded;
    }


    public SpawnerFeatureHandler getSpawnerFeatureHandler() {
        return spawnerFeatureHandler;
    }

    public NamingFeatureHandler getNamingFeatureHandler() {
        return namingFeatureHandler;
    }

    public CommandItemFeatureHandler getCommandItemFeatureHandler() {
        return commandItemFeatureHandler;
    }

    public ComponentType<EntityStore, TameworkOwnerComponent> getOwnerComponentType() {
        return ownerComponentType;
    }

    public ComponentType<EntityStore, TameworkTamedComponent> getTamedComponentType() {
        return tamedComponentType;
    }

    public ComponentType<EntityStore, TameworkHookComponent> getHookComponentType() {
        return hookComponentType;
    }

    public ComponentType<EntityStore, TameworkNpcNameComponent> getNpcNameComponentType() {
        return npcNameComponentType;
    }

    public ComponentType<EntityStore, TameworkCommandLinksComponent> getCommandLinksComponentType() {
        return commandLinksComponentType;
    }

    public ComponentType<EntityStore, TameworkHappinessComponent> getHappinessComponentType() {
        return happinessComponentType;
    }

    public ComponentType<EntityStore, TameworkNeedsComponent> getNeedsComponentType() {
        return needsComponentType;
    }

    public ComponentType<EntityStore, TameworkBreedingComponent> getBreedingComponentType() {
        return breedingComponentType;
    }

    public ComponentType<EntityStore, TameworkTraitsComponent> getTraitsComponentType() {
        return traitsComponentType;
    }

    public ComponentType<EntityStore, TameworkAttachmentsComponent> getAttachmentsComponentType() {
        return attachmentsComponentType;
    }

    public ComponentType<EntityStore, TameworkLifeStageComponent> getLifeStageComponentType() {
        return lifeStageComponentType;
    }

    public boolean isDebugHookEnabled() {
        return debugHookLogs;
    }

    public boolean setDebugHookEnabled(boolean enabled) {
        debugHookLogs = enabled;
        return debugHookLogs;
    }

    public boolean toggleDebugHookEnabled() {
        debugHookLogs = !debugHookLogs;
        return debugHookLogs;
    }

    public boolean isDebugSpawnerEnabled() {
        return debugSpawnerLogs;
    }

    public boolean setDebugSpawnerEnabled(boolean enabled) {
        debugSpawnerLogs = enabled;
        return debugSpawnerLogs;
    }

    public boolean toggleDebugSpawnerEnabled() {
        debugSpawnerLogs = !debugSpawnerLogs;
        return debugSpawnerLogs;
    }

    public boolean isDebugPromptEnabled() {
        return debugPromptLogs;
    }

    public boolean setDebugPromptEnabled(boolean enabled) {
        debugPromptLogs = enabled;
        return debugPromptLogs;
    }

    public boolean toggleDebugPromptEnabled() {
        debugPromptLogs = !debugPromptLogs;
        return debugPromptLogs;
    }

    // Logs a warning if required global config fields are missing.
    private void warnIfGlobalConfigMissingFields(TwGlobalConfig config) {
        if (config == null || getLogger() == null) {
            return;
        }
        String[] missing = config.listMissingRequiredFields();
        if (missing.length == 0) {
            lastGlobalConfigWarningKey = null;
            return;
        }
        String configId = config.getId();
        if (configId == null || configId.isBlank()) {
            configId = "<unknown>";
        }
        String key = configId + "|" + String.join(",", missing);
        if (key.equals(lastGlobalConfigWarningKey)) {
            return;
        }
        lastGlobalConfigWarningKey = key;
        getLogger().at(Level.WARNING).log(
                "TwGlobalConfig '" + configId + "' is missing required fields: "
                        + String.join(", ", missing)
        );
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
            actionFactory.add(BuilderActionTameworkHarvestDrop.BUILDER_ID, BuilderActionTameworkHarvestDrop::new);
            actionFactory.add(BuilderActionTameworkInteract.BUILDER_ID, BuilderActionTameworkInteract::new);
            actionFactory.add(BuilderActionTameworkInteractPrompt.BUILDER_ID, BuilderActionTameworkInteractPrompt::new);
            actionFactory.add(BuilderActionTameworkNeedsResourceConsume.BUILDER_ID, BuilderActionTameworkNeedsResourceConsume::new);
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
            sensorFactory.add(BuilderSensorTameworkLifeStage.BUILDER_ID, BuilderSensorTameworkLifeStage::new);
            sensorFactory.add(BuilderSensorTameworkHook.BUILDER_ID, BuilderSensorTameworkHook::new);
            sensorFactory.add(BuilderSensorTameworkNeedBelow.BUILDER_ID, BuilderSensorTameworkNeedBelow::new);
            sensorFactory.add(BuilderSensorTameworkNeedsResourceTarget.BUILDER_ID, BuilderSensorTameworkNeedsResourceTarget::new);
        }

        BuilderFactory<IEntityFilter> filterFactory = npcPlugin.getBuilderManager().getFactory(IEntityFilter.class);
        if (filterFactory == null) {
            getLogger().at(Level.WARNING).log("Tamework NPC builder registration: Entity filter factory missing.");
        } else {
            getLogger().at(Level.INFO).log("Tamework NPC builder registration: Entity filter factory ready.");
            filterFactory.add(
                    BuilderEntityFilterTameworkAttitudeFromTargetSlot.BUILDER_ID,
                    BuilderEntityFilterTameworkAttitudeFromTargetSlot::new
            );
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


