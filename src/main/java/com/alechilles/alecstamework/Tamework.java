package com.alechilles.alecstamework;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.assets.TameworkAssetPackCoordinator;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwDebugConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemorySystem;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.damage.CompanionHappinessDamageImpulseSystem;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileSpawnSystem;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardSystem;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectComponent;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectSystem;
import com.alechilles.alecstamework.damage.TraitDamageModifierSystem;
import com.alechilles.alecstamework.effects.PlayerEffectMovementSystem;
import com.alechilles.alecstamework.interactions.TameworkCommandInteraction;
import com.alechilles.alecstamework.interactions.TameworkClearFeedTroughWaterInteraction;
import com.alechilles.alecstamework.interactions.TameworkLaunchProjectileInteraction;
import com.alechilles.alecstamework.interactions.TameworkNameNpcInteraction;
import com.alechilles.alecstamework.interactions.TameworkSpawnInteraction;
import com.alechilles.alecstamework.integration.tooltips.SpawnerTooltipBridge;
import com.alechilles.alecstamework.integration.tooltips.SpawnerTooltipBridgeLoader;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.alechilles.alecstamework.items.CommandCoopManagedWildCaptureSystem;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandTeleportArrivalRelocationSystem;
import com.alechilles.alecstamework.items.CoopDebugLogger;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.FeedTroughFoodStateSyncSystem;
import com.alechilles.alecstamework.items.FeedTroughWaterChargeDroplistCompatService;
import com.alechilles.alecstamework.items.components.TameworkFeedTroughWaterChargesComponent;
import com.alechilles.alecstamework.items.NamingFeatureHandler;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.items.TranquilizerRecipeVisibilityService;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.TameworkHStatsIntegration;
import com.alechilles.alecstamework.npc.TameworkNpcBuilderRegistrar;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.OwnerPresenceTimelineService;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureMarkerComponent;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.alechilles.alecstamework.npc.systems.CompanionProgressionBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionPassiveBreedingSystem;
import com.alechilles.alecstamework.npc.systems.CompanionLifeStageResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionAttachmentSyncSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDespawnDiagnosticsSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDespawnProtectionSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CommandLinkedRevivableDropSuppressionSystem;
import com.alechilles.alecstamework.npc.systems.CommandNpcRelocationOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionNeedsSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitStatSyncSystem;
import com.alechilles.alecstamework.npc.systems.MountedInteractableSafetySystem;
import com.alechilles.alecstamework.npc.systems.MountedNpcTeleportSafetySystem;
import com.alechilles.alecstamework.npc.systems.MountedOwnerReferenceSanitySystem;
import com.alechilles.alecstamework.npc.systems.NpcDebugDisplayResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.NpcMountedNameplateVisibilitySystem;
import com.alechilles.alecstamework.npc.systems.NpcNamePersistenceSystem;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Main entry point for the Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {

    private static Tamework instance;

    private ItemFeatureRegistry itemFeatureRegistry;
    private NameItemRegistry nameItemRegistry;
    private CommandItemRegistry commandItemRegistry;
    private TameworkAssetPackCoordinator assetPackCoordinator;
    private TwConfigOverrideManager configOverrideManager;
    private final Set<String> overrideInitializedScopeKeys = ConcurrentHashMap.newKeySet();

    private TranslationRegistry translationRegistry;
    private SpawnerFeatureHandler spawnerFeatureHandler;
    private NamingFeatureHandler namingFeatureHandler;
    private CommandItemFeatureHandler commandItemFeatureHandler;
    private TranquilizerRecipeVisibilityService tranquilizerRecipeVisibilityService;
    private FeedTroughWaterChargeDroplistCompatService feedTroughWaterChargeDroplistCompatService;
    private CommandNpcRelocationService commandNpcRelocationService;
    private CommandLinkedNpcCaptureService commandLinkedNpcCaptureService;
    private CommandLinkedNpcCoopService commandLinkedNpcCoopService;
    private CommandLinkedNpcDeathService commandLinkedNpcDeathService;
    private CommandLinkedNpcLostService commandLinkedNpcLostService;
    private CommandLinkedNpcStateSnapshotService commandLinkedNpcStateSnapshotService;
    private CoopResidentStateSnapshotService coopResidentStateSnapshotService;
    private Path runtimeDataDirectory;
    private TameworkPersistenceRuntime persistenceRuntime;
    private TameworkApi api;
    private TameworkEventBus apiEventBus;
    private InteractionExtensionRegistry interactionExtensionRegistry;
    private ApiSelfTestFixtureManager apiSelfTestFixtureManager;
    private ApiSelfTestRunner apiSelfTestRunner;
    private TameworkNpcBuilderRegistrar npcBuilderRegistrar;
    private TameworkHStatsIntegration hStatsIntegration;
    private CrashTelemetryService crashTelemetryService;
    private SpawnerTooltipBridge spawnerTooltipBridge;
    private boolean globalAssetsRegistered;
    private boolean companionAssetsRegistered;
    private boolean spawnerAssetsRegistered;
    private boolean namingAssetsRegistered;
    private boolean namesAssetsRegistered;
    private boolean commandAssetsRegistered;
    private boolean interactionAssetsRegistered;
    private boolean coopAssetsRegistered;
    private boolean happinessAssetsRegistered;
    private boolean needsAssetsRegistered;
    private boolean breedingAssetsRegistered;
    private boolean traitAssetsRegistered;
    private boolean debugAssetsRegistered;
    private String lastGlobalConfigWarningKey;
    private final Object itemFeatureReloadSuppressionLock = new Object();
    private int itemFeatureReloadSuppressionDepth;
    private boolean itemFeatureReloadPending;
    private final Object overrideAssetEventSuppressionLock = new Object();
    private int overrideAssetEventSuppressionDepth;
    private boolean globalReconcilePendingAfterOverrideReload;
    private ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType;
    private ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType;
    private ComponentType<EntityStore, TameworkHookComponent> hookComponentType;
    private ComponentType<EntityStore, TameworkNpcNameComponent> npcNameComponentType;
    private ComponentType<EntityStore, TameworkMountedNameplateComponent> mountedNameplateComponentType;
    private ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksComponentType;
    private ComponentType<EntityStore, TameworkHappinessComponent> happinessComponentType;
    private ComponentType<EntityStore, TameworkNeedsComponent> needsComponentType;
    private ComponentType<EntityStore, TameworkBreedingComponent> breedingComponentType;
    private ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType;
    private ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsComponentType;
    private ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType;
    private ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> projectileImpactEffectComponentType;
    private ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringHazardProjectileComponentType;
    private ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazardComponentType;
    private ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> apiSelfTestFixtureMarkerComponentType;
    private ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> feedTroughWaterChargesComponentType;
    private volatile boolean debugHookLogs;
    private volatile boolean debugSpawnerLogs;
    private volatile boolean debugSpawnerLocationLogs;
    private volatile boolean debugPromptLogs;
    private volatile boolean debugDespawnLogs;
    private volatile String debugDespawnRoleFilter;
    private volatile boolean debugLagLogs;
    private volatile boolean debugCoopLogs;
    private volatile boolean debugBreedingLogs;
    private volatile boolean debugNeedsConsumeDiagnosticsLogs;
    private volatile boolean debugNeedsDamageDiagnosticsLogs;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        initializeCrashTelemetry();
        try {
            setupInternal();
        } catch (Throwable throwable) {
            captureSetupFailure(throwable);
            throw throwable;
        }
    }

    private void setupInternal() {
        itemFeatureRegistry = new ItemFeatureRegistry();
        nameItemRegistry = new NameItemRegistry();
        commandItemRegistry = new CommandItemRegistry();
        assetPackCoordinator = new TameworkAssetPackCoordinator(this);
        configOverrideManager = new TwConfigOverrideManager(this);
        tranquilizerRecipeVisibilityService = new TranquilizerRecipeVisibilityService();
        feedTroughWaterChargeDroplistCompatService = new FeedTroughWaterChargeDroplistCompatService();
        npcBuilderRegistrar = new TameworkNpcBuilderRegistrar(this);
        hStatsIntegration = new TameworkHStatsIntegration(this);
        assetPackCoordinator.registerEarlyAssetPackOrderingHook();
        // Register the custom item interaction used by spawner items.
        Interaction.CODEC.register("TameworkSpawn", TameworkSpawnInteraction.class, TameworkSpawnInteraction.CODEC);
        // Register the custom item interaction used by naming items.
        Interaction.CODEC.register("TameworkNameNpc", TameworkNameNpcInteraction.class, TameworkNameNpcInteraction.CODEC);
        // Register the custom item interaction used by command items.
        Interaction.CODEC.register("TameworkCommand", TameworkCommandInteraction.class, TameworkCommandInteraction.CODEC);
        // Register the custom block interaction used to empty water trough states.
        Interaction.CODEC.register(
                "TameworkClearFeedTroughWater",
                TameworkClearFeedTroughWaterInteraction.class,
                TameworkClearFeedTroughWaterInteraction.CODEC
        );
        // Register the custom projectile interaction used for solved lobbed shots.
        Interaction.CODEC.register(
                "TameworkLaunchProjectile",
                TameworkLaunchProjectileInteraction.class,
                TameworkLaunchProjectileInteraction.CODEC
        );
        itemFeatureRegistry.registerDefaults();
        registerGlobalConfigAssets();
        registerCompanionAssets();
        registerCoopAssets();
        registerSpawnerItemAssets();
        registerNamingItemAssets();
        registerNamesAssets();
        registerCommandItemAssets();
        registerInteractionAssets();
        registerHappinessAssets();
        registerNeedsAssets();
        registerBreedingAssets();
        registerTraitAssets();
        registerDebugAssets();
        getEventRegistry().register(LoadedAssetsEvent.class, CraftingRecipe.class, this::onCraftingRecipeAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, CraftingRecipe.class, this::onCraftingRecipeAssetsRemoved);
        getEventRegistry().register(LoadedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsRemoved);

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

        mountedNameplateComponentType = getEntityStoreRegistry().registerComponent(
                TameworkMountedNameplateComponent.class,
                "TameworkMountedNameplate",
                TameworkMountedNameplateComponent.CODEC
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

        projectileImpactEffectComponentType = getEntityStoreRegistry().registerComponent(
                TameworkProjectileImpactEffectComponent.class,
                "TameworkProjectileImpactEffect",
                TameworkProjectileImpactEffectComponent.CODEC
        );

        lingeringHazardProjectileComponentType = getEntityStoreRegistry().registerComponent(
                TameworkLingeringHazardProjectileComponent.class,
                "TameworkLingeringHazardProjectile",
                TameworkLingeringHazardProjectileComponent.CODEC
        );

        lingeringHazardComponentType = getEntityStoreRegistry().registerComponent(
                TameworkLingeringHazardComponent.class,
                "TameworkLingeringHazard",
                TameworkLingeringHazardComponent.CODEC
        );

        apiSelfTestFixtureMarkerComponentType = getEntityStoreRegistry().registerComponent(
                ApiSelfTestFixtureMarkerComponent.class,
                "TameworkApiSelfTestFixture",
                ApiSelfTestFixtureMarkerComponent.CODEC
        );

        feedTroughWaterChargesComponentType = getChunkStoreRegistry().registerComponent(
                TameworkFeedTroughWaterChargesComponent.class,
                "TameworkFeedTroughWaterCharges",
                TameworkFeedTroughWaterChargesComponent.CODEC
        );

        getEntityStoreRegistry().registerSystem(
                new NpcNamePersistenceSystem(npcNameComponentType, NPCEntity.getComponentType())
        );
        ComponentType<EntityStore, NPCMountComponent> npcMountComponentType = resolveNpcMountComponentTypeOrNull();
        if (npcMountComponentType == null) {
            getLogger().at(Level.WARNING).log(
                    "Mount plugin component type unavailable during setup; skipping mount-dependent Tamework systems."
            );
        } else {
            getEntityStoreRegistry().registerSystem(
                    new MountedOwnerReferenceSanitySystem(
                            NPCEntity.getComponentType(),
                            npcMountComponentType,
                            Player.getComponentType(),
                            Interactable.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedNpcTeleportSafetySystem(
                            NPCEntity.getComponentType(),
                            npcMountComponentType,
                            Teleport.getComponentType(),
                            Player.getComponentType(),
                            Interactable.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new NpcMountedNameplateVisibilitySystem(
                            NPCEntity.getComponentType(),
                            npcMountComponentType,
                            mountedNameplateComponentType,
                            npcNameComponentType
                    )
            );
            getEntityStoreRegistry().registerSystem(new MountedInteractableSafetySystem());
        }
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
        getEntityStoreRegistry().registerSystem(new CommandLinkedRevivableDropSuppressionSystem());
        getEntityStoreRegistry().registerSystem(new CompanionAttachmentSyncSystem());
        getEntityStoreRegistry().registerSystem(new CompanionDespawnProtectionSystem());
        getEntityStoreRegistry().registerSystem(
                new CompanionDespawnDiagnosticsSystem(
                        NPCEntity.getComponentType(),
                        tamedComponentType,
                        ownerComponentType,
                        SpawnMarkerReference.getComponentType(),
                        SpawnBeaconReference.getComponentType(),
                        UUIDComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(new CompanionNeedsSystem());
        getEntityStoreRegistry().registerSystem(new CompanionPassiveBreedingSystem());
        commandNpcRelocationService = new CommandNpcRelocationService(getLogger());
        runtimeDataDirectory = new TameworkDataPathService(getLogger())
                .resolveAndMigrateDataDirectory(getDataDirectory());
        persistenceRuntime = TameworkPersistenceRuntime.initialize(runtimeDataDirectory, getLogger());
        apiEventBus = new TameworkEventBus(getLogger());
        interactionExtensionRegistry = new InteractionExtensionRegistry(getLogger());
        persistenceRuntime.getNpcProfileRepository().setChangeObserver(apiEventBus);
        commandLinkedNpcStateSnapshotService = new CommandLinkedNpcStateSnapshotService(
                persistenceRuntime.getNpcProfileRepository()
        );
        api = new TameworkApiImpl(
                persistenceRuntime,
                apiEventBus,
                commandLinkedNpcStateSnapshotService,
                interactionExtensionRegistry
        );
        apiSelfTestFixtureManager = new ApiSelfTestFixtureManager(persistenceRuntime);
        apiSelfTestRunner = new ApiSelfTestRunner();
        commandLinkedNpcCaptureService = new CommandLinkedNpcCaptureService(
                persistenceRuntime.getCaptureRepository(),
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcProfileRepository()
        );
        commandLinkedNpcCoopService = new CommandLinkedNpcCoopService(
                persistenceRuntime.getCoopLedgerRepository(),
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcProfileRepository()
        );
        commandLinkedNpcDeathService = new CommandLinkedNpcDeathService(
                commandLinkedNpcStateSnapshotService,
                persistenceRuntime.getDeathRepository(),
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcProfileRepository()
        );
        commandLinkedNpcLostService = new CommandLinkedNpcLostService(
                getLogger(),
                commandLinkedNpcStateSnapshotService,
                commandLinkedNpcCaptureService,
                commandLinkedNpcCoopService,
                persistenceRuntime.getLostRepository(),
                persistenceRuntime.getHealthService()
        );
        coopResidentStateSnapshotService = new CoopResidentStateSnapshotService();
        commandNpcRelocationService.setRelocationDropListener(commandLinkedNpcLostService::recordLostFromRelocationDrop);
        getEntityStoreRegistry().registerSystem(
                new CommandNpcRelocationOnLoadSystem(
                        commandNpcRelocationService,
                        commandLinkedNpcDeathService,
                        commandLinkedNpcLostService,
                        commandLinkedNpcStateSnapshotService,
                        coopResidentStateSnapshotService,
                        null
                )
        );
        getChunkStoreRegistry().registerSystem(
                new CommandCoopManagedWildCaptureSystem(
                        commandLinkedNpcCoopService,
                        commandLinkedNpcCaptureService,
                        commandNpcRelocationService,
                        commandLinkedNpcLostService,
                        coopResidentStateSnapshotService
                )
        );
        getChunkStoreRegistry().registerSystem(new FeedTroughFoodStateSyncSystem());

        // Damage event is needed for owner damage filtering; avoid double-registration.
        try {
            getEntityStoreRegistry().registerEntityEventType(Damage.class);
        } catch (IllegalArgumentException ex) {
            getLogger().at(Level.INFO).log("Damage event type already registered; skipping registration.");
        }

        // Register damage filter system (configurable owner protection).
        getEntityStoreRegistry().registerSystem(new DamageTargetMemorySystem());
        getEntityStoreRegistry().registerSystem(
                new OwnerDamageFilterSystem(getLogger())
        );
        getEntityStoreRegistry().registerSystem(new TraitDamageModifierSystem());
        getEntityStoreRegistry().registerSystem(new CompanionHappinessDamageImpulseSystem());
        getEntityStoreRegistry().registerSystem(
                new PlayerEffectMovementSystem(
                        PlayerRef.getComponentType(),
                        MovementManager.getComponentType(),
                        EffectControllerComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new TameworkProjectileImpactEffectSystem(
                        projectileImpactEffectComponentType,
                        com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new TameworkLingeringHazardProjectileSpawnSystem(
                        lingeringHazardProjectileComponentType,
                        lingeringHazardComponentType,
                        com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new TameworkLingeringHazardSystem(
                        lingeringHazardComponentType,
                        com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()
                )
        );

        // Load item feature configs from bundled defaults and mod overrides.
        int loadedSpawner = loadSpawnerItemAssets();
        int loadedNaming = loadNameItemAssets();
        int loadedCommands = loadCommandItemAssets();

        // Load translation entries from mods so messages can be localized.
        translationRegistry = new TranslationRegistry();
        int langLoaded = ModLanguageDiscovery.loadAll(translationRegistry, getLogger(), getDataDirectory());
        getLogger().at(Level.INFO).log("Tamework language entries loaded: " + langLoaded);
        spawnerTooltipBridge = SpawnerTooltipBridgeLoader.initialize(
                getLogger(),
                itemFeatureRegistry,
                translationRegistry
        );

        // Core handler for capture/spawn flows.
        spawnerFeatureHandler = new SpawnerFeatureHandler(
                getLogger(),
                itemFeatureRegistry,
                commandLinkedNpcCaptureService,
                commandLinkedNpcCoopService,
                commandNpcRelocationService,
                commandLinkedNpcLostService
        );
        // Core handler for naming flows.
        namingFeatureHandler = new NamingFeatureHandler(nameItemRegistry, translationRegistry);
        // Core handler for command-item linking and dispatch.
        commandItemFeatureHandler = new CommandItemFeatureHandler(
                commandItemRegistry,
                commandNpcRelocationService,
                commandLinkedNpcDeathService,
                commandLinkedNpcCaptureService,
                commandLinkedNpcCoopService,
                commandLinkedNpcLostService,
                commandLinkedNpcStateSnapshotService
        );
        getEntityStoreRegistry().registerSystem(
                new CommandTeleportArrivalRelocationSystem(commandItemFeatureHandler)
        );

        // Register /tw commands if the server supports it.
        if (getCommandRegistry() != null) {
            getCommandRegistry().registerCommand(new TameworkCommandRoot());
        }
        applyDebugConfigDefaults();

        // Global listener to enforce owner-only interactions.
        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(translationRegistry, getLogger());
        getEventRegistry().registerGlobal(
                PlayerInteractEvent.class,
                ownerInteractionListener::onPlayerInteract
        );
        OwnerPresenceTimelineService ownerPresenceTimelineService = OwnerPresenceTimelineService.get();
        getEventRegistry().registerGlobal(
                PlayerConnectEvent.class,
                ownerPresenceTimelineService::onPlayerConnect
        );
        getEventRegistry().registerGlobal(
                PlayerDisconnectEvent.class,
                ownerPresenceTimelineService::onPlayerDisconnect
        );
        if (namingFeatureHandler != null) {
            getEventRegistry().registerGlobal(PlayerChatEvent.class, namingFeatureHandler::onPlayerChat);
            getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, namingFeatureHandler::onPlayerDisconnect);
        }
        if (commandItemFeatureHandler != null) {
            getEventRegistry().registerGlobal(
                    AddPlayerToWorldEvent.class,
                    commandItemFeatureHandler::onAddPlayerToWorld
            );
        }
        getEventRegistry().registerGlobal(
                AddPlayerToWorldEvent.class,
                this::onPlayerAddedToWorldForOverrides
        );
        getEventRegistry().registerGlobal(
                RemoveWorldEvent.class,
                this::onWorldRemovedForCrashTelemetry
        );
        reconcileTranquilizerRecipeVisibility();
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
        if (npcBuilderRegistrar != null) {
            npcBuilderRegistrar.registerNpcActionsIfReady();
        }
    }

    @Override
    protected void start() {
        try {
            startInternal();
        } catch (Throwable throwable) {
            captureStartFailure(throwable);
            throw throwable;
        }
    }

    private void startInternal() {
        OwnerPresenceTimelineService.get().seedOnlinePlayersFromUniverse();
        initializeOverridesForLoadedWorlds();
        getLogger().at(Level.INFO).log("Alec's Tamework! has been enabled!");
        if (hStatsIntegration != null) {
            hStatsIntegration.initialize();
        }
        if (crashTelemetryService != null) {
            crashTelemetryService.start();
        }
        if (assetPackCoordinator != null) {
            assetPackCoordinator.ensureAssetEditorPackVisible();
        }
    }

    @Override
    protected void shutdown() {
        if (crashTelemetryService != null) {
            crashTelemetryService.shutdown();
        }
        overrideInitializedScopeKeys.clear();
        if (spawnerTooltipBridge != null) {
            spawnerTooltipBridge.shutdown();
        }
        api = null;
        if (persistenceRuntime != null) {
            persistenceRuntime.getNpcProfileRepository().setChangeObserver(null);
        }
        if (apiEventBus != null) {
            apiEventBus.close();
            apiEventBus = null;
        }
        if (persistenceRuntime != null) {
            persistenceRuntime.close();
            persistenceRuntime = null;
        }
        runtimeDataDirectory = null;
        apiSelfTestFixtureManager = null;
        apiSelfTestRunner = null;
        crashTelemetryService = null;
        getLogger().at(Level.INFO).log("Alec's Tamework! has been disabled!");
    }

    private void initializeCrashTelemetry() {
        if (crashTelemetryService != null) {
            return;
        }
        try {
            crashTelemetryService = CrashTelemetryService.create(this);
        } catch (Exception ex) {
            getLogger().at(Level.WARNING).withCause(ex)
                    .log("Failed to initialize Tamework crash telemetry; continuing without crash telemetry.");
        }
    }

    private void captureSetupFailure(@Nullable Throwable throwable) {
        if (crashTelemetryService == null || throwable == null) {
            return;
        }
        crashTelemetryService.captureSetupFailure(throwable);
    }

    private void captureStartFailure(@Nullable Throwable throwable) {
        if (crashTelemetryService == null || throwable == null) {
            return;
        }
        crashTelemetryService.captureStartFailure(throwable);
    }

    private void onWorldRemovedForCrashTelemetry(@Nonnull RemoveWorldEvent event) {
        if (event == null
                || event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL
                || crashTelemetryService == null) {
            return;
        }
        crashTelemetryService.captureExceptionalWorldRemoval(event.getWorld(), event.getRemovalReason());
    }

    private void onPlayerAddedToWorldForOverrides(@Nonnull AddPlayerToWorldEvent event) {
        if (event == null || event.getWorld() == null || configOverrideManager == null) {
            return;
        }
        initializeOverridesForWorld(event.getWorld());
    }

    private void initializeOverridesForLoadedWorlds() {
        if (configOverrideManager == null) {
            return;
        }
        Universe universe = Universe.get();
        if (universe == null || universe.getWorlds() == null || universe.getWorlds().isEmpty()) {
            return;
        }
        for (World world : universe.getWorlds().values()) {
            if (world == null) {
                continue;
            }
            initializeOverridesForWorld(world);
        }
    }

    private void initializeOverridesForWorld(@Nonnull World world) {
        if (world == null || configOverrideManager == null) {
            return;
        }
        // Override files are resolved from the universe root, so instance worlds share one reload scope.
        String overrideScopeKey = configOverrideManager.resolveOverrideScopeKey(world);
        if (!overrideInitializedScopeKeys.add(overrideScopeKey)) {
            return;
        }
        TwConfigOverrideManager.ReloadResult reloadResult = configOverrideManager.reloadOverrides(world);
        if (reloadResult.hasErrors()) {
            getLogger().at(Level.WARNING).log(
                    "Loaded Tamework overrides for world "
                            + world.getName()
                            + " with "
                            + reloadResult.getErrors().size()
                            + " error(s)."
            );
        }
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

    public TwConfigOverrideManager getConfigOverrideManager() {
        return configOverrideManager;
    }

    @Nullable
    public Path getRuntimeDataDirectory() {
        return runtimeDataDirectory;
    }

    @Nullable
    public TameworkPersistenceRuntime getPersistenceRuntime() {
        return persistenceRuntime;
    }

    @Nullable
    public TameworkApi getApi() {
        return api;
    }

    @Nullable
    public CrashTelemetryService getCrashTelemetryService() {
        return crashTelemetryService;
    }

    @Nullable
    public InteractionExtensionRuntime getInteractionExtensionRuntime() {
        return interactionExtensionRegistry;
    }

    @Nullable
    public ApiSelfTestFixtureManager getApiSelfTestFixtureManager() {
        return apiSelfTestFixtureManager;
    }

    @Nullable
    public ApiSelfTestRunner getApiSelfTestRunner() {
        return apiSelfTestRunner;
    }

    public void beginItemFeatureAssetReloadSuppression() {
        synchronized (itemFeatureReloadSuppressionLock) {
            itemFeatureReloadSuppressionDepth++;
        }
    }

    public void endItemFeatureAssetReloadSuppression() {
        boolean shouldReload = false;
        synchronized (itemFeatureReloadSuppressionLock) {
            if (itemFeatureReloadSuppressionDepth > 0) {
                itemFeatureReloadSuppressionDepth--;
            }
            if (itemFeatureReloadSuppressionDepth == 0 && itemFeatureReloadPending) {
                itemFeatureReloadPending = false;
                shouldReload = true;
            }
        }
        if (shouldReload) {
            getLogger().at(Level.INFO).log("Running deferred item-feature config reload after override reload.");
            reloadItemFeatureConfigs();
        }
    }

    private void requestItemFeatureConfigReloadFromAssetEvent() {
        boolean suppressed;
        synchronized (itemFeatureReloadSuppressionLock) {
            suppressed = itemFeatureReloadSuppressionDepth > 0;
            if (suppressed) {
                itemFeatureReloadPending = true;
            }
        }
        if (suppressed) {
            return;
        }
        reloadItemFeatureConfigs();
    }

    public void beginOverrideAssetEventSuppression() {
        synchronized (overrideAssetEventSuppressionLock) {
            overrideAssetEventSuppressionDepth++;
        }
    }

    public void endOverrideAssetEventSuppression() {
        boolean shouldReconcileGlobal = false;
        synchronized (overrideAssetEventSuppressionLock) {
            if (overrideAssetEventSuppressionDepth > 0) {
                overrideAssetEventSuppressionDepth--;
            }
            if (overrideAssetEventSuppressionDepth == 0 && globalReconcilePendingAfterOverrideReload) {
                globalReconcilePendingAfterOverrideReload = false;
                shouldReconcileGlobal = true;
            }
        }
        if (shouldReconcileGlobal) {
            getLogger().at(Level.INFO).log("Running deferred global recipe reconcile after override reload.");
            reconcileTranquilizerRecipeVisibility();
        }
    }

    private boolean deferGlobalReconcileIfSuppressed() {
        synchronized (overrideAssetEventSuppressionLock) {
            if (overrideAssetEventSuppressionDepth <= 0) {
                return false;
            }
            globalReconcilePendingAfterOverrideReload = true;
            return true;
        }
    }

    @Nonnull
    public TwConfigOverrideManager.ReloadResult reloadConfigOverrides(@Nonnull World world) {
        if (configOverrideManager == null || world == null) {
            return TwConfigOverrideManager.ReloadResult.empty();
        }
        return configOverrideManager.reloadOverrides(world);
    }

    // Returns the active global config asset or defaults if none are loaded.
    public TwGlobalConfig getGlobalConfig() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        warnIfGlobalConfigMissingFields(config);
        return config;
    }

    public void applyDebugConfigDefaults() {
        TwDebugConfig config = TwDebugConfig.resolveActive();
        TwDebugConfig.DebugCommandsSection commands = config.getDebugCommands();
        setDebugHookEnabled(commands.isHook());
        setDebugSpawnerEnabled(commands.isSpawner());
        setDebugSpawnerLocationEnabled(commands.isSpawner());
        setDebugPromptEnabled(commands.isPrompt());
        setDebugDespawnEnabled(commands.isDespawn());
        setDebugLagEnabled(commands.isLag());
        setDebugCoopEnabled(commands.isCoop());
        setDebugBreedingEnabled(commands.isBreeding());
        setDebugNeedsConsumeDiagnosticsEnabled(commands.isNeedsConsumeDiagnostics());
        setDebugNeedsDamageDiagnosticsEnabled(commands.isNeedsDamageDiagnostics());
        String roleFilter = commands.getDespawnRoleFilter();
        if (roleFilter == null || roleFilter.isBlank()) {
            clearDebugDespawnRoleFilter();
        } else {
            setDebugDespawnRoleFilter(roleFilter);
        }
        getLogger().at(Level.INFO).log(
                "Applied Tamework debug defaults from "
                        + (config.getId() == null ? "<default>" : config.getId())
                        + ": hook=" + isDebugHookEnabled()
                        + ", spawner=" + isDebugSpawnerEnabled()
                        + ", spawnerLocation=" + isDebugSpawnerLocationEnabled()
                        + ", prompt=" + isDebugPromptEnabled()
                        + ", despawn=" + isDebugDespawnEnabled()
                        + ", lag=" + isDebugLagEnabled()
                        + ", coop=" + isDebugCoopEnabled()
                        + ", breeding=" + isDebugBreedingEnabled()
                        + ", needsConsumeDiagnostics=" + isDebugNeedsConsumeDiagnosticsEnabled()
                        + ", needsDamageDiagnostics=" + isDebugNeedsDamageDiagnosticsEnabled()
                        + ", despawnRoleFilter="
                        + (getDebugDespawnRoleFilter() == null ? "<none>" : getDebugDespawnRoleFilter())
        );
    }

    public int reloadItemFeatureConfigs() {
        if (itemFeatureRegistry == null) {
            return 0;
        }
        itemFeatureRegistry.clear();
        itemFeatureRegistry.registerDefaults();
        registerSpawnerItemAssets();
        registerNamesAssets();
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
        if (spawnerTooltipBridge != null) {
            spawnerTooltipBridge.refreshFromItemConfigReload();
        }
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

    private void registerNamesAssets() {
        if (namesAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwNamesConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Names")
                        .setCodec(TwNamesConfig.CODEC)
                        .setKeyFunction(TwNamesConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwNamesConfig.class, this::onNamesAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwNamesConfig.class, this::onNamesAssetsRemoved);
        namesAssetsRegistered = true;
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

    private void registerCompanionAssets() {
        if (companionAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwCompanionConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Companion")
                        .setCodec(TwCompanionConfig.CODEC)
                        .setKeyFunction(TwCompanionConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwCompanionConfig.class, this::onCompanionAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwCompanionConfig.class, this::onCompanionAssetsRemoved);
        companionAssetsRegistered = true;
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
                        .setPath("Tamework/Items/Coops")
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

    private void registerDebugAssets() {
        if (debugAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwDebugConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Debug")
                        .setCodec(TwDebugConfig.CODEC)
                        .setKeyFunction(TwDebugConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwDebugConfig.class, this::onDebugAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwDebugConfig.class, this::onDebugAssetsRemoved);
        debugAssetsRegistered = true;
    }

    private void onSpawnerAssetsLoaded(
            LoadedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        TwSpawnerConfig.clearInheritanceFallbackCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.SPAWNER, event.getLoadedAssets().keySet());
        }
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onSpawnerAssetsRemoved(
            RemovedAssetsEvent<String, TwSpawnerConfig, DefaultAssetMap<String, TwSpawnerConfig>> event) {
        TwSpawnerConfig.clearInheritanceFallbackCache();
        emitExperimentalConfigReload(TameworkConfigFamily.SPAWNER, event.getRemovedAssets());
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onNamingAssetsLoaded(
            LoadedAssetsEvent<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> event) {
        TwNameItemConfig.clearInheritanceFallbackCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.NAME_ITEM, event.getLoadedAssets().keySet());
        }
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onNamingAssetsRemoved(
            RemovedAssetsEvent<String, TwNameItemConfig, DefaultAssetMap<String, TwNameItemConfig>> event) {
        TwNameItemConfig.clearInheritanceFallbackCache();
        emitExperimentalConfigReload(TameworkConfigFamily.NAME_ITEM, event.getRemovedAssets());
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onNamesAssetsLoaded(
            LoadedAssetsEvent<String, TwNamesConfig, DefaultAssetMap<String, TwNamesConfig>> event) {
        TwNamesConfig.clearInheritanceFallbackCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.NAMES, event.getLoadedAssets().keySet());
        }
    }

    private void onNamesAssetsRemoved(
            RemovedAssetsEvent<String, TwNamesConfig, DefaultAssetMap<String, TwNamesConfig>> event) {
        TwNamesConfig.clearInheritanceFallbackCache();
        emitExperimentalConfigReload(TameworkConfigFamily.NAMES, event.getRemovedAssets());
    }

    private void onCommandAssetsLoaded(
            LoadedAssetsEvent<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> event) {
        TwCommandItemConfig.clearInheritanceFallbackCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.COMMAND_ITEM, event.getLoadedAssets().keySet());
        }
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onCommandAssetsRemoved(
            RemovedAssetsEvent<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> event) {
        TwCommandItemConfig.clearInheritanceFallbackCache();
        emitExperimentalConfigReload(TameworkConfigFamily.COMMAND_ITEM, event.getRemovedAssets());
        requestItemFeatureConfigReloadFromAssetEvent();
    }

    private void onGlobalAssetsLoaded(
            LoadedAssetsEvent<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> event) {
        TwGlobalConfig.clearCache();
        lastGlobalConfigWarningKey = null;
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.GLOBAL, event.getLoadedAssets().keySet());
        }
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileTranquilizerRecipeVisibility();
    }

    private void onGlobalAssetsRemoved(
            RemovedAssetsEvent<String, TwGlobalConfig, DefaultAssetMap<String, TwGlobalConfig>> event) {
        TwGlobalConfig.clearCache();
        lastGlobalConfigWarningKey = null;
        emitExperimentalConfigReload(TameworkConfigFamily.GLOBAL, event.getRemovedAssets());
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileTranquilizerRecipeVisibility();
    }

    private void onCraftingRecipeAssetsLoaded(
            LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileTranquilizerRecipeVisibility();
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void onCraftingRecipeAssetsRemoved(
            RemovedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileTranquilizerRecipeVisibility();
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void onItemDropListAssetsLoaded(
            LoadedAssetsEvent<String, ItemDropList, DefaultAssetMap<String, ItemDropList>> event) {
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void onItemDropListAssetsRemoved(
            RemovedAssetsEvent<String, ItemDropList, DefaultAssetMap<String, ItemDropList>> event) {
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void reconcileTranquilizerRecipeVisibility() {
        if (tranquilizerRecipeVisibilityService == null) {
            return;
        }
        tranquilizerRecipeVisibilityService.reconcile();
    }

    private void reconcileFeedTroughWaterChargeDroplistCompat() {
        if (feedTroughWaterChargeDroplistCompatService == null) {
            return;
        }
        feedTroughWaterChargeDroplistCompatService.reconcile();
    }

    private void onCompanionAssetsLoaded(
            LoadedAssetsEvent<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> event) {
        TwCompanionConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.COMPANION, event.getLoadedAssets().keySet());
        }
    }

    private void onCompanionAssetsRemoved(
            RemovedAssetsEvent<String, TwCompanionConfig, DefaultAssetMap<String, TwCompanionConfig>> event) {
        TwCompanionConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.COMPANION, event.getRemovedAssets());
    }

    private void onInteractionAssetsLoaded(
            LoadedAssetsEvent<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> event) {
        TwInteractionConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.INTERACTION, event.getLoadedAssets().keySet());
        }
    }

    private void onInteractionAssetsRemoved(
            RemovedAssetsEvent<String, TwInteractionConfig, DefaultAssetMap<String, TwInteractionConfig>> event) {
        TwInteractionConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.INTERACTION, event.getRemovedAssets());
    }

    private void emitExperimentalConfigReload(@Nonnull TameworkConfigFamily family, @Nullable Iterable<String> changedIds) {
        if (apiEventBus == null || changedIds == null) {
            return;
        }
        java.util.ArrayList<String> normalizedIds = new java.util.ArrayList<>();
        for (String changedId : changedIds) {
            if (changedId == null || changedId.isBlank()) {
                continue;
            }
            normalizedIds.add(changedId.trim());
        }
        apiEventBus.emitConfigReload(family, normalizedIds);
    }

    private void onCoopAssetsLoaded(
            LoadedAssetsEvent<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> event) {
        TwCoopConfig.clearCoopCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.COOP, event.getLoadedAssets().keySet());
        }
    }

    private void onCoopAssetsRemoved(
            RemovedAssetsEvent<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> event) {
        TwCoopConfig.clearCoopCache();
        emitExperimentalConfigReload(TameworkConfigFamily.COOP, event.getRemovedAssets());
    }

    private void onHappinessAssetsLoaded(
            LoadedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.HAPPINESS, event.getLoadedAssets().keySet());
        }
    }

    private void onHappinessAssetsRemoved(
            RemovedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.HAPPINESS, event.getRemovedAssets());
    }

    private void onNeedsAssetsLoaded(
            LoadedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.NEEDS, event.getLoadedAssets().keySet());
        }
    }

    private void onNeedsAssetsRemoved(
            RemovedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.NEEDS, event.getRemovedAssets());
    }

    private void onBreedingAssetsLoaded(
            LoadedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.BREEDING, event.getLoadedAssets().keySet());
        }
    }

    private void onBreedingAssetsRemoved(
            RemovedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.BREEDING, event.getRemovedAssets());
    }

    private void onTraitAssetsLoaded(
            LoadedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.TRAIT, event.getLoadedAssets().keySet());
        }
    }

    private void onTraitAssetsRemoved(
            RemovedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.TRAIT, event.getRemovedAssets());
    }

    private void onDebugAssetsLoaded(
            LoadedAssetsEvent<String, TwDebugConfig, DefaultAssetMap<String, TwDebugConfig>> event) {
        TwDebugConfig.clearCache();
        applyDebugConfigDefaults();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.DEBUG, event.getLoadedAssets().keySet());
        }
    }

    private void onDebugAssetsRemoved(
            RemovedAssetsEvent<String, TwDebugConfig, DefaultAssetMap<String, TwDebugConfig>> event) {
        TwDebugConfig.clearCache();
        applyDebugConfigDefaults();
        emitExperimentalConfigReload(TameworkConfigFamily.DEBUG, event.getRemovedAssets());
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

    public CommandLinkedNpcDeathService getCommandLinkedNpcDeathService() {
        return commandLinkedNpcDeathService;
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

    public ComponentType<EntityStore, TameworkMountedNameplateComponent> getMountedNameplateComponentType() {
        return mountedNameplateComponentType;
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

    public ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> getLingeringHazardProjectileComponentType() {
        return lingeringHazardProjectileComponentType;
    }

    public ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> getProjectileImpactEffectComponentType() {
        return projectileImpactEffectComponentType;
    }

    public ComponentType<EntityStore, TameworkLingeringHazardComponent> getLingeringHazardComponentType() {
        return lingeringHazardComponentType;
    }

    public ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> getApiSelfTestFixtureMarkerComponentType() {
        return apiSelfTestFixtureMarkerComponentType;
    }

    public ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> getFeedTroughWaterChargesComponentType() {
        return feedTroughWaterChargesComponentType;
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

    public boolean isDebugSpawnerLocationEnabled() {
        return debugSpawnerLocationLogs;
    }

    public boolean setDebugSpawnerLocationEnabled(boolean enabled) {
        debugSpawnerLocationLogs = enabled;
        return debugSpawnerLocationLogs;
    }

    public boolean toggleDebugSpawnerLocationEnabled() {
        debugSpawnerLocationLogs = !debugSpawnerLocationLogs;
        return debugSpawnerLocationLogs;
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

    public boolean isDebugLagEnabled() {
        return debugLagLogs;
    }

    public boolean isDebugDespawnEnabled() {
        return debugDespawnLogs;
    }

    public boolean setDebugDespawnEnabled(boolean enabled) {
        debugDespawnLogs = enabled;
        return debugDespawnLogs;
    }

    public boolean toggleDebugDespawnEnabled() {
        debugDespawnLogs = !debugDespawnLogs;
        return debugDespawnLogs;
    }

    public String getDebugDespawnRoleFilter() {
        return debugDespawnRoleFilter;
    }

    public void clearDebugDespawnRoleFilter() {
        debugDespawnRoleFilter = null;
    }

    public String setDebugDespawnRoleFilter(String roleName) {
        debugDespawnRoleFilter = normalizeDebugDespawnRole(roleName);
        return debugDespawnRoleFilter;
    }

    public boolean matchesDebugDespawnRole(String roleName) {
        String filter = debugDespawnRoleFilter;
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (roleName == null || roleName.isBlank()) {
            return false;
        }
        String normalizedRole = normalizeDebugDespawnRole(roleName);
        if (normalizedRole == null) {
            return false;
        }
        return normalizedRole.equals(filter)
                || normalizedRole.endsWith("_" + filter)
                || normalizedRole.startsWith(filter + "_");
    }

    private static String normalizeDebugDespawnRole(String roleName) {
        if (roleName == null) {
            return null;
        }
        String normalized = roleName.trim().toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    public boolean setDebugLagEnabled(boolean enabled) {
        debugLagLogs = enabled;
        return debugLagLogs;
    }

    public boolean toggleDebugLagEnabled() {
        debugLagLogs = !debugLagLogs;
        return debugLagLogs;
    }

    public boolean isDebugCoopEnabled() {
        return debugCoopLogs;
    }

    public boolean setDebugCoopEnabled(boolean enabled) {
        debugCoopLogs = enabled;
        CoopDebugLogger.setEnabled(enabled);
        return debugCoopLogs;
    }

    public boolean toggleDebugCoopEnabled() {
        debugCoopLogs = !debugCoopLogs;
        CoopDebugLogger.setEnabled(debugCoopLogs);
        return debugCoopLogs;
    }

    public boolean isDebugBreedingEnabled() {
        return debugBreedingLogs;
    }

    public boolean setDebugBreedingEnabled(boolean enabled) {
        debugBreedingLogs = enabled;
        return debugBreedingLogs;
    }

    public boolean toggleDebugBreedingEnabled() {
        debugBreedingLogs = !debugBreedingLogs;
        return debugBreedingLogs;
    }

    public boolean isDebugNeedsConsumeDiagnosticsEnabled() {
        return debugNeedsConsumeDiagnosticsLogs;
    }

    public boolean setDebugNeedsConsumeDiagnosticsEnabled(boolean enabled) {
        debugNeedsConsumeDiagnosticsLogs = enabled;
        return debugNeedsConsumeDiagnosticsLogs;
    }

    public boolean toggleDebugNeedsConsumeDiagnosticsEnabled() {
        debugNeedsConsumeDiagnosticsLogs = !debugNeedsConsumeDiagnosticsLogs;
        return debugNeedsConsumeDiagnosticsLogs;
    }

    public boolean isDebugNeedsDamageDiagnosticsEnabled() {
        return debugNeedsDamageDiagnosticsLogs;
    }

    public boolean setDebugNeedsDamageDiagnosticsEnabled(boolean enabled) {
        debugNeedsDamageDiagnosticsLogs = enabled;
        return debugNeedsDamageDiagnosticsLogs;
    }

    public boolean toggleDebugNeedsDamageDiagnosticsEnabled() {
        debugNeedsDamageDiagnosticsLogs = !debugNeedsDamageDiagnosticsLogs;
        return debugNeedsDamageDiagnosticsLogs;
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

    @Nullable
    private ComponentType<EntityStore, NPCMountComponent> resolveNpcMountComponentTypeOrNull() {
        try {
            return NPCMountComponent.getComponentType();
        } catch (Throwable throwable) {
            return null;
        }
    }

}



