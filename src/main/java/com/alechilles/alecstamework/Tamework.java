package com.alechilles.alecstamework;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.TameworkProgressionTimeScales;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.api.internal.TameworkApiImpl;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal.TraitEffectRegistry;
import com.alechilles.alecstamework.api.internal.TraitEffectRuntime;
import com.alechilles.alecstamework.assets.TameworkAssetPackCoordinator;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.selftest.AssetPatchSelfTestPack;
import com.alechilles.alecstamework.avatarflight.AvatarFlightActivator;
import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightEquipmentVisualSystem;
import com.alechilles.alecstamework.avatarflight.AvatarFlightHudSystem;
import com.alechilles.alecstamework.avatarflight.AvatarFlightInputComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMovementSystem;
import com.alechilles.alecstamework.avatarflight.AvatarFlightRiderVisualComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightRiderVisualCleanupSystem;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfigValidationService;
import com.alechilles.alecstamework.config.assets.TwDebugConfig;
import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.damage.DamageTargetMemorySystem;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.damage.CompanionHappinessDamageImpulseSystem;
import com.alechilles.alecstamework.damage.CompanionCombatExperienceSystem;
import com.alechilles.alecstamework.damage.RespawnFallDamageGraceSystem;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileSpawnSystem;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardSystem;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectComponent;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectSystem;
import com.alechilles.alecstamework.damage.TraitDamageModifierSystem;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import com.alechilles.alecstamework.debug.PlayerInputDebugProbe;
import com.alechilles.alecstamework.debug.PlayerInputDebugSystem;
import com.alechilles.alecstamework.interactions.TameworkCommandInteraction;
import com.alechilles.alecstamework.interactions.TameworkClearFeedTroughWaterInteraction;
import com.alechilles.alecstamework.interactions.TameworkFlightAirbrakeInteraction;
import com.alechilles.alecstamework.interactions.TameworkFlightBoostInteraction;
import com.alechilles.alecstamework.interactions.TameworkFlightFlapInteraction;
import com.alechilles.alecstamework.interactions.TameworkLaunchProjectileInteraction;
import com.alechilles.alecstamework.interactions.TameworkManagedCoopCaptureCrateInteraction;
import com.alechilles.alecstamework.interactions.TameworkNameNpcInteraction;
import com.alechilles.alecstamework.interactions.TameworkSpawnInteraction;
import com.alechilles.alecstamework.npc.actions.HeldItemAttachmentInteractionService;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimProviderLifecycleInvalidator;
import com.alechilles.alecstamework.integration.creditor.CreditorIntegration;
import com.alechilles.alecstamework.integration.nameplatebuilder.NameplateBuilderBridgeLoader;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.alechilles.alecstamework.items.CommandLinkedNpcInventoryCanonicalizationSystem;
import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.alechilles.alecstamework.items.CommandLinkedNpcCoopService;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeComposition;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandTargetHudActivationTracker;
import com.alechilles.alecstamework.items.CommandTargetHudActiveSlotSystem;
import com.alechilles.alecstamework.items.CommandTargetHudInventoryChangeSystem;
import com.alechilles.alecstamework.items.CommandTargetHudService;
import com.alechilles.alecstamework.items.CommandTeleportArrivalRelocationSystem;
import com.alechilles.alecstamework.items.CoopDebugLogger;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService;
import com.alechilles.alecstamework.items.FeedTroughFoodStateSyncSystem;
import com.alechilles.alecstamework.items.FeedTroughWaterChargeDroplistCompatService;
import com.alechilles.alecstamework.items.LoadedNpcIdentityBootstrapService;
import com.alechilles.alecstamework.items.RecoveryProjectionReconciliationService;
import com.alechilles.alecstamework.items.components.TameworkFeedTroughWaterChargesComponent;
import com.alechilles.alecstamework.items.NamingFeatureHandler;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.items.SpawnerManagedCoopCaptureDetachService;
import com.alechilles.alecstamework.items.TranquilizerRecipeVisibilityService;
import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.metrics.TameworkHStatsIntegration;
import com.alechilles.alecstamework.npc.TameworkNpcBuilderRegistrar;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkFlyingCompanionComponent;
import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedGlideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkMountedNameplateComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideMountComponent;
import com.alechilles.alecstamework.npc.components.TameworkRideRiderComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.OwnerPresenceTimelineService;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.OwnerPopulationAdmissionCoordinator;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.ownership.OwnerComponentMutationService;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.reconciliation.TameworkPopulationRuntimeLifecycle;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureMarkerComponent;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.alechilles.alecstamework.ui.TameworkSettingsAnnouncementService;
import com.alechilles.alecstamework.npc.systems.CompanionProgressionBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionPassiveBreedingSystem;
import com.alechilles.alecstamework.npc.breeding.TameworkBreedingServices;
import com.alechilles.alecstamework.npc.systems.CompanionLifeStageResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CompanionAttachmentSyncSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDespawnDiagnosticsSystem;
import com.alechilles.alecstamework.npc.systems.CompanionDespawnProtectionSystem;
import com.alechilles.alecstamework.npc.systems.DynamicAttachmentEvaluationSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitBootstrapOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.CommandLinkedRevivableDropSuppressionSystem;
import com.alechilles.alecstamework.npc.systems.CommandNpcRelocationOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.RecoveryProjectionReconciliationSystem;
import com.alechilles.alecstamework.npc.systems.CompanionNeedsSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTraitStatSyncSystem;
import com.alechilles.alecstamework.npc.systems.CompanionTranquilizerPeakSystem;
import com.alechilles.alecstamework.npc.systems.FlyingCompanionControlSystem;
import com.alechilles.alecstamework.npc.systems.MountedInteractableSafetySystem;
import com.alechilles.alecstamework.npc.systems.MountedGlideCleanupSystem;
import com.alechilles.alecstamework.npc.systems.MountedGlideInputCaptureSystem;
import com.alechilles.alecstamework.npc.systems.MountedGlidePlayerVelocitySystem;
import com.alechilles.alecstamework.npc.systems.MountedNpcTeleportSafetySystem;
import com.alechilles.alecstamework.npc.systems.MountedOwnerReferenceSanitySystem;
import com.alechilles.alecstamework.npc.systems.MountedRideCleanupSystem;
import com.alechilles.alecstamework.npc.systems.MountedRideInputCaptureSystem;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
import com.alechilles.alecstamework.npc.systems.MountedRideRiderCleanupSystem;
import com.alechilles.alecstamework.npc.systems.MountedRideRiderFollowSystem;
import com.alechilles.alecstamework.npc.systems.NpcDebugDisplayResumeOnLoadSystem;
import com.alechilles.alecstamework.npc.systems.NpcMountedNameplateVisibilitySystem;
import com.alechilles.alecstamework.npc.systems.NpcNamePersistenceSystem;
import com.hypixel.hytale.assetstore.AssetMap;
import com.hypixel.hytale.assetstore.event.AssetStoreMonitorEvent;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.common.events.CommonAssetMonitorEvent;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.event.PluginSetupEvent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
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
    private AssetPatchService assetPatchService;
    private AssetPatchSelfTestPack assetPatchSelfTestPack;
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
    private LoadedNpcIdentityBootstrapService loadedNpcIdentityBootstrapService;
    private CoopResidentStateSnapshotService coopResidentStateSnapshotService;
    private ManagedCoopRuntimeComposition managedCoopRuntime;
    private Path runtimeDataDirectory;
    private TameworkPersistenceRuntime persistenceRuntime;
    private OwnerPopulationRuntime ownerPopulationRuntime;
    private TameworkApi api;
    private TameworkEventBus apiEventBus;
    private InteractionExtensionRegistry interactionExtensionRegistry;
    private TraitEffectRegistry traitEffectRegistry;
    private ApiSelfTestFixtureManager apiSelfTestFixtureManager;
    private ApiSelfTestRunner apiSelfTestRunner;
    private CompanionXpEventDebugLogService companionXpEventDebugLogService;
    private TameworkNpcBuilderRegistrar npcBuilderRegistrar;
    private TameworkHStatsIntegration hStatsIntegration;
    private CrashTelemetryService crashTelemetryService;
    private final TameworkTelemetryEvents telemetryEvents = new TameworkTelemetryEvents();
    private TameworkSettingsAnnouncementService settingsAnnouncementService;
    private final AvatarFlightActivator avatarFlightActivator = new AvatarFlightActivator();
    private boolean globalAssetsRegistered;
    private boolean companionAssetsRegistered;
    private boolean spawnerAssetsRegistered;
    private boolean namingAssetsRegistered;
    private boolean namesAssetsRegistered;
    private boolean commandAssetsRegistered;
    private boolean interactionAssetsRegistered;
    private boolean mountedGlideAssetsRegistered;
    private boolean avatarFlightAssetsRegistered;
    private boolean coopAssetsRegistered;
    private boolean foodAssetsRegistered;
    private boolean happinessAssetsRegistered;
    private boolean needsAssetsRegistered;
    private boolean breedingAssetsRegistered;
    private boolean attachmentMigrationAssetsRegistered;
    private boolean attachmentDisplayAssetsRegistered;
    private boolean dynamicAttachmentsAssetsRegistered;
    private boolean levelingAssetsRegistered;
    private boolean traitAssetsRegistered;
    private boolean talentAssetsRegistered;
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
    private ComponentType<EntityStore, TameworkAlarmComponent> alarmComponentType;
    private ComponentType<EntityStore, TameworkFlyingCompanionComponent> flyingCompanionComponentType;
    private ComponentType<EntityStore, TameworkRideMountComponent> rideMountComponentType;
    private ComponentType<EntityStore, TameworkRideRiderComponent> rideRiderComponentType;
    private ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlideComponentType;
    private ComponentType<EntityStore, TameworkMountedGlideRiderComponent> mountedGlideRiderComponentType;
    private ComponentType<EntityStore, AvatarFlightComponent> avatarFlightComponentType;
    private ComponentType<EntityStore, AvatarFlightInputComponent> avatarFlightInputComponentType;
    private ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisualComponentType;
    private ComponentType<EntityStore, TameworkLevelingComponent> levelingComponentType;
    private ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType;
    private ComponentType<EntityStore, TameworkTalentsComponent> talentsComponentType;
    private ComponentType<EntityStore, TameworkTranquilizerPeakComponent> tranquilizerPeakComponentType;
    private ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsComponentType;
    private ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> dynamicAttachmentsComponentType;
    private ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType;
    private ComponentType<EntityStore, TameworkProjectionIdentityComponent> projectionIdentityComponentType;
    private ComponentType<EntityStore, TameworkProjectileImpactEffectComponent> projectileImpactEffectComponentType;
    private ComponentType<EntityStore, TameworkLingeringHazardProjectileComponent> lingeringHazardProjectileComponentType;
    private ComponentType<EntityStore, TameworkLingeringHazardComponent> lingeringHazardComponentType;
    private ComponentType<EntityStore, ApiSelfTestFixtureMarkerComponent> apiSelfTestFixtureMarkerComponentType;
    private ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> feedTroughWaterChargesComponentType;
    private volatile boolean debugHookLogs;
    private volatile boolean debugSpawnerLogs;
    private volatile boolean debugSpawnerLocationLogs;
    private volatile boolean debugPromptLogs;
    private volatile boolean debugRideLogs;
    private volatile boolean debugDespawnLogs;
    private volatile String debugDespawnRoleFilter;
    private volatile boolean debugLagLogs;
    private volatile boolean debugCoopLogs;
    private volatile boolean debugBreedingLogs;
    private volatile boolean debugNeedsConsumeDiagnosticsLogs;
    private volatile boolean debugNeedsDamageDiagnosticsLogs;
    private volatile boolean debugNeedsSeekDiagnosticsLogs;
    private volatile boolean debugNeedsTelemetryDiagnostics;
    private volatile boolean debugHarvestLogs;
    private volatile boolean debugRespawnTraceLogs;
    private volatile boolean debugFlyingCompanionLogs;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        long startedAtNanos = System.nanoTime();
        initializeCrashTelemetry();
        try {
            setupInternal();
            int durationMs = telemetryEvents.elapsedMillis(startedAtNanos);
            telemetryEvents.recordLifecycle(
                    "plugin_setup",
                    durationMs,
                    true,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("setup")
                            .operation("setupInternal")
                            .detail("Tamework setupInternal completed.")
                            .build()
            );
            telemetryEvents.recordPerformance(
                    "plugin_setup_duration",
                    durationMs,
                    (double) durationMs,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("setup")
                            .operation("setupInternal")
                            .detail("Tamework plugin setup duration.")
                            .build()
            );
            if (crashTelemetryService != null) {
                crashTelemetryService.recordBreadcrumb("lifecycle", "Tamework setup completed.");
            }
        } catch (Throwable throwable) {
            int durationMs = telemetryEvents.elapsedMillis(startedAtNanos);
            telemetryEvents.recordLifecycle(
                    "plugin_setup",
                    durationMs,
                    false,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("setup")
                            .operation("setupInternal")
                            .detail("Tamework setupInternal failed.")
                            .build()
            );
            telemetryEvents.recordPerformance(
                    "plugin_setup_duration",
                    durationMs,
                    (double) durationMs,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("setup")
                            .operation("setupInternal")
                            .detail("Failed Tamework plugin setup duration.")
                            .detail("result", "failed")
                            .build()
            );
            telemetryEvents.recordError(
                    "plugin_setup_failed",
                    throwable,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("setup")
                            .operation("setupInternal")
                            .detail("Tamework setupInternal threw an exception.")
                            .build()
            );
            captureSetupFailure(throwable);
            throw throwable;
        }
    }

    private void setupInternal() {
        itemFeatureRegistry = new ItemFeatureRegistry();
        nameItemRegistry = new NameItemRegistry();
        commandItemRegistry = new CommandItemRegistry();
        assetPackCoordinator = new TameworkAssetPackCoordinator(this);
        assetPatchSelfTestPack = new AssetPatchSelfTestPack(getDataDirectory(), getManifest(), getLogger());
        assetPatchService = new AssetPatchService(this, assetPatchSelfTestPack);
        configOverrideManager = new TwConfigOverrideManager(this);
        tranquilizerRecipeVisibilityService = new TranquilizerRecipeVisibilityService();
        feedTroughWaterChargeDroplistCompatService = new FeedTroughWaterChargeDroplistCompatService();
        npcBuilderRegistrar = new TameworkNpcBuilderRegistrar(this);
        hStatsIntegration = new TameworkHStatsIntegration(this);
        assetPackCoordinator.registerEarlyAssetPackOrderingHook();
        assetPatchService.registerLoadHook();
        ServerManager.get().registerSubPacketHandlers(MountedRidePacketHandler::new);
        // Register the custom item interaction used by spawner items.
        Interaction.CODEC.register("TameworkSpawn", TameworkSpawnInteraction.class, TameworkSpawnInteraction.CODEC);
        // Register the custom item interaction used by naming items.
        Interaction.CODEC.register("TameworkNameNpc", TameworkNameNpcInteraction.class, TameworkNameNpcInteraction.CODEC);
        // Register the custom item interaction used by command items.
        Interaction.CODEC.register("TameworkCommand", TameworkCommandInteraction.class, TameworkCommandInteraction.CODEC);
        // Cut over at the item boundary; ordinary unmanaged use delegates while canonical evidence fails closed.
        Interaction.CODEC.register(
                "TameworkManagedCoopCaptureCrate",
                TameworkManagedCoopCaptureCrateInteraction.class,
                TameworkManagedCoopCaptureCrateInteraction.CODEC
        );
        // Register the custom item interactions used by Dragon Reins flight controls.
        Interaction.CODEC.register(
                "TameworkFlightFlap",
                TameworkFlightFlapInteraction.class,
                TameworkFlightFlapInteraction.CODEC
        );
        Interaction.CODEC.register(
                "TameworkFlightAirbrake",
                TameworkFlightAirbrakeInteraction.class,
                TameworkFlightAirbrakeInteraction.CODEC
        );
        Interaction.CODEC.register(
                "TameworkFlightBoost",
                TameworkFlightBoostInteraction.class,
                TameworkFlightBoostInteraction.CODEC
        );
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
        registerMountedGlideAssets();
        registerAvatarFlightAssets();
        registerFoodAssets();
        registerHappinessAssets();
        registerNeedsAssets();
        registerBreedingAssets();
        registerAttachmentMigrationAssets();
        registerAttachmentDisplayAssets();
        registerDynamicAttachmentsAssets();
        registerLevelingAssets();
        registerTraitAssets();
        registerTalentAssets();
        registerDebugAssets();
        CreditorIntegration.setup(this);
        getEventRegistry().register(LoadedAssetsEvent.class, CraftingRecipe.class, this::onCraftingRecipeAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, CraftingRecipe.class, this::onCraftingRecipeAssetsRemoved);
        getEventRegistry().register(LoadedAssetsEvent.class, Item.class, this::onItemAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, ParticleSystem.class, this::onParticleSystemAssetsLoaded);
        getEventRegistry().register(AssetStoreMonitorEvent.class, this::onAssetStoreMonitor);
        getEventRegistry().register(CommonAssetMonitorEvent.class, this::onCommonAssetMonitor);
        getEventRegistry().register(LoadedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsRemoved);

        TameworkComponentRegistrar.RegisteredComponents components = TameworkComponentRegistrar.register(this);
        ownerComponentType = components.owner();
        tamedComponentType = components.tamed();
        hookComponentType = components.hook();
        npcNameComponentType = components.npcName();
        mountedNameplateComponentType = components.mountedNameplate();
        commandLinksComponentType = components.commandLinks();
        happinessComponentType = components.happiness();
        needsComponentType = components.needs();
        breedingComponentType = components.breeding();
        alarmComponentType = components.alarm();
        flyingCompanionComponentType = components.flyingCompanion();
        rideMountComponentType = components.rideMount();
        rideRiderComponentType = components.rideRider();
        mountedGlideComponentType = components.mountedGlide();
        mountedGlideRiderComponentType = components.mountedGlideRider();
        avatarFlightComponentType = components.avatarFlight();
        avatarFlightInputComponentType = components.avatarFlightInput();
        avatarFlightRiderVisualComponentType = components.avatarFlightRiderVisual();
        levelingComponentType = components.leveling();
        traitsComponentType = components.traits();
        talentsComponentType = components.talents();
        tranquilizerPeakComponentType = components.tranquilizerPeak();
        attachmentsComponentType = components.attachments();
        dynamicAttachmentsComponentType = components.dynamicAttachments();
        lifeStageComponentType = components.lifeStage();
        projectionIdentityComponentType = components.projectionIdentity();
        projectileImpactEffectComponentType = components.projectileImpactEffect();
        lingeringHazardProjectileComponentType = components.lingeringHazardProjectile();
        lingeringHazardComponentType = components.lingeringHazard();
        apiSelfTestFixtureMarkerComponentType = components.apiSelfTestFixtureMarker();
        feedTroughWaterChargesComponentType = components.feedTroughWaterCharges();

        getEntityStoreRegistry().registerSystem(
                new NpcNamePersistenceSystem(npcNameComponentType, NPCEntity.getComponentType())
        );
        getEntityStoreRegistry().registerSystem(
                new PlayerInputDebugSystem(
                        PlayerInput.getComponentType(),
                        UUIDComponent.getComponentType(),
                        MovementStatesComponent.getComponentType(),
                        HeadRotation.getComponentType(),
                        TransformComponent.getComponentType(),
                        Velocity.getComponentType(),
                        ModelComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new AvatarFlightMovementSystem(
                        avatarFlightComponentType,
                        avatarFlightInputComponentType,
                        Velocity.getComponentType(),
                        MovementStatesComponent.getComponentType(),
                        HeadRotation.getComponentType(),
                        TransformComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new AvatarFlightHudSystem(
                        avatarFlightComponentType,
                        avatarFlightInputComponentType,
                        Player.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new AvatarFlightEquipmentVisualSystem(
                        avatarFlightComponentType,
                        avatarFlightRiderVisualComponentType,
                        EntityTrackerSystems.Visible.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(
                new AvatarFlightRiderVisualCleanupSystem(
                        avatarFlightRiderVisualComponentType,
                        avatarFlightComponentType
                )
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
            getEntityStoreRegistry().registerSystem(
                    new MountedGlideInputCaptureSystem(
                            npcMountComponentType,
                            PlayerInput.getComponentType(),
                            MovementStatesComponent.getComponentType(),
                            HeadRotation.getComponentType(),
                            mountedGlideRiderComponentType,
                            mountedGlideComponentType,
                            UUIDComponent.getComponentType(),
                            TransformComponent.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedGlidePlayerVelocitySystem(
                            mountedGlideComponentType,
                            npcMountComponentType,
                            TransformComponent.getComponentType(),
                            Velocity.getComponentType(),
                            MovementStatesComponent.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedGlideCleanupSystem(
                            npcMountComponentType,
                            mountedGlideRiderComponentType,
                            mountedGlideComponentType,
                            UUIDComponent.getComponentType(),
                            NPCEntity.getComponentType(),
                            DeathComponent.getComponentType(),
                            Player.getComponentType()
                    )
            );
        }
        ComponentType<EntityStore, MountedComponent> mountedComponentType = resolveMountedComponentTypeOrNull();
        if (mountedComponentType == null) {
            getLogger().at(Level.WARNING).log(
                    "Mount plugin mounted component type unavailable during setup; skipping legacy Tamework ride systems."
            );
        } else {
            getEntityStoreRegistry().registerSystem(
                    new MountedRideInputCaptureSystem(
                            mountedComponentType,
                            PlayerInput.getComponentType(),
                            rideRiderComponentType,
                            rideMountComponentType,
                            UUIDComponent.getComponentType(),
                            MovementStatesComponent.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedRideCleanupSystem(
                            mountedComponentType,
                            rideRiderComponentType,
                            rideMountComponentType,
                            UUIDComponent.getComponentType(),
                            NPCEntity.getComponentType(),
                            TransformComponent.getComponentType(),
                            DeathComponent.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedRideRiderFollowSystem(
                            mountedComponentType,
                            rideRiderComponentType,
                            rideMountComponentType,
                            TransformComponent.getComponentType()
                    )
            );
            getEntityStoreRegistry().registerSystem(
                    new MountedRideRiderCleanupSystem(
                            mountedComponentType,
                            rideRiderComponentType,
                            rideMountComponentType,
                            UUIDComponent.getComponentType(),
                            NPCEntity.getComponentType(),
                            DeathComponent.getComponentType()
                    )
            );
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
                new CompanionTranquilizerPeakSystem(
                        NPCEntity.getComponentType(),
                        EffectControllerComponent.getComponentType(),
                        tranquilizerPeakComponentType
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
        registerOptionalCommandLinkedRevivableDropSuppressionSystem();
        getEntityStoreRegistry().registerSystem(new CompanionAttachmentSyncSystem());
        getEntityStoreRegistry().registerSystem(
                new DynamicAttachmentEvaluationSystem(
                        NPCEntity.getComponentType(),
                        attachmentsComponentType,
                        dynamicAttachmentsComponentType,
                        ownerComponentType,
                        tamedComponentType,
                        lifeStageComponentType,
                        happinessComponentType,
                        needsComponentType,
                        traitsComponentType,
                        commandLinksComponentType
                )
        );
        getEntityStoreRegistry().registerSystem(new CompanionDespawnProtectionSystem());
        getEntityStoreRegistry().registerSystem(new FlyingCompanionControlSystem());
        getEntityStoreRegistry().registerSystem(
                new CompanionDespawnDiagnosticsSystem(
                        NPCEntity.getComponentType(),
                        tamedComponentType,
                        ownerComponentType,
                        resolveOptionalSpawnMarkerReferenceComponentType(),
                        resolveOptionalSpawnBeaconReferenceComponentType(),
                        UUIDComponent.getComponentType()
                )
        );
        getEntityStoreRegistry().registerSystem(new CompanionNeedsSystem());
        getEntityStoreRegistry().registerSystem(new CompanionPassiveBreedingSystem());
        commandNpcRelocationService = new CommandNpcRelocationService(getLogger());
        runtimeDataDirectory = new TameworkDataPathService(getLogger())
                .resolveAndMigrateDataDirectory(getDataDirectory());
        persistenceRuntime = TameworkPersistenceRuntime.initialize(runtimeDataDirectory, getLogger());
        ownerPopulationRuntime = TameworkPopulationRuntimeLifecycle.initialize(
                persistenceRuntime, getLogger()
        );
        commandNpcRelocationService.setRelocationAdmissionService(ownerPopulationRuntime.relocationAdmissionService());
        apiEventBus = new TameworkEventBus(getLogger());
        interactionExtensionRegistry = new InteractionExtensionRegistry(getLogger());
        HeldItemAttachmentInteractionService heldItemAttachmentInteractions =
                new HeldItemAttachmentInteractionService(getLogger());
        interactionExtensionRegistry.registerBuiltInRequirement(
                HeldItemAttachmentInteractionService.MODEL_SUPPORT_REQUIREMENT_ID,
                heldItemAttachmentInteractions::modelSupportsAttachment
        );
        interactionExtensionRegistry.registerBuiltInRequirement(
                HeldItemAttachmentInteractionService.EXCHANGE_AVAILABLE_REQUIREMENT_ID,
                heldItemAttachmentInteractions::attachmentExchangeAvailable
        );
        interactionExtensionRegistry.registerBuiltInEffect(
                HeldItemAttachmentInteractionService.SET_FROM_HELD_ITEM_EFFECT_ID,
                heldItemAttachmentInteractions::setAttachmentFromHeldItem
        );
        interactionExtensionRegistry.registerBuiltInEffect(
                HeldItemAttachmentInteractionService.EXCHANGE_ATTACHMENT_EFFECT_ID,
                heldItemAttachmentInteractions::exchangeAttachment
        );
        traitEffectRegistry = new TraitEffectRegistry(getLogger(), persistenceRuntime.getNpcProfileRepository());
        persistenceRuntime.getNpcProfileRepository().setChangeObserver(apiEventBus);
        commandLinkedNpcStateSnapshotService = TameworkPopulationRuntimeLifecycle
                .createStateSnapshotService(persistenceRuntime, ownerPopulationRuntime);
        loadedNpcIdentityBootstrapService = new LoadedNpcIdentityBootstrapService(
                commandLinkedNpcStateSnapshotService.getLoadedNpcIdentityIndex(),
                getLogger()
        );
        RecoveryProjectionReconciliationService recoveryProjectionReconciliationService =
                new RecoveryProjectionReconciliationService(
                        persistenceRuntime.getNpcRecoveryOperationRepository());
        getEntityStoreRegistry().registerSystem(
                new RecoveryProjectionReconciliationSystem(
                        recoveryProjectionReconciliationService,
                        NPCEntity.getComponentType(),
                        UUIDComponent.getComponentType(),
                        projectionIdentityComponentType,
                        commandLinksComponentType
                )
        );
        SimpleClaimsTamedDamagePolicy damagePolicy = new SimpleClaimsTamedDamagePolicy();
        api = new TameworkApiImpl(
                persistenceRuntime, apiEventBus,
                commandLinkedNpcStateSnapshotService,
                interactionExtensionRegistry,
                traitEffectRegistry,
                damagePolicy,
                ownerPopulationRuntime.populationPolicyAuthority()
        );
        ClaimProviderLifecycleInvalidator claimProviderLifecycleInvalidator =
                new ClaimProviderLifecycleInvalidator(provider -> {
                    OwnerPopulationRuntime runtime = ownerPopulationRuntime;
                    if (runtime != null) {
                        runtime.claimProviderRegistry().onPluginLifecycleChanged(provider);
                    }
                    if (provider == ClaimIntegrationProvider.SIMPLE_CLAIMS) {
                        damagePolicy.onRuntimeSettingsChanged();
                    }
                });
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PluginSetupEvent.class,
                claimProviderLifecycleInvalidator::onPluginSetup,
                "claim provider lifecycle invalidation"
        );
        companionXpEventDebugLogService = new CompanionXpEventDebugLogService(
                () -> api,
                message -> getLogger().at(Level.INFO).log(message)
        );
        apiSelfTestFixtureManager = new ApiSelfTestFixtureManager(
                persistenceRuntime, ownerPopulationRuntime
        );
        apiSelfTestRunner = new ApiSelfTestRunner();
        commandLinkedNpcCaptureService = new CommandLinkedNpcCaptureService(
                persistenceRuntime.getCaptureRepository(),
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcProfileRepository()
        );
        commandLinkedNpcCoopService = new CommandLinkedNpcCoopService(
                persistenceRuntime.getCoopLedgerRepository(),
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcProfileRepository(),
                persistenceRuntime.getManagedCoopServices().residentIndex(),
                persistenceRuntime.getManagedCoopServices()
                        .compositeIndexRefreshService()::isTrusted
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
                persistenceRuntime.getHealthService(),
                persistenceRuntime.getNpcIdentityRepository(),
                commandLinkedNpcStateSnapshotService.getLoadedNpcIdentityIndex()
        );
        TameworkPopulationRuntimeLifecycle.LinkedServices populationServices =
                new TameworkPopulationRuntimeLifecycle.LinkedServices(
                        commandLinkedNpcCaptureService,
                        commandLinkedNpcCoopService,
                        commandLinkedNpcDeathService,
                        commandLinkedNpcLostService
                );
        coopResidentStateSnapshotService = new CoopResidentStateSnapshotService();
        managedCoopRuntime = new ManagedCoopRuntimeComposition(
                persistenceRuntime,
                ownerPopulationRuntime,
                coopResidentStateSnapshotService,
                commandLinkedNpcStateSnapshotService.getLoadedNpcIdentityIndex(),
                projectionIdentityComponentType
        );
        commandNpcRelocationService.setRelocationDropListener(commandLinkedNpcLostService::recordLostFromRelocationDrop);
        getEntityStoreRegistry().registerSystem(managedCoopRuntime.staleEntitySuppressionSystem());
        getEntityStoreRegistry().registerSystem(managedCoopRuntime.sourceRetirementSystem());
        getEntityStoreRegistry().registerSystem(
                new CommandNpcRelocationOnLoadSystem(
                        commandNpcRelocationService,
                        commandLinkedNpcDeathService,
                        commandLinkedNpcLostService,
                        commandLinkedNpcStateSnapshotService
                )
        );
        TameworkPopulationRuntimeLifecycle.registerSystems(
                getEntityStoreRegistry(),
                ownerPopulationRuntime,
                populationServices,
                ownerComponentType,
                message -> getLogger().at(Level.WARNING).log(message)
        );
        getChunkStoreRegistry().registerSystem(managedCoopRuntime.runtimeSystem());
        getChunkStoreRegistry().registerSystem(new FeedTroughFoodStateSyncSystem());

        // Damage event is needed for owner damage filtering; avoid double-registration.
        try {
            getEntityStoreRegistry().registerEntityEventType(Damage.class);
        } catch (IllegalArgumentException ex) {
            getLogger().at(Level.INFO).log("Damage event type already registered; skipping registration.");
        }

        // Register damage filter system (configurable owner protection).
        getEntityStoreRegistry().registerSystem(new DamageTargetMemorySystem());
        getEntityStoreRegistry().registerSystem(new RespawnFallDamageGraceSystem());
        getEntityStoreRegistry().registerSystem(new OwnerDamageFilterSystem(getLogger(), damagePolicy));
        getEntityStoreRegistry().registerSystem(new TraitDamageModifierSystem());
        getEntityStoreRegistry().registerSystem(new CompanionHappinessDamageImpulseSystem());
        getEntityStoreRegistry().registerSystem(new CompanionCombatExperienceSystem());
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
        NameplateBuilderBridgeLoader.initialize(this);

        // Core handler for capture/spawn flows.
        spawnerFeatureHandler = new SpawnerFeatureHandler(
                getLogger(),
                itemFeatureRegistry,
                commandLinkedNpcCaptureService,
                commandLinkedNpcCoopService,
                commandNpcRelocationService,
                commandLinkedNpcLostService,
                translationRegistry,
                new SpawnerManagedCoopCaptureDetachService(
                        persistenceRuntime.getManagedCoopServices().residentIndex(),
                        persistenceRuntime.getManagedCoopServices().compositeIndexRefreshService()
                )
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
                commandLinkedNpcStateSnapshotService,
                persistenceRuntime,
                ownerPopulationRuntime.identityResolver()
        );
        getEntityStoreRegistry().registerSystem(
                new CommandTeleportArrivalRelocationSystem(commandItemFeatureHandler)
        );
        getEntityStoreRegistry().registerSystem(
                new CommandLinkedNpcInventoryCanonicalizationSystem(commandItemFeatureHandler)
        );
        CommandTargetHudActivationTracker commandTargetHudActivationTracker = new CommandTargetHudActivationTracker();
        getEntityStoreRegistry().registerSystem(
                new CommandTargetHudActiveSlotSystem(commandTargetHudActivationTracker)
        );
        getEntityStoreRegistry().registerSystem(
                new CommandTargetHudInventoryChangeSystem(commandTargetHudActivationTracker)
        );
        getEntityStoreRegistry().registerSystem(
                new CommandTargetHudService(commandItemRegistry, commandTargetHudActivationTracker)
        );

        // Register /tw commands if the server supports it.
        if (getCommandRegistry() != null) {
            getCommandRegistry().registerCommand(new TameworkCommandRoot());
        }
        applyDebugConfigDefaults();
        settingsAnnouncementService = new TameworkSettingsAnnouncementService(this);

        // Global listener to enforce owner-only interactions.
        boolean identityBootstrapListenerRegistered = TameworkEventRegistrationSupport.registerGlobal(
                this,
                StartWorldEvent.class,
                loadedNpcIdentityBootstrapService::onStartWorld,
                "loaded NPC identity bootstrap"
        );
        if (identityBootstrapListenerRegistered) {
            loadedNpcIdentityBootstrapService.bootstrapUniverse();
        }
        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(translationRegistry, getLogger());
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerInteractEvent.class,
                PlayerInputDebugProbe::logPlayerInteract,
                "player input debug interaction logging"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerInteractEvent.class,
                ownerInteractionListener::onPlayerInteract,
                "owner interaction enforcement"
        );
        OwnerPresenceTimelineService ownerPresenceTimelineService = OwnerPresenceTimelineService.get();
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerConnectEvent.class,
                ownerPresenceTimelineService::onPlayerConnect,
                "owner presence connect tracking"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerDisconnectEvent.class,
                ownerPresenceTimelineService::onPlayerDisconnect,
                "owner presence disconnect tracking"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerDisconnectEvent.class,
                avatarFlightActivator::onPlayerDisconnect,
                "avatar flight disconnect cleanup"
        );
        if (settingsAnnouncementService != null) {
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    PlayerConnectEvent.class,
                    settingsAnnouncementService::onPlayerConnect,
                    "settings announcement connect reset"
            );
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    PlayerDisconnectEvent.class,
                    settingsAnnouncementService::onPlayerDisconnect,
                    "settings announcement disconnect reset"
            );
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    PlayerReadyEvent.class,
                    settingsAnnouncementService::onPlayerReady,
                    "settings announcement ready prompt"
            );
        }
        if (namingFeatureHandler != null) {
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    PlayerChatEvent.class,
                    namingFeatureHandler::onPlayerChat,
                    "name item chat capture"
            );
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    PlayerDisconnectEvent.class,
                    namingFeatureHandler::onPlayerDisconnect,
                    "name item disconnect cleanup"
            );
        }
        if (commandItemFeatureHandler != null) {
            TameworkEventRegistrationSupport.registerGlobal(
                    this,
                    AddPlayerToWorldEvent.class,
                    commandItemFeatureHandler::onAddPlayerToWorld,
                    "command item relocation resume"
            );
        }
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                AddPlayerToWorldEvent.class,
                this::onPlayerAddedToWorldForOverrides,
                "loaded world override initialization"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                RemoveWorldEvent.class,
                this::onWorldRemovedForCrashTelemetry,
                "crash telemetry world cleanup"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                RemoveWorldEvent.class,
                this::onWorldRemovedForCompanionRecovery,
                "delete-on-remove companion recovery"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                RemoveWorldEvent.class,
                this::onWorldRemovedForProgressionTiming,
                "progression timing world cleanup"
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
        long startedAtNanos = System.nanoTime();
        try {
            startInternal();
            int durationMs = telemetryEvents.elapsedMillis(startedAtNanos);
            telemetryEvents.recordLifecycle(
                    "plugin_start",
                    durationMs,
                    true,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("start")
                            .operation("startInternal")
                            .detail("Tamework startInternal completed.")
                            .build()
            );
            telemetryEvents.recordPerformance(
                    "plugin_start_duration",
                    durationMs,
                    (double) durationMs,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("start")
                            .operation("startInternal")
                            .detail("Tamework plugin start duration.")
                            .build()
            );
            if (crashTelemetryService != null) {
                crashTelemetryService.recordBreadcrumb("lifecycle", "Tamework start completed.");
            }
        } catch (Throwable throwable) {
            int durationMs = telemetryEvents.elapsedMillis(startedAtNanos);
            telemetryEvents.recordLifecycle(
                    "plugin_start",
                    durationMs,
                    false,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("start")
                            .operation("startInternal")
                            .detail("Tamework startInternal failed.")
                            .build()
            );
            telemetryEvents.recordPerformance(
                    "plugin_start_duration",
                    durationMs,
                    (double) durationMs,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("start")
                            .operation("startInternal")
                            .detail("Failed Tamework plugin start duration.")
                            .detail("result", "failed")
                            .build()
            );
            telemetryEvents.recordError(
                    "plugin_start_failed",
                    throwable,
                    TameworkTelemetryEvents.context()
                            .subsystem("plugin")
                            .phase("start")
                            .operation("startInternal")
                            .detail("Tamework startInternal threw an exception.")
                            .build()
            );
            captureStartFailure(throwable);
            throw throwable;
        }
    }

    private void startInternal() {
        OwnerPresenceTimelineService.get().seedOnlinePlayersFromUniverse();
        CreditorIntegration.start(this);
        initializeOverridesForLoadedWorlds();
        if (ownerPopulationRuntime != null && itemFeatureRegistry != null) {
            TameworkPopulationRuntimeLifecycle.start(ownerPopulationRuntime, Universe.get(),
                    ownerComponentType, itemFeatureRegistry,
                    loadedNpcIdentityBootstrapService::awaitCurrentBootstrap);
        }
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
        if (hStatsIntegration != null) {
            hStatsIntegration.close();
            hStatsIntegration = null;
        }
        if (crashTelemetryService != null) {
            crashTelemetryService.shutdown();
        }
        if (companionXpEventDebugLogService != null) {
            companionXpEventDebugLogService.close();
            companionXpEventDebugLogService = null;
        }
        overrideInitializedScopeKeys.clear();
        if (api instanceof TameworkApiImpl implementation) {
            implementation.close();
        }
        api = null;
        if (managedCoopRuntime != null) {
            managedCoopRuntime.close();
            managedCoopRuntime = null;
        }
        if (commandNpcRelocationService != null) {
            commandNpcRelocationService.close();
            commandNpcRelocationService = null;
        }
        TameworkBreedingServices.shutdownShared();
        if (persistenceRuntime != null) {
            persistenceRuntime.getNpcProfileRepository().setChangeObserver(null);
        }
        if (apiEventBus != null) {
            apiEventBus.close();
            apiEventBus = null;
        }
        if (ownerPopulationRuntime != null) {
            ownerPopulationRuntime.close();
            ownerPopulationRuntime = null;
        }
        if (persistenceRuntime != null) {
            persistenceRuntime.close();
            persistenceRuntime = null;
        }
        runtimeDataDirectory = null;
        apiSelfTestFixtureManager = null;
        apiSelfTestRunner = null;
        assetPatchService = null;
        crashTelemetryService = null;
        settingsAnnouncementService = null;
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
                    .log("Failed to initialize Tamework embedded telemetry; continuing without telemetry.");
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

    private void onWorldRemovedForCompanionRecovery(@Nonnull RemoveWorldEvent event) {
        if (event != null && !event.isCancelled() && commandNpcRelocationService != null) {
            commandNpcRelocationService.onWorldRemoved(event.getWorld());
        }
    }

    private void onWorldRemovedForProgressionTiming(@Nonnull RemoveWorldEvent event) {
        if (event != null) {
            World world = event.getWorld();
            String worldName = world != null ? world.getName() : null;
            if (managedCoopRuntime != null && worldName != null && !worldName.isBlank()) {
                managedCoopRuntime.invalidateManagedAuthorityWorld(worldName);
            }
            if (world != null && world.getEntityStore() != null
                    && world.getEntityStore().getStore() != null) {
                TameworkBreedingServices.shared().clearScope(
                        world.getEntityStore().getStore());
            }
            TameworkProgressionTimeScales.clearWorldScale(event.getWorld());
        }
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
            telemetryEvents.recordError(
                    "world_override_reload_errors",
                    null,
                    TameworkTelemetryEvents.context()
                            .subsystem("config")
                            .featureKey("config_overrides")
                            .operation("reload_overrides")
                            .target("world_overrides")
                            .detail("Loaded overrides with " + reloadResult.getErrors().size() + " error(s).")
                            .detail("overrideErrorCount", reloadResult.getErrors().size())
                            .build()
            );
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
    public AssetPatchService getAssetPatchService() {
        return assetPatchService;
    }

    @Nullable
    public AssetPatchSelfTestPack getAssetPatchSelfTestPack() {
        return assetPatchSelfTestPack;
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
    public OwnerPopulationIndex getOwnerPopulationIndex() {
        return ownerPopulationRuntime == null ? null : ownerPopulationRuntime.index();
    }

    @Nullable
    public CompanionIdentityResolver getCompanionIdentityResolver() {
        return ownerPopulationRuntime == null ? null : ownerPopulationRuntime.identityResolver();
    }

    @Nullable
    public OwnerPopulationAdmissionCoordinator getOwnerPopulationAdmissionCoordinator() {
        return ownerPopulationRuntime == null ? null : ownerPopulationRuntime.admissionCoordinator();
    }

    @Nullable
    public OwnerComponentMutationService getOwnerComponentMutationService() {
        return ownerPopulationRuntime == null ? null : ownerPopulationRuntime.mutationService();
    }

    @Nullable
    public OwnerMutationScheduler getOwnerMutationScheduler() {
        return ownerPopulationRuntime == null ? null : ownerPopulationRuntime.mutationScheduler();
    }

    @Nullable
    public OwnerPopulationRuntime getOwnerPopulationRuntime() {
        return ownerPopulationRuntime;
    }

    @Nullable
    public TameworkApi getApi() {
        return api;
    }

    @Nullable
    public CompanionXpEventDebugLogService getCompanionXpEventDebugLogService() {
        return companionXpEventDebugLogService;
    }

    @Nullable
    public TameworkEventBus getApiEventBus() {
        return apiEventBus;
    }

    @Nullable
    public CrashTelemetryService getCrashTelemetryService() {
        return crashTelemetryService;
    }

    @Nonnull
    public TameworkTelemetryEvents getTelemetryEvents() {
        return telemetryEvents;
    }

    @Nullable
    public TameworkSettingsAnnouncementService getSettingsAnnouncementService() {
        return settingsAnnouncementService;
    }

    @Nullable
    public InteractionExtensionRuntime getInteractionExtensionRuntime() {
        return interactionExtensionRegistry;
    }

    @Nullable
    public TraitEffectRuntime getTraitEffectRuntime() {
        return traitEffectRegistry;
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
        setDebugRideEnabled(commands.isRide());
        setDebugDespawnEnabled(commands.isDespawn());
        setDebugLagEnabled(commands.isLag());
        setDebugCoopEnabled(commands.isCoop());
        setDebugBreedingEnabled(commands.isBreeding());
        setDebugNeedsConsumeDiagnosticsEnabled(commands.isNeedsConsumeDiagnostics());
        setDebugNeedsDamageDiagnosticsEnabled(commands.isNeedsDamageDiagnostics());
        setDebugNeedsSeekDiagnosticsEnabled(commands.isNeedsSeekDiagnostics());
        setDebugNeedsTelemetryDiagnosticsEnabled(commands.isNeedsTelemetryDiagnostics());
        setDebugHarvestEnabled(commands.isHarvest());
        setDebugRespawnTraceEnabled(commands.isRespawnTrace());
        setDebugFlyingCompanionEnabled(commands.isFlyingCompanion());
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
                        + ", ride=" + isDebugRideEnabled()
                        + ", despawn=" + isDebugDespawnEnabled()
                        + ", lag=" + isDebugLagEnabled()
                        + ", coop=" + isDebugCoopEnabled()
                        + ", breeding=" + isDebugBreedingEnabled()
                        + ", needsConsumeDiagnostics=" + isDebugNeedsConsumeDiagnosticsEnabled()
                        + ", needsDamageDiagnostics=" + isDebugNeedsDamageDiagnosticsEnabled()
                        + ", needsSeekDiagnostics=" + isDebugNeedsSeekDiagnosticsEnabled()
                        + ", needsTelemetryDiagnostics=" + isDebugNeedsTelemetryDiagnosticsEnabled()
                        + ", harvest=" + isDebugHarvestEnabled()
                        + ", respawnTrace=" + isDebugRespawnTraceEnabled()
                        + ", flyingCompanion=" + isDebugFlyingCompanionEnabled()
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

    private void registerOptionalCommandLinkedRevivableDropSuppressionSystem() {
        try {
            getEntityStoreRegistry().registerSystem(new CommandLinkedRevivableDropSuppressionSystem());
        } catch (RuntimeException | LinkageError error) {
            getLogger().at(Level.WARNING).withCause(error).log(
                    "Skipping command-linked revivable drop suppression system because required NPC damage dependencies "
                            + "are unavailable during setup."
            );
        }
    }

    @Nullable
    private ComponentType<EntityStore, SpawnMarkerReference> resolveOptionalSpawnMarkerReferenceComponentType() {
        try {
            return SpawnMarkerReference.getComponentType();
        } catch (RuntimeException | LinkageError error) {
            getLogger().at(Level.WARNING).withCause(error).log(
                    "SpawnMarkerReference component type is unavailable; companion despawn diagnostics will skip marker "
                            + "reference tracking."
            );
            return null;
        }
    }

    @Nullable
    private ComponentType<EntityStore, SpawnBeaconReference> resolveOptionalSpawnBeaconReferenceComponentType() {
        try {
            return SpawnBeaconReference.getComponentType();
        } catch (RuntimeException | LinkageError error) {
            getLogger().at(Level.WARNING).withCause(error).log(
                    "SpawnBeaconReference component type is unavailable; companion despawn diagnostics will skip beacon "
                            + "reference tracking."
            );
            return null;
        }
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

    private void registerMountedGlideAssets() {
        if (mountedGlideAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwMountedGlideConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Mounts/Glide")
                        .setCodec(TwMountedGlideConfig.CODEC)
                        .setKeyFunction(TwMountedGlideConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwMountedGlideConfig.class, this::onMountedGlideAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwMountedGlideConfig.class, this::onMountedGlideAssetsRemoved);
        mountedGlideAssetsRegistered = true;
    }

    private void registerAvatarFlightAssets() {
        if (avatarFlightAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwAvatarFlightConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/AvatarFlight")
                        .setCodec(TwAvatarFlightConfig.CODEC)
                        .setKeyFunction(TwAvatarFlightConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwAvatarFlightConfig.class, this::onAvatarFlightAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwAvatarFlightConfig.class, this::onAvatarFlightAssetsRemoved);
        avatarFlightAssetsRegistered = true;
    }

    private void registerCoopAssets() {
        if (coopAssetsRegistered) {
            return;
        }
        TwCoopConfigValidationService.resetWarnings();
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

    private void registerFoodAssets() {
        if (foodAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwFoodConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Food")
                        .setCodec(TwFoodConfig.CODEC)
                        .setKeyFunction(TwFoodConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwFoodConfig.class, this::onFoodAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwFoodConfig.class, this::onFoodAssetsRemoved);
        foodAssetsRegistered = true;
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

    private void registerAttachmentMigrationAssets() {
        if (attachmentMigrationAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwAttachmentMigrationConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/AttachmentMigrations")
                        .setCodec(TwAttachmentMigrationConfig.CODEC)
                        .setKeyFunction(TwAttachmentMigrationConfig::getId)
                        .build()
        );
        getEventRegistry().register(
                LoadedAssetsEvent.class,
                TwAttachmentMigrationConfig.class,
                this::onAttachmentMigrationAssetsLoaded
        );
        getEventRegistry().register(
                RemovedAssetsEvent.class,
                TwAttachmentMigrationConfig.class,
                this::onAttachmentMigrationAssetsRemoved
        );
        attachmentMigrationAssetsRegistered = true;
    }

    private void registerAttachmentDisplayAssets() {
        if (attachmentDisplayAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwAttachmentDisplayConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/AttachmentDisplays")
                        .setCodec(TwAttachmentDisplayConfig.CODEC)
                        .setKeyFunction(TwAttachmentDisplayConfig::getId)
                        .build()
        );
        getEventRegistry().register(
                LoadedAssetsEvent.class,
                TwAttachmentDisplayConfig.class,
                this::onAttachmentDisplayAssetsLoaded
        );
        getEventRegistry().register(
                RemovedAssetsEvent.class,
                TwAttachmentDisplayConfig.class,
                this::onAttachmentDisplayAssetsRemoved
        );
        attachmentDisplayAssetsRegistered = true;
    }

    private void registerDynamicAttachmentsAssets() {
        if (dynamicAttachmentsAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwDynamicAttachmentsConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/DynamicAttachments")
                        .setCodec(TwDynamicAttachmentsConfig.CODEC)
                        .setKeyFunction(TwDynamicAttachmentsConfig::getId)
                        .build()
        );
        getEventRegistry().register(
                LoadedAssetsEvent.class,
                TwDynamicAttachmentsConfig.class,
                this::onDynamicAttachmentsAssetsLoaded
        );
        getEventRegistry().register(
                RemovedAssetsEvent.class,
                TwDynamicAttachmentsConfig.class,
                this::onDynamicAttachmentsAssetsRemoved
        );
        dynamicAttachmentsAssetsRegistered = true;
    }

    private void registerLevelingAssets() {
        if (levelingAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwLevelingConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Leveling")
                        .setCodec(TwLevelingConfig.CODEC)
                        .setKeyFunction(TwLevelingConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwLevelingConfig.class, this::onLevelingAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwLevelingConfig.class, this::onLevelingAssetsRemoved);
        levelingAssetsRegistered = true;
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

    private void registerTalentAssets() {
        if (talentAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwTalentConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Talents")
                        .setCodec(TwTalentConfig.CODEC)
                        .setKeyFunction(TwTalentConfig::getId)
                        .build()
        );
        getEventRegistry().register(LoadedAssetsEvent.class, TwTalentConfig.class, this::onTalentAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, TwTalentConfig.class, this::onTalentAssetsRemoved);
        talentAssetsRegistered = true;
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
        recordAssetPatchHotReload(TwCommandItemConfig.class, event.getAssetMap(), event.getLoadedAssets().keySet());
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

    private void onItemAssetsLoaded(
            LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        recordAssetPatchHotReload(Item.class, event.getAssetMap(), event.getLoadedAssets().keySet());
    }

    private void onParticleSystemAssetsLoaded(
            LoadedAssetsEvent<String, ParticleSystem, DefaultAssetMap<String, ParticleSystem>> event) {
        recordAssetPatchHotReload(ParticleSystem.class, event.getAssetMap(), event.getLoadedAssets().keySet());
    }

    private void onAssetStoreMonitor(@Nonnull AssetStoreMonitorEvent event) {
        if (assetPatchService == null || event.getAssetStore() == null) {
            return;
        }
        assetPatchService.recordGeneratedAssetStoreMonitor(
                event.getAssetStore().getAssetClass(),
                event.getAssetPack(),
                event.getCreatedOrModifiedFilesToLoad()
        );
    }

    private void onCommonAssetMonitor(@Nonnull CommonAssetMonitorEvent event) {
        if (assetPatchService == null) {
            return;
        }
        assetPatchService.recordGeneratedCommonAssetMonitor(
                event.getAssetPack(),
                event.getCreatedOrModifiedFilesToLoad()
        );
    }

    private void onItemDropListAssetsLoaded(
            LoadedAssetsEvent<String, ItemDropList, DefaultAssetMap<String, ItemDropList>> event) {
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void onItemDropListAssetsRemoved(
            RemovedAssetsEvent<String, ItemDropList, DefaultAssetMap<String, ItemDropList>> event) {
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void recordAssetPatchHotReload(@Nonnull Class<?> assetClass,
                                           @Nullable AssetMap<?, ?> assetMap,
                                           @Nonnull Iterable<?> keys) {
        if (assetPatchService != null) {
            assetPatchService.recordHotReloadedAssets(assetClass, assetMap, keys);
        }
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

    private void onMountedGlideAssetsLoaded(
            LoadedAssetsEvent<String, TwMountedGlideConfig, DefaultAssetMap<String, TwMountedGlideConfig>> event) {
        TwMountedGlideConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.MOUNTED_GLIDE, event.getLoadedAssets().keySet());
        }
    }

    private void onMountedGlideAssetsRemoved(
            RemovedAssetsEvent<String, TwMountedGlideConfig, DefaultAssetMap<String, TwMountedGlideConfig>> event) {
        TwMountedGlideConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.MOUNTED_GLIDE, event.getRemovedAssets());
    }

    private void onAvatarFlightAssetsLoaded(
            LoadedAssetsEvent<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> event) {
        TwAvatarFlightConfig.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.AVATAR_FLIGHT, event.getLoadedAssets().keySet());
        }
    }

    private void onAvatarFlightAssetsRemoved(
            RemovedAssetsEvent<String, TwAvatarFlightConfig, DefaultAssetMap<String, TwAvatarFlightConfig>> event) {
        TwAvatarFlightConfig.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.AVATAR_FLIGHT, event.getRemovedAssets());
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
        if (managedCoopRuntime != null) {
            managedCoopRuntime.invalidateManagedAuthorityEvidence();
        }
        TwCoopConfig.clearCoopCache();
        DefaultAssetMap<String, TwCoopConfig> resolvedAssets = TwCoopConfig.getAssetMap();
        TwCoopConfigValidationService.logInvalidManagedConfigs(
                getLogger(),
                resolvedAssets != null && resolvedAssets.getAssetMap() != null
                        ? resolvedAssets.getAssetMap().values()
                        : event.getLoadedAssets().values()
        );
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.COOP, event.getLoadedAssets().keySet());
        }
    }

    private void onCoopAssetsRemoved(
            RemovedAssetsEvent<String, TwCoopConfig, DefaultAssetMap<String, TwCoopConfig>> event) {
        if (managedCoopRuntime != null) {
            managedCoopRuntime.invalidateManagedAuthorityEvidence();
        }
        TwCoopConfig.clearCoopCache();
        DefaultAssetMap<String, TwCoopConfig> resolvedAssets = TwCoopConfig.getAssetMap();
        TwCoopConfigValidationService.logInvalidManagedConfigs(
                getLogger(),
                resolvedAssets != null && resolvedAssets.getAssetMap() != null
                        ? resolvedAssets.getAssetMap().values()
                        : java.util.List.of()
        );
        TwCoopConfigValidationService.forgetConfigs(event.getRemovedAssets());
        emitExperimentalConfigReload(TameworkConfigFamily.COOP, event.getRemovedAssets());
    }

    private void onHappinessAssetsLoaded(
            LoadedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.HAPPINESS, event.getLoadedAssets().keySet());
        }
    }

    private void onHappinessAssetsRemoved(
            RemovedAssetsEvent<String, TwHappinessConfig, DefaultAssetMap<String, TwHappinessConfig>> event) {
        TwHappinessConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.HAPPINESS, event.getRemovedAssets());
    }

    private void onFoodAssetsLoaded(
            LoadedAssetsEvent<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> event) {
        TwFoodConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.FOOD, event.getLoadedAssets().keySet());
        }
    }

    private void onFoodAssetsRemoved(
            RemovedAssetsEvent<String, TwFoodConfig, DefaultAssetMap<String, TwFoodConfig>> event) {
        TwFoodConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.FOOD, event.getRemovedAssets());
    }

    private void onNeedsAssetsLoaded(
            LoadedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
        NeedsConfigResolver.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.NEEDS, event.getLoadedAssets().keySet());
        }
    }

    private void onNeedsAssetsRemoved(
            RemovedAssetsEvent<String, TwNeedsConfig, DefaultAssetMap<String, TwNeedsConfig>> event) {
        TwNeedsConfig.clearRoleCache();
        NeedsConfigResolver.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.NEEDS, event.getRemovedAssets());
    }

    private void onBreedingAssetsLoaded(
            LoadedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.BREEDING, event.getLoadedAssets().keySet());
        }
    }

    private void onBreedingAssetsRemoved(
            RemovedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
        CompanionHappinessModifierService.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.BREEDING, event.getRemovedAssets());
    }

    private void onAttachmentMigrationAssetsLoaded(
            LoadedAssetsEvent<String, TwAttachmentMigrationConfig, DefaultAssetMap<String, TwAttachmentMigrationConfig>> event) {
        TwAttachmentMigrationConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.ATTACHMENT_MIGRATION, event.getLoadedAssets().keySet());
        }
    }

    private void onAttachmentMigrationAssetsRemoved(
            RemovedAssetsEvent<String, TwAttachmentMigrationConfig, DefaultAssetMap<String, TwAttachmentMigrationConfig>> event) {
        TwAttachmentMigrationConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.ATTACHMENT_MIGRATION, event.getRemovedAssets());
    }

    private void onAttachmentDisplayAssetsLoaded(
            LoadedAssetsEvent<String, TwAttachmentDisplayConfig, DefaultAssetMap<String, TwAttachmentDisplayConfig>> event) {
        TwAttachmentDisplayConfig.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.ATTACHMENT_DISPLAY, event.getLoadedAssets().keySet());
        }
    }

    private void onAttachmentDisplayAssetsRemoved(
            RemovedAssetsEvent<String, TwAttachmentDisplayConfig, DefaultAssetMap<String, TwAttachmentDisplayConfig>> event) {
        TwAttachmentDisplayConfig.clearCache();
        emitExperimentalConfigReload(TameworkConfigFamily.ATTACHMENT_DISPLAY, event.getRemovedAssets());
    }

    private void onDynamicAttachmentsAssetsLoaded(
            LoadedAssetsEvent<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> event) {
        TwDynamicAttachmentsConfig.clearRoleRuleIndexCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ATTACHMENTS, event.getLoadedAssets().keySet());
        }
    }

    private void onDynamicAttachmentsAssetsRemoved(
            RemovedAssetsEvent<String, TwDynamicAttachmentsConfig, DefaultAssetMap<String, TwDynamicAttachmentsConfig>> event) {
        TwDynamicAttachmentsConfig.clearRoleRuleIndexCache();
        emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ATTACHMENTS, event.getRemovedAssets());
    }

    private void onLevelingAssetsLoaded(
            LoadedAssetsEvent<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> event) {
        TwLevelingConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.LEVELING, event.getLoadedAssets().keySet());
        }
    }

    private void onLevelingAssetsRemoved(
            RemovedAssetsEvent<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> event) {
        TwLevelingConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.LEVELING, event.getRemovedAssets());
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

    private void onTalentAssetsLoaded(
            LoadedAssetsEvent<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> event) {
        TwTalentConfig.clearRoleCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.TALENT, event.getLoadedAssets().keySet());
        }
    }

    private void onTalentAssetsRemoved(
            RemovedAssetsEvent<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> event) {
        TwTalentConfig.clearRoleCache();
        emitExperimentalConfigReload(TameworkConfigFamily.TALENT, event.getRemovedAssets());
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

    public ComponentType<EntityStore, TameworkAlarmComponent> getAlarmComponentType() {
        return alarmComponentType;
    }

    public ComponentType<EntityStore, TameworkFlyingCompanionComponent> getFlyingCompanionComponentType() {
        return flyingCompanionComponentType;
    }

    public ComponentType<EntityStore, TameworkRideMountComponent> getRideMountComponentType() {
        return rideMountComponentType;
    }

    public ComponentType<EntityStore, TameworkRideRiderComponent> getRideRiderComponentType() {
        return rideRiderComponentType;
    }

    public ComponentType<EntityStore, TameworkMountedGlideComponent> getMountedGlideComponentType() {
        return mountedGlideComponentType;
    }

    public ComponentType<EntityStore, TameworkMountedGlideRiderComponent> getMountedGlideRiderComponentType() {
        return mountedGlideRiderComponentType;
    }

    public ComponentType<EntityStore, AvatarFlightComponent> getAvatarFlightComponentType() {
        return avatarFlightComponentType;
    }

    public ComponentType<EntityStore, AvatarFlightInputComponent> getAvatarFlightInputComponentType() {
        return avatarFlightInputComponentType;
    }

    public ComponentType<EntityStore, AvatarFlightRiderVisualComponent> getAvatarFlightRiderVisualComponentType() {
        return avatarFlightRiderVisualComponentType;
    }

    public ComponentType<EntityStore, TameworkLevelingComponent> getLevelingComponentType() {
        return levelingComponentType;
    }

    public ComponentType<EntityStore, TameworkTraitsComponent> getTraitsComponentType() {
        return traitsComponentType;
    }

    public ComponentType<EntityStore, TameworkTalentsComponent> getTalentsComponentType() {
        return talentsComponentType;
    }

    public ComponentType<EntityStore, TameworkTranquilizerPeakComponent> getTranquilizerPeakComponentType() {
        return tranquilizerPeakComponentType;
    }

    public ComponentType<EntityStore, TameworkAttachmentsComponent> getAttachmentsComponentType() {
        return attachmentsComponentType;
    }

    public ComponentType<EntityStore, TameworkDynamicAttachmentsComponent> getDynamicAttachmentsComponentType() {
        return dynamicAttachmentsComponentType;
    }

    public ComponentType<EntityStore, TameworkLifeStageComponent> getLifeStageComponentType() {
        return lifeStageComponentType;
    }

    public ComponentType<EntityStore, TameworkProjectionIdentityComponent> getProjectionIdentityComponentType() {
        return projectionIdentityComponentType;
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

    public boolean isDebugRideEnabled() {
        return debugRideLogs;
    }

    public boolean setDebugRideEnabled(boolean enabled) {
        debugRideLogs = enabled;
        return debugRideLogs;
    }

    public boolean toggleDebugRideEnabled() {
        debugRideLogs = !debugRideLogs;
        return debugRideLogs;
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

    public boolean isDebugNeedsSeekDiagnosticsEnabled() {
        return debugNeedsSeekDiagnosticsLogs;
    }

    public boolean setDebugNeedsSeekDiagnosticsEnabled(boolean enabled) {
        debugNeedsSeekDiagnosticsLogs = enabled;
        return debugNeedsSeekDiagnosticsLogs;
    }

    public boolean toggleDebugNeedsSeekDiagnosticsEnabled() {
        debugNeedsSeekDiagnosticsLogs = !debugNeedsSeekDiagnosticsLogs;
        return debugNeedsSeekDiagnosticsLogs;
    }

    public boolean isDebugNeedsTelemetryDiagnosticsEnabled() {
        return debugNeedsTelemetryDiagnostics;
    }

    public boolean setDebugNeedsTelemetryDiagnosticsEnabled(boolean enabled) {
        debugNeedsTelemetryDiagnostics = enabled;
        return debugNeedsTelemetryDiagnostics;
    }

    public boolean toggleDebugNeedsTelemetryDiagnosticsEnabled() {
        debugNeedsTelemetryDiagnostics = !debugNeedsTelemetryDiagnostics;
        return debugNeedsTelemetryDiagnostics;
    }

    public boolean isDebugHarvestEnabled() {
        return debugHarvestLogs;
    }

    public boolean setDebugHarvestEnabled(boolean enabled) {
        debugHarvestLogs = enabled;
        return debugHarvestLogs;
    }

    public boolean toggleDebugHarvestEnabled() {
        debugHarvestLogs = !debugHarvestLogs;
        return debugHarvestLogs;
    }

    public boolean isDebugRespawnTraceEnabled() {
        return debugRespawnTraceLogs;
    }

    public boolean setDebugRespawnTraceEnabled(boolean enabled) {
        debugRespawnTraceLogs = enabled;
        return debugRespawnTraceLogs;
    }

    public boolean toggleDebugRespawnTraceEnabled() {
        debugRespawnTraceLogs = !debugRespawnTraceLogs;
        return debugRespawnTraceLogs;
    }

    public boolean isDebugFlyingCompanionEnabled() {
        return debugFlyingCompanionLogs;
    }

    public boolean setDebugFlyingCompanionEnabled(boolean enabled) {
        debugFlyingCompanionLogs = enabled;
        return debugFlyingCompanionLogs;
    }

    public boolean toggleDebugFlyingCompanionEnabled() {
        debugFlyingCompanionLogs = !debugFlyingCompanionLogs;
        return debugFlyingCompanionLogs;
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

    @Nullable
    private ComponentType<EntityStore, MountedComponent> resolveMountedComponentTypeOrNull() {
        try {
            return MountedComponent.getComponentType();
        } catch (Throwable throwable) {
            return null;
        }
    }

}



