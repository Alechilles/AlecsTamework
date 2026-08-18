package com.alechilles.alecstamework;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.nio.file.Path;
import java.time.Duration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.TameworkProgressionTimeScales;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
import com.alechilles.alecstamework.api.internal.BondedOnlyTameworkApi;
import com.alechilles.alecstamework.api.internal.ReplacementTameworkApiFactory;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import com.alechilles.alecstamework.api.internal.TraitEffectRegistry;
import com.alechilles.alecstamework.api.internal.TraitEffectRuntime;
import com.alechilles.alecstamework.assets.TameworkAssetEditorPackService;
import com.alechilles.alecstamework.integration.patchwork.TameworkPatchworkRuntime;
import com.alechilles.alecstamework.avatarflight.AvatarFlightComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightDisconnectRecoveryService;
import com.alechilles.alecstamework.avatarflight.AvatarFlightInputComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightMountSessionComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightRiderVisualComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceRecoverySystem;
import com.alechilles.alecstamework.avatarflight.AvatarFlightStaleOwnerRecoveryRegistry;
import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceVisibilitySystem;
import com.alechilles.alecstamework.commands.TameworkCommandRoot;
import com.alechilles.alecstamework.commands.SpawnBeaconVisualizationService;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.SpawnerItemConfigReloadService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionConfigReloadService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.config.population.PopulationGroupAssetRegistrar;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwAvatarFlightConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.assets.TwCapturePolicyConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionMovementConfig;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.config.assets.TwDebugConfig;
import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.config.assets.TwMountedDescentConfig;
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
import com.alechilles.alecstamework.damage.ExpiryDismountFallDamageProtectionSystem;
import com.alechilles.alecstamework.damage.ExpiryDismountLandingProtectionSystem;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.damage.SimpleClaimsCapabilityRuntime;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileComponent;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardProjectileSpawnSystem;
import com.alechilles.alecstamework.damage.TameworkLingeringHazardSystem;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectComponent;
import com.alechilles.alecstamework.damage.TameworkProjectileImpactEffectSystem;
import com.alechilles.alecstamework.damage.TraitDamageModifierSystem;
import com.alechilles.alecstamework.damage.TranquilizedSleepAnimationRestoreSystem;
import com.alechilles.alecstamework.debug.CompanionXpEventDebugLogService;
import com.alechilles.alecstamework.debug.PlayerInputDebugProbe;
import com.alechilles.alecstamework.npc.actions.BreedingPairAdmissionRegistry;
import com.alechilles.alecstamework.npc.actions.HeldItemAttachmentInteractionService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.integration.creditor.CreditorIntegration;
import com.alechilles.alecstamework.integration.nameplatebuilder.NameplateBuilderBridgeLoader;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.alechilles.alecstamework.items.CaptureChannelVfxSystem;
import com.alechilles.alecstamework.items.CaptureChannelSessionCleanupSystem;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.CommandWorldChangeArrivalSystem;
import com.alechilles.alecstamework.items.CommandWorldChangeTravelEventHandler;
import com.alechilles.alecstamework.items.CommandLinkedNpcInventoryCanonicalizationSystem;
import com.alechilles.alecstamework.items.CommandDirectLiveCoopSystem;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CommandHotswapHudService;
import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandTargetHudActivationTracker;
import com.alechilles.alecstamework.items.CommandTargetHudActiveSlotSystem;
import com.alechilles.alecstamework.items.CommandTargetHudInventoryChangeSystem;
import com.alechilles.alecstamework.items.CommandTargetHudService;
import com.alechilles.alecstamework.items.CommandTeleportArrivalRelocationSystem;
import com.alechilles.alecstamework.items.CoopDebugLogger;
import com.alechilles.alecstamework.items.FeedTroughFoodStateSyncSystem;
import com.alechilles.alecstamework.items.FeedTroughWaterChargeDroplistCompatService;
import com.alechilles.alecstamework.items.components.TameworkFeedTroughWaterChargesComponent;
import com.alechilles.alecstamework.items.components.TameworkBondedReviveEscrowComponent;
import com.alechilles.alecstamework.items.NamingFeatureHandler;
import com.alechilles.alecstamework.items.OwnerInteractionListener;
import com.alechilles.alecstamework.items.SpawnerFeatureHandler;
import com.alechilles.alecstamework.items.TranquilizerRecipeVisibilityService;
import com.alechilles.alecstamework.items.scarecrow.ScarecrowBlockEventSystems;
import com.alechilles.alecstamework.items.persistence.ImportedCompanionRecallRecovery;
import com.alechilles.alecstamework.items.persistence.CompositeRecallRecoverySink;
import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
import com.alechilles.alecstamework.metrics.BondedCompanionPersistenceTelemetry;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.metrics.TameworkHStatsIntegration;
import com.alechilles.alecstamework.runtime.activation.TameworkReloadTopologyReport;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationPlan;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeActivationState;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeDiagnostics;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeCapabilityRequests;
import com.alechilles.alecstamework.runtime.TameworkRuntimeHandle;
import com.alechilles.alecstamework.runtime.TameworkRuntimeActivationCoordinator;
import com.alechilles.alecstamework.runtime.TameworkInteractionCodecRegistrar;
import com.alechilles.alecstamework.runtime.TameworkActiveAssetInitializer;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationTarget;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationTelemetry;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrar;
import com.alechilles.alecstamework.runtime.TameworkRuntimeRegistrationContext;
import com.alechilles.alecstamework.persistence.runtime.player.TameworkInventoryOperationReceiptsComponent;
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
import com.alechilles.alecstamework.npc.components.TameworkShoulderRideComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTranquilizerPeakComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.OwnerPresenceTimelineService;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.persistence.facade.ReplacementNpcProfilesApi;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.diagnostics
        .PersistenceDiagnosticExporter;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.activation.TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.companion.bonded.runtime
        .BondedCompanionExpiryWarningSystem;
import com.alechilles.alecstamework.companion.bonded.runtime
        .BondedCompanionMaintenanceSystem;
import com.alechilles.alecstamework.companion.bonded.runtime
        .BondedCompanionDeathSystem;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonOwnerDeathSystem;
import com.alechilles.alecstamework.companion.command.timed.runtime
        .TimedSummonOwnerLifecycleService;
import com.alechilles.alecstamework.persistence.runtime
        .PublicPersistenceShutdownReport;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureMarkerComponent;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.alechilles.alecstamework.ui.TameworkSettingsAnnouncementService;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileComponent;
import com.alechilles.alecstamework.vfx.projectile.HomingVisualProjectileSystem;
import com.alechilles.alecstamework.npc.systems.CompanionSpawnAuthorityCleanupSystems;
import com.alechilles.alecstamework.npc.systems.CompanionMovementSpeedSyncSystem;
import com.alechilles.alecstamework.npc.systems.CommandNpcRelocationOnLoadSystem;
import com.alechilles.alecstamework.npc.network.MountedRidePacketHandler;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.mounts.NPCMountComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.system.ISystem;
import com.hypixel.hytale.component.system.EcsEvent;
import com.hypixel.hytale.server.core.asset.HytaleAssetStore;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.io.ServerManager;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import java.util.function.Supplier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

/**
 * Main entry point for the Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {
    private static Tamework instance;

    private ItemFeatureRegistry itemFeatureRegistry;
    private SpawnerItemConfigReloadService spawnerItemConfigReloadService;
    private NameItemRegistry nameItemRegistry;
    private CommandItemRegistry commandItemRegistry;
    private TameworkAssetEditorPackService assetEditorPackService;
    private TameworkPatchworkRuntime patchworkRuntime;
    private TwConfigOverrideManager configOverrideManager;
    private final Set<String> overrideInitializedScopeKeys = ConcurrentHashMap.newKeySet();

    private TranslationRegistry translationRegistry;
    private SpawnerFeatureHandler spawnerFeatureHandler;
    private NamingFeatureHandler namingFeatureHandler;
    private CommandItemFeatureHandler commandItemFeatureHandler;
    private TranquilizerRecipeVisibilityService tranquilizerRecipeVisibilityService;
    private FeedTroughWaterChargeDroplistCompatService feedTroughWaterChargeDroplistCompatService;
    private CommandNpcRelocationService commandNpcRelocationService;
    private CommandLinkedNpcStateSnapshotService commandLinkedNpcStateSnapshotService;
    private Path runtimeDataDirectory;
    private TameworkPersistenceComposition persistenceComposition;
    private TameworkBondedCompanionComposition bondedCompanionComposition;
    private AutoCloseable bondedDiagnosticRegistration;
    private PersistenceBootstrap persistenceBootstrap;
    private TameworkApi api;
    private ReplacementTameworkApiFactory.Composition apiComposition;
    private TameworkEventBus apiEventBus;
    private InteractionExtensionRegistry interactionExtensionRegistry;
    private TraitEffectRegistry traitEffectRegistry;
    private CapturePolicyRegistry capturePolicyRegistry;
    private BondedCompanionRosterRegistry bondedCompanionRosterRegistry;
    private BondedCompanionConfigReloadService bondedCompanionConfigReloadService;
    private PopulationGroupConfigRegistry populationGroupConfigRegistry;
    private PopulationGroupAssetRegistrar populationGroupAssetRegistrar;
    private ApiSelfTestFixtureManager apiSelfTestFixtureManager;
    private ApiSelfTestRunner apiSelfTestRunner;
    private CompanionXpEventDebugLogService companionXpEventDebugLogService;
    private TameworkNpcBuilderRegistrar npcBuilderRegistrar;
    private TameworkHStatsIntegration hStatsIntegration;
    private final TameworkRuntimeActivationCoordinator runtimeActivationCoordinator =
            new TameworkRuntimeActivationCoordinator();
    private TameworkRuntimeActivationPlan runtimeStartupPlan;
    private TameworkRuntimeActivationState runtimeActivationState;
    private TameworkRuntimeDiagnostics runtimeStartupDiagnostics;
    private TameworkRuntimeHandle runtimeHandle;
    private final TameworkRuntimeCapabilityRequests runtimeCapabilityRequests =
            new TameworkRuntimeCapabilityRequests();
    private TameworkRuntimeParticipantRegistry runtimeParticipants;
    private Runnable runtimeServiceInitializer;
    private TameworkPersistenceActivationEvidence genericPersistenceActivationEvidence;
    private TameworkPersistenceActivationEvidence bondedPersistenceActivationEvidence;
    private CrashTelemetryService crashTelemetryService;
    private final TameworkTelemetryEvents telemetryEvents = new TameworkTelemetryEvents();
    private TameworkSettingsAnnouncementService settingsAnnouncementService;
    private final SpawnBeaconVisualizationService spawnBeaconVisualizationService =
            new SpawnBeaconVisualizationService();
    private boolean globalAssetsRegistered;
    private boolean companionAssetsRegistered;
    private boolean spawnerAssetsRegistered;
    private boolean namingAssetsRegistered;
    private boolean namesAssetsRegistered;
    private boolean commandAssetsRegistered;
    private boolean interactionAssetsRegistered;
    private boolean mountedGlideAssetsRegistered;
    private boolean mountedDescentAssetsRegistered;
    private boolean avatarFlightAssetsRegistered;
    private boolean coopAssetsRegistered;
    private boolean foodAssetsRegistered;
    private boolean happinessAssetsRegistered;
    private boolean needsAssetsRegistered;
    private boolean breedingAssetsRegistered;
    private boolean attachmentMigrationAssetsRegistered;
    private boolean attachmentDisplayAssetsRegistered;
    private boolean dynamicAttachmentsAssetsRegistered;
    private boolean companionMovementAssetsRegistered;
    private boolean levelingAssetsRegistered;
    private boolean traitAssetsRegistered;
    private boolean talentAssetsRegistered;
    private boolean debugAssetsRegistered;
    private boolean capturePolicyAssetsRegistered;
    private long capturePolicyAssetRevision;
    private boolean bondedCompanionRosterAssetsRegistered;
    private String lastGlobalConfigWarningKey;
    private final Object itemFeatureReloadSuppressionLock = new Object();
    private int itemFeatureReloadSuppressionDepth;
    private boolean itemFeatureReloadPending;
    private volatile boolean spawnerReloadPendingOnItemAssets;
    private final Object overrideAssetEventSuppressionLock = new Object();
    private final OwnerPopulationLiveIndex ownerPopulationLiveIndex =
            new OwnerPopulationLiveIndex();
    private final BreedingPairAdmissionRegistry breedingPairAdmissionRegistry =
            new BreedingPairAdmissionRegistry();
    private final SimpleClaimsCapabilityRuntime simpleClaimsCapabilityRuntime =
            new SimpleClaimsCapabilityRuntime();
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
    private ComponentType<EntityStore, TameworkShoulderRideComponent> shoulderRideComponentType;
    private ComponentType<EntityStore, TameworkMountedGlideComponent> mountedGlideComponentType;
    private ComponentType<EntityStore, TameworkMountedGlideRiderComponent> mountedGlideRiderComponentType;
    private ComponentType<EntityStore, AvatarFlightComponent> avatarFlightComponentType;
    private ComponentType<EntityStore, AvatarFlightInputComponent> avatarFlightInputComponentType;
    private ComponentType<EntityStore, AvatarFlightRiderVisualComponent> avatarFlightRiderVisualComponentType;
    private ComponentType<EntityStore, AvatarFlightMountSessionComponent> avatarFlightMountSessionComponentType;
    private ComponentType<EntityStore, AvatarFlightSourceComponent> avatarFlightSourceComponentType;
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
    private ComponentType<EntityStore, HomingVisualProjectileComponent> homingVisualProjectileComponentType;
    private ComponentType<EntityStore, TameworkInventoryOperationReceiptsComponent>
            inventoryOperationReceiptsComponentType;
    private ComponentType<EntityStore, TameworkBondedReviveEscrowComponent>
            bondedReviveEscrowComponentType;
    private ComponentType<ChunkStore, TameworkFeedTroughWaterChargesComponent> feedTroughWaterChargesComponentType;
    private ComponentType<EntityStore, SpawnMarkerEntity> spawnMarkerEntityType;
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
    private volatile boolean debugAvatarFlightLogs;

    public Tamework(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    @Override
    protected void setup() {
        setupInternal();
    }

    private void setupInternal() {
        runtimeParticipants = new TameworkRuntimeParticipantRegistry(
                eventType -> getEntityStoreRegistry().registerEntityEventType(eventType),
                getLogger()
        );
        itemFeatureRegistry = new ItemFeatureRegistry();
        spawnerItemConfigReloadService = new SpawnerItemConfigReloadService(
                itemFeatureRegistry,
                itemId -> {
                    DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();
                    Item item = itemMap == null ? null : itemMap.getAsset(itemId);
                    return item == null || item.getMaxStack() <= 0
                            ? java.util.OptionalInt.empty()
                            : java.util.OptionalInt.of(item.getMaxStack());
                });
        nameItemRegistry = new NameItemRegistry();
        bondedCompanionRosterRegistry = new BondedCompanionRosterRegistry();
        commandItemRegistry = new CommandItemRegistry(
                bondedCompanionRosterRegistry
        );
        bondedCompanionConfigReloadService =
                new BondedCompanionConfigReloadService(
                        bondedCompanionRosterRegistry,
                        commandItemRegistry
                );
        capturePolicyRegistry = new CapturePolicyRegistry();
        populationGroupConfigRegistry = new PopulationGroupConfigRegistry();
        populationGroupAssetRegistrar = new PopulationGroupAssetRegistrar(
                this,
                populationGroupConfigRegistry,
                this::emitExperimentalConfigReload
        );
        assetEditorPackService = new TameworkAssetEditorPackService(this);
        patchworkRuntime = new TameworkPatchworkRuntime(this);
        tranquilizerRecipeVisibilityService = new TranquilizerRecipeVisibilityService();
        feedTroughWaterChargeDroplistCompatService = new FeedTroughWaterChargeDroplistCompatService();
        npcBuilderRegistrar = new TameworkNpcBuilderRegistrar(this);
        TameworkInteractionCodecRegistrar.registerAll();
        // Builders are passive decoders and must exist before the initial NPC asset load.
        npcBuilderRegistrar.registerNpcActionsIfReady();
        deferGlobalListener(
                TameworkRuntimeModule.MOUNTS,
                "mounted-ride-packet-handler",
                () -> ServerManager.get().registerSubPacketHandlers(MountedRidePacketHandler::new)
        );
        itemFeatureRegistry.registerDefaults();
        registerGlobalConfigAssets();
        registerCompanionAssets();
        registerCapturePolicyAssets();
        registerBondedCompanionRosterAssets();
        populationGroupAssetRegistrar.registerAssetStore();
        deferAssetSubscription(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "population-group-assets",
                populationGroupAssetRegistrar::activate
        );
        registerCoopAssets();
        registerSpawnerItemAssets();
        registerNamingItemAssets();
        registerNamesAssets();
        registerCommandItemAssets();
        registerInteractionAssets();
        registerMountedGlideAssets();
        registerMountedDescentAssets();
        registerAvatarFlightAssets();
        registerFoodAssets();
        registerHappinessAssets();
        registerNeedsAssets();
        registerBreedingAssets();
        registerAttachmentMigrationAssets();
        registerAttachmentDisplayAssets();
        registerDynamicAttachmentsAssets();
        registerCompanionMovementAssets();
        registerLevelingAssets();
        registerTraitAssets();
        registerTalentAssets();
        registerDebugAssets();
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "creditor-integration",
                () -> {
                    CreditorIntegration.setup(this);
                    CreditorIntegration.start(this);
                }
        );
        deferCraftingRecipeSubscriptions();
        deferAssetSubscription(
                TameworkRuntimeModule.SPAWNER_ITEMS,
                "item-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class, Item.class, this::onItemAssetsLoaded
                )
        );
        deferAssetSubscription(
                TameworkRuntimeModule.FOOD,
                "item-drop-list-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsLoaded
                )
        );
        deferAssetSubscription(
                TameworkRuntimeModule.FOOD,
                "item-drop-list-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class, ItemDropList.class, this::onItemDropListAssetsRemoved
                )
        );

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
        shoulderRideComponentType = components.shoulderRide();
        mountedGlideComponentType = components.mountedGlide();
        mountedGlideRiderComponentType = components.mountedGlideRider();
        avatarFlightComponentType = components.avatarFlight();
        avatarFlightInputComponentType = components.avatarFlightInput();
        avatarFlightRiderVisualComponentType = components.avatarFlightRiderVisual();
        avatarFlightMountSessionComponentType = components.avatarFlightMountSession();
        avatarFlightSourceComponentType = components.avatarFlightSource();
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
        homingVisualProjectileComponentType = components.homingVisualProjectile();
        inventoryOperationReceiptsComponentType =
                components.inventoryOperationReceipts();
        bondedReviveEscrowComponentType = components.bondedReviveEscrow();
        feedTroughWaterChargesComponentType = components.feedTroughWaterCharges();

        spawnMarkerEntityType = TameworkCompanionRuntimeParticipants.add(this, runtimeParticipants);
        deferPersistenceIndependentRuntimeParticipants();

        runtimeServiceInitializer = () -> {
        if (runtimeStartupPlan == null) {
            return;
        }
        if (runtimeStartupPlan.activeModules().stream()
                .anyMatch(module -> module != TameworkRuntimeModule.HSTATS)) {
            try {
                patchworkRuntime.start();
                configOverrideManager = new TwConfigOverrideManager(
                        this, patchworkRuntime::generatedPatchRoot
                );
            } catch (RuntimeException exception) {
                getLogger().at(Level.SEVERE).withCause(exception).log(
                        "Patchwork failed to start; Tamework will continue without generated asset patches."
                );
            }
        }
        boolean genericPersistenceNeeded = runtimeStartupPlan.isActive(
                TameworkRuntimeModule.GENERIC_PERSISTENCE
        ) || genericPersistenceActivationEvidence.hasDurableWork();
        boolean bondedPersistenceNeeded = runtimeStartupPlan.isActive(
                TameworkRuntimeModule.BONDED_PERSISTENCE
        ) || bondedPersistenceActivationEvidence.hasDurableWork();
        if (!genericPersistenceNeeded && !bondedPersistenceNeeded) {
            return;
        }
        apiEventBus = new TameworkEventBus(getLogger());
        runtimeDataDirectory = new TameworkDataPathService(getLogger())
                .resolveAndInitializeDataPathLayout(getDataDirectory())
                .targetDirectory();
        bondedCompanionComposition = TameworkBondedCompanionComposition.openIfActive(
                runtimeDataDirectory,
                bondedCompanionRosterRegistry,
                getLogger(),
                System::currentTimeMillis,
                apiEventBus::publishPersistenceEvent,
                BondedCompanionPersistenceTelemetry::recordRuntimeFailure,
                runtimeStartupPlan,
                bondedPersistenceActivationEvidence
        );
        if (bondedCompanionComposition != null) {
        runtimeStartupDiagnostics.recordDatabaseOpen(TameworkRuntimeModule.BONDED_PERSISTENCE);
        deferEntitySystem(TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-companion-maintenance",
                () -> new BondedCompanionMaintenanceSystem(bondedCompanionComposition));
        deferEntitySystem(TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-companion-expiry-warning",
                () -> new BondedCompanionExpiryWarningSystem(bondedCompanionComposition));
        deferEntitySystem(TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-companion-death",
                () -> new BondedCompanionDeathSystem(
                        bondedCompanionComposition,
                        projectionIdentityComponentType,
                        UUIDComponent.getComponentType()
                ));
        deferGlobalListener(
                TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-world-load-reconciliation",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this, StartWorldEvent.class,
                        bondedCompanionComposition::onWorldLoad,
                        "bonded companion world-load reconciliation"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-player-added-reconciliation",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this, AddPlayerToWorldEvent.class,
                        bondedCompanionComposition::onPlayerAdded,
                        "bonded companion player join/transfer reconciliation"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.BONDED_PERSISTENCE,
                "bonded-player-disconnect-reconciliation",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this, PlayerDisconnectEvent.class,
                        bondedCompanionComposition::onPlayerLogout,
                        "bonded companion logout reconciliation"
                )
        );
        }
        try {
            persistenceComposition = TameworkPersistenceComposition.createIfActive(
                    this,
                    components,
                    apiEventBus,
                    itemFeatureRegistry,
                    commandItemRegistry,
                    populationGroupConfigRegistry,
                    runtimeStartupPlan,
                    genericPersistenceActivationEvidence,
                    runtimeParticipants
            );
            if (persistenceComposition == null) {
                if (runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
                    throw new IllegalStateException(
                            "Active generic persistence returned no runtime composition"
                    );
                }
                if (bondedCompanionComposition != null) {
                    activateBondedOnlyFallback(
                            new IllegalStateException("Generic persistence is dormant")
                    );
                }
                return;
            }
            runtimeStartupDiagnostics.recordDatabaseOpen(TameworkRuntimeModule.GENERIC_PERSISTENCE);
        } catch (RuntimeException genericStartupFailure) {
            if (runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
                closeBondedCompanions();
                throw new IllegalStateException(
                        "Required generic persistence failed during startup",
                        genericStartupFailure
                );
            }
            if (spawnMarkerEntityType != null) {
                deferEntitySystem(
                        TameworkRuntimeModule.CORE_OWNERSHIP,
                        "companion-spawn-authority-cleanup-marker-fallback",
                        () -> new CompanionSpawnAuthorityCleanupSystems.Marker(
                                spawnMarkerEntityType,
                                tamedComponentType
                        )
                );
            }
            if (bondedCompanionComposition != null) {
                activateBondedOnlyFallback(genericStartupFailure);
            } else {
                getLogger().at(Level.SEVERE).withCause(genericStartupFailure).log(
                        "Generic persistence composition failed and no bonded authority is active."
                );
            }
            return;
        }
        if (spawnMarkerEntityType != null) {
            deferEntitySystem(
                    TameworkRuntimeModule.CORE_OWNERSHIP,
                    "companion-spawn-authority-cleanup-marker",
                    () -> new CompanionSpawnAuthorityCleanupSystems.Marker(
                            spawnMarkerEntityType,
                            tamedComponentType
                    )
            );
        }
        commandNpcRelocationService = new CommandNpcRelocationService(
                getLogger(),
                new CompositeRecallRecoverySink(List.of(
                        persistenceComposition.exactRecallRecovery(),
                        new ImportedCompanionRecallRecovery(
                                persistenceComposition.facades(),
                                getLogger()
                        )
                ))
        );
        runtimeDataDirectory = persistenceComposition.dataDirectory();
        if (bondedCompanionComposition != null) {
            bondedDiagnosticRegistration = persistenceComposition.registerBondedDiagnostics(
                    bondedCompanionComposition.diagnostics()
            );
        }
        persistenceBootstrap = persistenceComposition.persistence();
        commandLinkedNpcStateSnapshotService =
                persistenceComposition.snapshots();
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
        traitEffectRegistry = new TraitEffectRegistry(
                getLogger(),
                new ReplacementNpcProfilesApi(
                        persistenceComposition.facades().queries(),
                        Duration.ofSeconds(5)
                )
        );
        deferEntitySystem(TameworkRuntimeModule.CAPTURE,
                "capture-channel-vfx", CaptureChannelVfxSystem::new);
        deferEntitySystem(TameworkRuntimeModule.CAPTURE,
                "capture-channel-session-cleanup", CaptureChannelSessionCleanupSystem::new);
        SimpleClaimsTamedDamagePolicy damagePolicy =
                new SimpleClaimsTamedDamagePolicy(simpleClaimsCapabilityRuntime);
        apiComposition = ReplacementTameworkApiFactory.compose(
                persistenceBootstrap,
                Duration.ofSeconds(5),
                System::currentTimeMillis,
                apiEventBus,
                commandLinkedNpcStateSnapshotService,
                interactionExtensionRegistry,
                traitEffectRegistry,
                damagePolicy,
                persistenceComposition.featureApiDependencies(),
                bondedCompanionComposition == null ? null : bondedCompanionComposition.api()
        );
        api = apiComposition.api();
        apiComposition.activateCapturePolicyRuntime(
                itemFeatureRegistry, capturePolicyRegistry
        );
        TimedSummonOwnerLifecycleService timedSummonOwnerLifecycle =
                new TimedSummonOwnerLifecycleService(
                        () -> api.commandTimedSummoning(),
                        () -> persistenceComposition.facades().queries()
                                .projectedTimedSummons()
                );
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "timed-summon-owner-death", () -> new TimedSummonOwnerDeathSystem(
                        timedSummonOwnerLifecycle,
                        Player.getComponentType(),
                        UUIDComponent.getComponentType()
                )
        );
        companionXpEventDebugLogService = new CompanionXpEventDebugLogService(
                () -> api,
                message -> getLogger().at(Level.INFO).log(message)
        );
        apiSelfTestFixtureManager = new ApiSelfTestFixtureManager();
        apiSelfTestRunner = new ApiSelfTestRunner();
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-npc-relocation-on-load", () -> new CommandNpcRelocationOnLoadSystem(
                        commandNpcRelocationService,
                        commandLinkedNpcStateSnapshotService
                )
        );
        deferChunkSystem(TameworkRuntimeModule.COOPS,
                "command-direct-live-coop", () -> new CommandDirectLiveCoopSystem(
                        persistenceComposition.directLiveCoopAuthor(),
                        persistenceComposition.directLiveCoopProjections()
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
        if (bondedCompanionComposition == null) {
            spawnerFeatureHandler = new SpawnerFeatureHandler(
                    getLogger(), itemFeatureRegistry, translationRegistry,
                    persistenceComposition.captureAuthor(), persistenceComposition.releaseAuthor(),
                    capturePolicyRegistry, interactionExtensionRegistry,
                    persistenceComposition.tameAndLinkEvidence()
            );
        } else {
            spawnerFeatureHandler = new SpawnerFeatureHandler(
                    getLogger(), itemFeatureRegistry, translationRegistry,
                    persistenceComposition.captureAuthor(), persistenceComposition.releaseAuthor(),
                    capturePolicyRegistry, interactionExtensionRegistry,
                    persistenceComposition.tameAndLinkEvidence(),
                    bondedCompanionComposition.captureAuthor(), bondedCompanionRosterRegistry,
                    commandItemRegistry
            );
        }
        // Core handler for naming flows.
        namingFeatureHandler = new NamingFeatureHandler(nameItemRegistry, translationRegistry);
        // Core handler for command-item linking and dispatch.
        commandItemFeatureHandler = new CommandItemFeatureHandler(
                commandItemRegistry,
                commandNpcRelocationService,
                commandLinkedNpcStateSnapshotService,
                persistenceComposition.facades(),
                persistenceComposition.restorationAuthor(),
                api::commandTimedSummoning,
                api::paidCommandRevival,
                api::populationGroups,
                api::bondedCompanions,
                api.events()
        );
        CommandWorldChangeTravelEventHandler commandWorldChangeTravelEventHandler =
                new CommandWorldChangeTravelEventHandler(commandItemFeatureHandler);
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-teleport-arrival-relocation",
                () -> new CommandTeleportArrivalRelocationSystem(commandItemFeatureHandler));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-world-change-arrival",
                () -> new CommandWorldChangeArrivalSystem(commandWorldChangeTravelEventHandler));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-linked-npc-inventory-canonicalization",
                () -> new CommandLinkedNpcInventoryCanonicalizationSystem(commandItemFeatureHandler));
        CommandTargetHudActivationTracker commandTargetHudActivationTracker = new CommandTargetHudActivationTracker();
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud-active-slot",
                () -> new CommandTargetHudActiveSlotSystem(commandTargetHudActivationTracker));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud-inventory-change",
                () -> new CommandTargetHudInventoryChangeSystem(commandTargetHudActivationTracker));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud",
                () -> new CommandTargetHudService(commandItemRegistry, commandTargetHudActivationTracker));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-hotswap-hud",
                () -> new CommandHotswapHudService(commandItemRegistry));

        applyDebugConfigDefaults();
        settingsAnnouncementService = new TameworkSettingsAnnouncementService(this);

        // Global listener to enforce owner-only interactions.
        OwnerInteractionListener ownerInteractionListener =
                new OwnerInteractionListener(translationRegistry, getLogger());
        deferGlobalListener(
                TameworkRuntimeModule.DEBUG_SELF_TEST,
                "player-input-debug-interaction",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerInteractEvent.class,
                        PlayerInputDebugProbe::logPlayerInteract,
                        "player input debug interaction logging"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "owner-interaction-enforcement",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerInteractEvent.class,
                        ownerInteractionListener::onPlayerInteract,
                        "owner interaction enforcement"
                )
        );
        OwnerPresenceTimelineService ownerPresenceTimelineService = OwnerPresenceTimelineService.get();
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "owner-presence-connect",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerConnectEvent.class,
                        ownerPresenceTimelineService::onPlayerConnect,
                        "owner presence connect tracking"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "owner-presence-disconnect",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerDisconnectEvent.class,
                        ownerPresenceTimelineService::onPlayerDisconnect,
                        "owner presence disconnect tracking"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.COMMAND_ITEMS,
                "timed-summon-owner-logout",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerDisconnectEvent.class,
                        event -> timedSummonOwnerLifecycle.onOwnerLogout(
                                event == null || event.getPlayerRef() == null
                                        ? null : event.getPlayerRef().getUuid()
                        ),
                        "timed summon owner logout storage"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.AVATAR_FLIGHT,
                "avatar-flight-disconnect-cleanup",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerDisconnectEvent.class,
                        new AvatarFlightDisconnectRecoveryService()::onPlayerDisconnect,
                        "avatar flight disconnect cleanup"
                )
        );
        if (settingsAnnouncementService != null) {
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "settings-announcement-connect",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerConnectEvent.class,
                            settingsAnnouncementService::onPlayerConnect,
                            "settings announcement connect reset"
                    )
            );
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "settings-announcement-disconnect",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerDisconnectEvent.class,
                            settingsAnnouncementService::onPlayerDisconnect,
                            "settings announcement disconnect reset"
                    )
            );
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "settings-announcement-ready",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerReadyEvent.class,
                            settingsAnnouncementService::onPlayerReady,
                            "settings announcement ready prompt"
                    )
            );
        }
        if (namingFeatureHandler != null) {
            deferGlobalListener(
                    TameworkRuntimeModule.NAMING_ITEMS,
                    "name-item-chat-capture",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerChatEvent.class,
                            namingFeatureHandler::onPlayerChat,
                            "name item chat capture"
                    )
            );
            deferGlobalListener(
                    TameworkRuntimeModule.NAMING_ITEMS,
                    "name-item-disconnect-cleanup",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerDisconnectEvent.class,
                            namingFeatureHandler::onPlayerDisconnect,
                            "name item disconnect cleanup"
                    )
            );
        }
        if (commandItemFeatureHandler != null) {
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "command-travel-connect",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerConnectEvent.class,
                            commandWorldChangeTravelEventHandler::onPlayerConnect,
                            "command item travel session connect tracking"
                    )
            );
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "command-travel-disconnect",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            PlayerDisconnectEvent.class,
                            commandWorldChangeTravelEventHandler::onPlayerDisconnect,
                            "command item travel session disconnect cleanup"
                    )
            );
            deferGlobalListener(
                    TameworkRuntimeModule.COMMAND_ITEMS,
                    "command-travel-world-change",
                    () -> TameworkEventRegistrationSupport.registerGlobal(
                            this,
                            AddPlayerToWorldEvent.class,
                            commandWorldChangeTravelEventHandler::onAddPlayerToWorld,
                            "command item world-change relocation"
                    )
            );
        }
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "world-override-initialization",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        AddPlayerToWorldEvent.class,
                        this::onPlayerAddedToWorldForOverrides,
                        "loaded world override initialization"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.AVATAR_FLIGHT,
                "stale-avatar-flight-roster",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        AddPlayerToWorldEvent.class,
                        event -> {
                            PlayerRef playerRef = event == null || event.getHolder() == null
                                    ? null : event.getHolder().getComponent(
                                    PlayerRef.getComponentType()
                            );
                            if (playerRef != null
                                    && AvatarFlightStaleOwnerRecoveryRegistry.claim(
                                    playerRef.getUuid())) {
                                timedSummonOwnerLifecycle.onStaleAvatarFlightRecovery(
                                        playerRef.getUuid()
                                );
                            }
                        },
                        "stale avatar flight roster storage"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "crash-telemetry-world-cleanup",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        RemoveWorldEvent.class,
                        this::onWorldRemovedForCrashTelemetry,
                        "crash telemetry world cleanup"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.LEVELING,
                "progression-timing-world-cleanup",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        RemoveWorldEvent.class,
                        this::onWorldRemovedForProgressionTiming,
                        "progression timing world cleanup"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "spawn-beacon-world-cleanup",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        RemoveWorldEvent.class,
                        this::onWorldRemovedForSpawnBeaconVisualization,
                        "spawn beacon visualization world cleanup"
                )
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

        };
    }

    private void deferPersistenceIndependentRuntimeParticipants() {
        deferEntitySystem(TameworkRuntimeModule.SCARECROWS,
                "scarecrow-block-placed", ScarecrowBlockEventSystems.Placed::new);
        deferEntitySystem(TameworkRuntimeModule.SCARECROWS,
                "scarecrow-block-broken", ScarecrowBlockEventSystems.Broken::new);
        deferEntitySystem(TameworkRuntimeModule.AVATAR_FLIGHT,
                "avatar-flight-source-recovery", () -> new AvatarFlightSourceRecoverySystem(
                        avatarFlightSourceComponentType,
                        avatarFlightMountSessionComponentType,
                        UUIDComponent.getComponentType(),
                        DeathComponent.getComponentType()
                ));
        deferEntitySystem(TameworkRuntimeModule.AVATAR_FLIGHT,
                "avatar-flight-source-visibility", () -> new AvatarFlightSourceVisibilitySystem(
                        avatarFlightSourceComponentType,
                        EntityTrackerSystems.EntityViewer.getComponentType()
                ));
        deferChunkSystem(TameworkRuntimeModule.FOOD,
                "feed-trough-food-state-sync", FeedTroughFoodStateSyncSystem::new);
        deferDamageRuntimeParticipants();
    }

    private void deferDamageRuntimeParticipants() {
        deferEntityEventType(TameworkRuntimeModule.CORE_OWNERSHIP,
                "damage-event-type", Damage.class);
        deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                "damage-target-memory", DamageTargetMemorySystem::new);
        deferEntitySystem(TameworkRuntimeModule.CAPTURE,
                "tranquilized-sleep-animation-restore", TranquilizedSleepAnimationRestoreSystem::new);
        deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                "respawn-fall-damage-grace", RespawnFallDamageGraceSystem::new);
        deferEntitySystem(TameworkRuntimeModule.BONDED_PERSISTENCE,
                "expiry-dismount-fall-damage-protection", ExpiryDismountFallDamageProtectionSystem::new);
        deferEntitySystem(TameworkRuntimeModule.BONDED_PERSISTENCE,
                "expiry-dismount-landing-protection", ExpiryDismountLandingProtectionSystem::new);
        deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                "owner-damage-filter", () -> new OwnerDamageFilterSystem(
                        getLogger(), new SimpleClaimsTamedDamagePolicy(simpleClaimsCapabilityRuntime)));
        deferEntitySystem(TameworkRuntimeModule.TRAITS,
                "trait-damage-modifier", TraitDamageModifierSystem::new);
        deferEntitySystem(TameworkRuntimeModule.HAPPINESS,
                "companion-happiness-damage-impulse", CompanionHappinessDamageImpulseSystem::new);
        deferEntitySystem(TameworkRuntimeModule.LEVELING,
                "companion-combat-experience", CompanionCombatExperienceSystem::new);
        deferEntitySystem(TameworkRuntimeModule.DAMAGE_PROJECTILES,
                "projectile-impact-effect", () -> new TameworkProjectileImpactEffectSystem(
                        projectileImpactEffectComponentType,
                        com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType(),
                        TransformComponent.getComponentType()
                ));
        deferEntitySystem(TameworkRuntimeModule.DAMAGE_PROJECTILES,
                "lingering-hazard-projectile-spawn", () -> new TameworkLingeringHazardProjectileSpawnSystem(
                        lingeringHazardProjectileComponentType,
                        lingeringHazardComponentType,
                        com.hypixel.hytale.server.core.entity.entities.ProjectileComponent.getComponentType(),
                        TransformComponent.getComponentType()
                ));
        deferEntitySystem(TameworkRuntimeModule.DAMAGE_PROJECTILES,
                "lingering-hazard", () -> new TameworkLingeringHazardSystem(
                        lingeringHazardComponentType,
                        TransformComponent.getComponentType()
                ));
        deferEntitySystem(TameworkRuntimeModule.DAMAGE_PROJECTILES,
                "homing-visual-projectile", () -> new HomingVisualProjectileSystem(
                        homingVisualProjectileComponentType,
                        TransformComponent.getComponentType()
                ));
    }

    private void deferEntitySystem(
            TameworkRuntimeModule module,
            String participantId,
            Supplier<?> factory
    ) {
        runtimeParticipants.entitySystem(module, participantId, factory);
    }

    private void deferCraftingRecipeSubscriptions() {
        deferAssetSubscription(TameworkRuntimeModule.CAPTURE, "capture-crafting-recipe-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, CraftingRecipe.class,
                        (LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> ignored) ->
                                onCaptureCraftingRecipeAssetsChanged()));
        deferAssetSubscription(TameworkRuntimeModule.CAPTURE, "capture-crafting-recipe-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, CraftingRecipe.class,
                        (RemovedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> ignored) ->
                                onCaptureCraftingRecipeAssetsChanged()));
        deferAssetSubscription(TameworkRuntimeModule.FOOD, "food-crafting-recipe-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, CraftingRecipe.class,
                        (LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> ignored) ->
                                onFoodCraftingRecipeAssetsChanged()));
        deferAssetSubscription(TameworkRuntimeModule.FOOD, "food-crafting-recipe-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, CraftingRecipe.class,
                        (RemovedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> ignored) ->
                                onFoodCraftingRecipeAssetsChanged()));
    }

    private void deferChunkSystem(
            TameworkRuntimeModule module,
            String participantId,
            Supplier<?> factory
    ) {
        runtimeParticipants.chunkSystem(module, participantId, factory);
    }

    private void deferOptionalEntitySystem(
            TameworkRuntimeModule module,
            String participantId,
            Supplier<?> factory
    ) {
        runtimeParticipants.optionalEntitySystem(module, participantId, factory);
    }

    private <T extends EcsEvent> void deferEntityEventType(
            TameworkRuntimeModule module,
            String participantId,
            Class<? super T> eventType
    ) {
        runtimeParticipants.entityEventType(module, participantId, eventType);
    }

    /** Defers one global listener until the owning module is active. */
    private void deferGlobalListener(
            TameworkRuntimeModule module,
            String participantId,
            Runnable registration
    ) {
        runtimeParticipants.listener(module, participantId, registration);
    }

    /** Defers one asset event subscription until the owning module is active. */
    private void deferAssetSubscription(
            TameworkRuntimeModule module,
            String participantId,
            Runnable registration
    ) {
        runtimeParticipants.subscription(module, participantId, registration);
    }

    /** Defers ECS registration until the frozen activation plan is available. */
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
        prepareRuntimeActivation();
        if (runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            initializeCrashTelemetry();
        }
        TameworkActiveAssetInitializer.initialize(
                runtimeStartupPlan, populationGroupAssetRegistrar::initialize,
                this::rebuildCapturePolicyIndex, this::rebuildBondedCompanionRosterIndex);
        preflightDeclaredRuntimeParticipants();
        initializeRuntimeServices();
        registerRuntimeParticipants();
        runtimeActivationState = TameworkRuntimeActivationState.of(
                runtimeStartupPlan, runtimeStartupDiagnostics
        );
        getLogger().at(Level.INFO).log(runtimeActivationState.diagnostics().startupSummary());
        registerCommandRoot();
        if (runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            OwnerPresenceTimelineService.get().seedOnlinePlayersFromUniverse();
            initializeOverridesForLoadedWorlds();
        }
        getLogger().at(Level.INFO).log("Alec's Tamework! has been enabled!");
        if (crashTelemetryService != null
                && runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            crashTelemetryService.start();
        }
        if (assetEditorPackService != null
                && runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            assetEditorPackService.ensurePackVisible();
        }
    }

    private void registerCommandRoot() {
        if (getCommandRegistry() != null) {
            getCommandRegistry().registerCommand(new TameworkCommandRoot(
                    persistenceComposition == null ? null : persistenceComposition.diagnosticsReader(),
                    persistenceComposition == null ? null : persistenceComposition.diagnosticsExporter(),
                    bondedCompanionComposition == null ? null : bondedCompanionComposition.diagnostics(),
                    spawnBeaconVisualizationService
            ));
        }
    }

    /** Runs deferred persistence and feature-service construction after the plan is frozen. */
    private void initializeRuntimeServices() {
        if (runtimeServiceInitializer == null) {
            return;
        }
        Runnable initializer = runtimeServiceInitializer;
        runtimeServiceInitializer = null;
        initializer.run();
    }

    /** Validates setup-declared factories before persistence can mutate state. */
    private void preflightDeclaredRuntimeParticipants() {
        TameworkRuntimeRegistrationContext.Builder builder =
                TameworkRuntimeRegistrationContext.builder(
                        runtimeStartupPlan,
                        (kind, participantId) -> () -> { }
                );
        for (TameworkRuntimeRegistrationContext.Participant participant
                : runtimeParticipants.participants()) {
            builder.participant(participant);
        }
        new TameworkRuntimeRegistrar().preflight(builder.build());
    }

    /** Installs optional workers through the same preflight and ownership boundary as ECS work. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerRuntimeParticipants() {
        if (runtimeStartupPlan == null) {
            return;
        }
        TameworkRuntimeRegistrationContext.RegistrationTarget target =
                new TameworkRuntimeRegistrationTarget(
                        system -> getEntityStoreRegistry().registerSystem(
                                (ISystem<EntityStore>) system),
                        system -> getChunkStoreRegistry().registerSystem(
                                (ISystem<ChunkStore>) system)
                );
        TameworkRuntimeRegistrationContext.Builder contextBuilder =
                TameworkRuntimeRegistrationContext.builder(runtimeStartupPlan, target);
        for (TameworkRuntimeRegistrationContext.Participant participant
                : runtimeParticipants.participants()) {
            contextBuilder.participant(participant);
        }
        TameworkRuntimeRegistrationContext context = contextBuilder
                .participant(TameworkRuntimeRegistrationContext.Participant.prepared(
                        TameworkRuntimeModule.HSTATS,
                        "hstats-worker",
                        TameworkRuntimeRegistrationContext.RegistrationKind.WORKER,
                        () -> new TameworkHStatsIntegration(this),
                        (ignoredTarget, prepared) -> {
                            hStatsIntegration = (TameworkHStatsIntegration) prepared;
                            hStatsIntegration.initialize();
                            return hStatsIntegration;
                        }
                ))
                .build();
        runtimeHandle = new TameworkRuntimeRegistrar().register(
                context,
                participant -> TameworkRuntimeRegistrationTelemetry.record(
                        runtimeStartupDiagnostics, participant
                )
        );
    }
    /** Probes durable state and builds the private startup candidate. */
    private void prepareRuntimeActivation() {
        TameworkRuntimeActivationCoordinator.Preparation preparation =
                runtimeActivationCoordinator.prepare(getDataDirectory(), getLogger(), runtimeCapabilityRequests);
        runtimeStartupPlan = preparation.plan();
        runtimeStartupDiagnostics = new TameworkRuntimeDiagnostics(runtimeStartupPlan);
        genericPersistenceActivationEvidence = preparation.genericPersistence();
        bondedPersistenceActivationEvidence = preparation.bondedPersistence();
    }
    @Override
    protected void shutdown() {
        if (runtimeHandle != null) {
            try {
                runtimeHandle.close();
            } catch (RuntimeException exception) {
                getLogger().at(Level.WARNING).withCause(exception).log(
                        "Tamework runtime participant teardown was not clean."
                );
            }
            runtimeHandle = null;
        }
        spawnBeaconVisualizationService.close();
        if (patchworkRuntime != null) {
            try {
                patchworkRuntime.close();
                patchworkRuntime = null;
            } catch (RuntimeException exception) {
                getLogger().at(Level.SEVERE).withCause(exception).log(
                        "Patchwork did not shut down cleanly; ownership is retained for a later shutdown retry."
                );
            }
        }
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
        if (commandItemFeatureHandler != null) {
            commandItemFeatureHandler.close();
            commandItemFeatureHandler = null;
        }
        if (apiComposition != null) {
            apiComposition.close();
            apiComposition = null;
        }
        simpleClaimsCapabilityRuntime.close();
        api = null;
        closeBondedCompanions();
        if (commandNpcRelocationService != null) {
            commandNpcRelocationService.close();
            commandNpcRelocationService = null;
        }
        shutdownPersistence();
        if (apiEventBus != null) {
            apiEventBus.close();
            apiEventBus = null;
        }
        ownerPopulationLiveIndex.clear();
        runtimeDataDirectory = null;
        apiSelfTestFixtureManager = null;
        apiSelfTestRunner = null;
        crashTelemetryService = null;
        settingsAnnouncementService = null;
        runtimeActivationState = null;
        getLogger().at(Level.INFO).log("Alec's Tamework! has been disabled!");
    }

    private void shutdownPersistence() {
        if (persistenceComposition == null) {
            return;
        }
        PublicPersistenceShutdownReport report =
                persistenceComposition.shutdown();
        if (!report.terminal()) {
            getLogger().at(Level.SEVERE).log(
                    "Replacement persistence teardown is not terminal: "
                            + report.status() + " (outstanding workflows: "
                            + report.outstandingWorkflows() + ")."
            );
            return;
        }
        if (report.status()
                != PublicPersistenceShutdownReport.Status.COMPLETE) {
            getLogger().at(Level.WARNING).log(
                    "Replacement persistence teardown completed with "
                            + report.status() + "."
            );
        }
        persistenceComposition = null;
        persistenceBootstrap = null;
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

    private void onWorldRemovedForProgressionTiming(@Nonnull RemoveWorldEvent event) {
        if (event != null) {
            TameworkProgressionTimeScales.clearWorldScale(event.getWorld());
        }
    }

    private void onWorldRemovedForSpawnBeaconVisualization(@Nonnull RemoveWorldEvent event) {
        if (event != null && event.getWorld() != null) {
            spawnBeaconVisualizationService.removeWorld(event.getWorld());
        }
    }

    private void onPlayerAddedToWorldForOverrides(@Nonnull AddPlayerToWorldEvent event) {
        if (event == null || event.getWorld() == null) {
            return;
        }
        if (configOverrideManager != null) {
            initializeOverridesForWorld(event.getWorld());
        }
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
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
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

    @Nonnull
    public BreedingPairAdmissionRegistry getBreedingPairAdmissionRegistry() {
        return breedingPairAdmissionRegistry;
    }

    @Nonnull
    public SimpleClaimsCapabilityRuntime getSimpleClaimsCapabilityRuntime() {
        return simpleClaimsCapabilityRuntime;
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
    public TameworkApi getApi() {
        return api;
    }

    /** Refreshes runtime-backed API settings without exposing its implementation. */
    public void onRuntimeSettingsChanged() {
        if (apiComposition != null) {
            apiComposition.onRuntimeSettingsChanged();
        }
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

    /** Returns the immutable startup activation state after plugin start. */
    @Nullable
    public TameworkRuntimeActivationState getRuntimeActivationState() {
        return runtimeActivationState;
    }

    /**
     * Requests one runtime capability during setup.
     *
     * <p>Downstream plugins must request before Tamework publishes its startup
     * topology. A later request is rejected because live systems are restart-bound.</p>
     */
    public void requestRuntimeCapability(
            @Nonnull TameworkRuntimeModule module,
            @Nonnull String capabilityId
    ) {
        runtimeCapabilityRequests.request(module, capabilityId);
    }

    /** Compares current effective assets with the frozen startup topology. */
    @Nullable
    public TameworkReloadTopologyReport compareRuntimeActivationTopology() {
        if (runtimeActivationState == null) {
            return null;
        }
        return runtimeActivationCoordinator.compare(
                runtimeActivationState.plan(),
                runtimeCapabilityRequests.snapshot(),
                genericPersistenceActivationEvidence,
                bondedPersistenceActivationEvidence
        );
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
        endItemFeatureAssetReloadSuppression(true);
    }

    public void endItemFeatureAssetReloadSuppression(boolean applyPendingReload) {
        boolean shouldReload = false;
        synchronized (itemFeatureReloadSuppressionLock) {
            if (itemFeatureReloadSuppressionDepth > 0) {
                itemFeatureReloadSuppressionDepth--;
            }
            if (itemFeatureReloadSuppressionDepth == 0 && itemFeatureReloadPending) {
                itemFeatureReloadPending = false;
                shouldReload = applyPendingReload;
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
        TwConfigOverrideManager.ReloadResult result = configOverrideManager.reloadOverrides(world);
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
        return result;
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
        setDebugAvatarFlightEnabled(commands.isAvatarFlight());
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
                        + ", avatarFlight=" + isDebugAvatarFlightEnabled()
                        + ", despawnRoleFilter="
                        + (getDebugDespawnRoleFilter() == null ? "<none>" : getDebugDespawnRoleFilter())
        );
    }

    public int reloadItemFeatureConfigs() {
        if (itemFeatureRegistry == null) {
            return 0;
        }
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
        deferAssetSubscription(TameworkRuntimeModule.SPAWNER_ITEMS, "spawner-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwSpawnerConfig.class, this::onSpawnerAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.SPAWNER_ITEMS, "spawner-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwSpawnerConfig.class, this::onSpawnerAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.NAMING_ITEMS, "naming-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwNameItemConfig.class, this::onNamingAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.NAMING_ITEMS, "naming-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwNameItemConfig.class, this::onNamingAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.NAMING_ITEMS, "names-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwNamesConfig.class, this::onNamesAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.NAMING_ITEMS, "names-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwNamesConfig.class, this::onNamesAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.COMMAND_ITEMS, "command-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwCommandItemConfig.class, this::onCommandAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.COMMAND_ITEMS, "command-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwCommandItemConfig.class, this::onCommandAssetsRemoved));
        commandAssetsRegistered = true;
    }

    /** Keeps the isolated bonded authority reachable after generic startup aborts. */
    private void activateBondedOnlyFallback(RuntimeException failure) {
        api = new BondedOnlyTameworkApi(bondedCompanionComposition.api());
        if (getCommandRegistry() != null) {
            PersistenceDiagnosticExporter exporter =
                    PersistenceDiagnosticExporter.bondedOnly(
                            runtimeDataDirectory,
                            bondedCompanionComposition.diagnostics()
                    );
            getCommandRegistry().registerCommand(new TameworkCommandRoot(
                    null,
                    exporter,
                    bondedCompanionComposition.diagnostics(),
                    spawnBeaconVisualizationService
            ));
        }
        getLogger().at(Level.SEVERE).withCause(failure).log(
                "Generic persistence composition failed; bonded companion "
                        + "persistence remains isolated and available."
        );
    }

    private void closeBondedCompanions() {
        if (bondedDiagnosticRegistration != null) {
            try {
                bondedDiagnosticRegistration.close();
            } catch (Exception failure) {
                getLogger().at(Level.WARNING).withCause(failure).log(
                        "Bonded diagnostic aggregation teardown failed."
                );
            }
            bondedDiagnosticRegistration = null;
        }
        if (bondedCompanionComposition != null) {
            bondedCompanionComposition.close();
            bondedCompanionComposition = null;
        }
    }

    private void registerBondedCompanionRosterAssets() {
        if (bondedCompanionRosterAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(
                                TwBondedCompanionRosterConfig.class,
                                new DefaultAssetMap<>()
                        )
                        .setPath("Tamework/BondedCompanions/Rosters")
                        .setCodec(TwBondedCompanionRosterConfig.CODEC)
                        .setKeyFunction(
                                TwBondedCompanionRosterConfig::getId
                        )
                        .build()
        );
        deferAssetSubscription(TameworkRuntimeModule.BONDED_PERSISTENCE, "bonded-roster-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwBondedCompanionRosterConfig.class,
                        this::onBondedCompanionRosterAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.BONDED_PERSISTENCE, "bonded-roster-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwBondedCompanionRosterConfig.class,
                        this::onBondedCompanionRosterAssetsRemoved
                ));
        bondedCompanionRosterAssetsRegistered = true;
    }

    @Nullable
    ComponentType<EntityStore, SpawnMarkerReference> resolveOptionalSpawnMarkerReferenceComponentType() {
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
    ComponentType<EntityStore, SpawnBeaconReference> resolveOptionalSpawnBeaconReferenceComponentType() {
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

    @Nullable
    ComponentType<EntityStore, SpawnMarkerEntity> resolveOptionalSpawnMarkerEntityComponentType() {
        try {
            return SpawnMarkerEntity.getComponentType();
        } catch (RuntimeException | LinkageError error) {
            getLogger().at(Level.WARNING).withCause(error).log(
                    "SpawnMarkerEntity component type is unavailable; loaded marker reverse-reference repair is "
                            + "disabled."
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
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "global-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwGlobalConfig.class, this::onGlobalAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "global-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwGlobalConfig.class, this::onGlobalAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "companion-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwCompanionConfig.class, this::onCompanionAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "companion-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwCompanionConfig.class, this::onCompanionAssetsRemoved));
        companionAssetsRegistered = true;
    }

    private void registerCapturePolicyAssets() {
        if (capturePolicyAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwCapturePolicyConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/CapturePolicies")
                        .setCodec(TwCapturePolicyConfig.CODEC)
                        .setKeyFunction(TwCapturePolicyConfig::getId)
                        .build()
        );
        deferAssetSubscription(TameworkRuntimeModule.CAPTURE, "capture-policy-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class, TwCapturePolicyConfig.class, this::onCapturePolicyAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.CAPTURE, "capture-policy-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class, TwCapturePolicyConfig.class, this::onCapturePolicyAssetsRemoved));
        capturePolicyAssetsRegistered = true;
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
        deferAssetSubscription(TameworkRuntimeModule.INTERACTIONS, "interaction-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwInteractionConfig.class, this::onInteractionAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.INTERACTIONS, "interaction-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwInteractionConfig.class, this::onInteractionAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.MOUNTS, "mounted-glide-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwMountedGlideConfig.class, this::onMountedGlideAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.MOUNTS, "mounted-glide-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwMountedGlideConfig.class, this::onMountedGlideAssetsRemoved));
        mountedGlideAssetsRegistered = true;
    }

    private void registerMountedDescentAssets() {
        if (mountedDescentAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwMountedDescentConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/Mounts/Descent")
                        .setCodec(TwMountedDescentConfig.CODEC)
                        .setKeyFunction(TwMountedDescentConfig::getId)
                        .build()
        );
        deferAssetSubscription(TameworkRuntimeModule.MOUNTS, "mounted-descent-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwMountedDescentConfig.class,
                        this::onMountedDescentAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.MOUNTS, "mounted-descent-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwMountedDescentConfig.class,
                        this::onMountedDescentAssetsRemoved
                ));
        mountedDescentAssetsRegistered = true;
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
        deferAssetSubscription(TameworkRuntimeModule.AVATAR_FLIGHT, "avatar-flight-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwAvatarFlightConfig.class, this::onAvatarFlightAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.AVATAR_FLIGHT, "avatar-flight-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwAvatarFlightConfig.class, this::onAvatarFlightAssetsRemoved));
        avatarFlightAssetsRegistered = true;
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
        deferAssetSubscription(TameworkRuntimeModule.COOPS, "coop-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwCoopConfig.class, this::onCoopAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.COOPS, "coop-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwCoopConfig.class, this::onCoopAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.HAPPINESS, "happiness-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwHappinessConfig.class, this::onHappinessAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.HAPPINESS, "happiness-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwHappinessConfig.class, this::onHappinessAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.FOOD, "food-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwFoodConfig.class, this::onFoodAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.FOOD, "food-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwFoodConfig.class, this::onFoodAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.NEEDS, "needs-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwNeedsConfig.class, this::onNeedsAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.NEEDS, "needs-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwNeedsConfig.class, this::onNeedsAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.BREEDING, "breeding-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwBreedingConfig.class, this::onBreedingAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.BREEDING, "breeding-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwBreedingConfig.class, this::onBreedingAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "attachment-migration-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwAttachmentMigrationConfig.class,
                        this::onAttachmentMigrationAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "attachment-migration-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwAttachmentMigrationConfig.class,
                        this::onAttachmentMigrationAssetsRemoved
                ));
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
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "attachment-display-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwAttachmentDisplayConfig.class,
                        this::onAttachmentDisplayAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "attachment-display-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwAttachmentDisplayConfig.class,
                        this::onAttachmentDisplayAssetsRemoved
                ));
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
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "dynamic-attachments-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwDynamicAttachmentsConfig.class,
                        this::onDynamicAttachmentsAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.ATTACHMENTS, "dynamic-attachments-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwDynamicAttachmentsConfig.class,
                        this::onDynamicAttachmentsAssetsRemoved
                ));
        dynamicAttachmentsAssetsRegistered = true;
    }

    private void registerCompanionMovementAssets() {
        if (companionMovementAssetsRegistered) {
            return;
        }
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwCompanionMovementConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/CompanionMovement")
                        .setCodec(TwCompanionMovementConfig.CODEC)
                        .setKeyFunction(TwCompanionMovementConfig::getId)
                        .build()
        );
        deferAssetSubscription(TameworkRuntimeModule.COMPANION_MOVEMENT, "companion-movement-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class,
                        TwCompanionMovementConfig.class,
                        this::onCompanionMovementAssetsLoaded
                ));
        deferAssetSubscription(TameworkRuntimeModule.COMPANION_MOVEMENT, "companion-movement-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class,
                        TwCompanionMovementConfig.class,
                        this::onCompanionMovementAssetsRemoved
                ));
        companionMovementAssetsRegistered = true;
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
        deferAssetSubscription(TameworkRuntimeModule.LEVELING, "leveling-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwLevelingConfig.class, this::onLevelingAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.LEVELING, "leveling-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwLevelingConfig.class, this::onLevelingAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.TRAITS, "trait-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwTraitConfig.class, this::onTraitAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.TRAITS, "trait-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwTraitConfig.class, this::onTraitAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.TALENTS, "talent-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwTalentConfig.class, this::onTalentAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.TALENTS, "talent-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwTalentConfig.class, this::onTalentAssetsRemoved));
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
        deferAssetSubscription(TameworkRuntimeModule.DEBUG_SELF_TEST, "debug-config-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class, TwDebugConfig.class, this::onDebugAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.DEBUG_SELF_TEST, "debug-config-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class, TwDebugConfig.class, this::onDebugAssetsRemoved));
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

    private void onCaptureCraftingRecipeAssetsChanged() {
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileTranquilizerRecipeVisibility();
    }

    private void onFoodCraftingRecipeAssetsChanged() {
        if (deferGlobalReconcileIfSuppressed()) {
            return;
        }
        reconcileFeedTroughWaterChargeDroplistCompat();
    }

    private void onItemAssetsLoaded(
            LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        if (spawnerReloadPendingOnItemAssets) {
            int loaded = loadSpawnerItemAssets();
            if (loaded > 0) {
                getLogger().at(Level.INFO).log(
                        "Recovered deferred spawner config reload after referenced Item assets loaded: "
                                + loaded + " config(s)."
                );
            }
        }
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

    private void onCapturePolicyAssetsLoaded(
            LoadedAssetsEvent<String, TwCapturePolicyConfig,
                    DefaultAssetMap<String, TwCapturePolicyConfig>> event) {
        if (rebuildCapturePolicyIndex() && !event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.CAPTURE_POLICY, event.getLoadedAssets().keySet());
        }
    }

    private void onCapturePolicyAssetsRemoved(
            RemovedAssetsEvent<String, TwCapturePolicyConfig,
                    DefaultAssetMap<String, TwCapturePolicyConfig>> event) {
        if (rebuildCapturePolicyIndex()) {
            emitExperimentalConfigReload(TameworkConfigFamily.CAPTURE_POLICY, event.getRemovedAssets());
        }
    }

    private void onBondedCompanionRosterAssetsLoaded(
            LoadedAssetsEvent<
                    String,
                    TwBondedCompanionRosterConfig,
                    DefaultAssetMap<String, TwBondedCompanionRosterConfig>
                    > event
    ) {
        rebuildBondedCompanionRosterIndex();
    }

    private void onBondedCompanionRosterAssetsRemoved(
            RemovedAssetsEvent<
                    String,
                    TwBondedCompanionRosterConfig,
                    DefaultAssetMap<String, TwBondedCompanionRosterConfig>
                    > event
    ) {
        rebuildBondedCompanionRosterIndex();
    }

    private boolean rebuildBondedCompanionRosterIndex() {
        TwBondedCompanionRosterConfig.clearInheritanceFallbackCache();
        TwCommandItemConfig.clearInheritanceFallbackCache();
        BondedCompanionConfigReloadService.ReloadResult result =
                reloadBondedCompanionConfigGeneration();
        if (!result.applied()) {
            for (String error : result.errors()) {
                getLogger().at(Level.WARNING).log(
                        "Bonded companion config reload rejected; retaining "
                                + "roster revision " + result.rosterRevision()
                                + " and command revision "
                                + result.commandRevision() + ": " + error
                );
            }
            return false;
        }
        return true;
    }

    private boolean rebuildCapturePolicyIndex() {
        TwCapturePolicyConfig.clearInheritanceFallbackCache();
        if (capturePolicyRegistry == null) {
            return false;
        }
        DefaultAssetMap<String, TwCapturePolicyConfig> assetMap = TwCapturePolicyConfig.getAssetMap();
        java.util.Collection<TwCapturePolicyConfig> configs = assetMap == null
                ? java.util.List.of()
                : assetMap.getAssetMap().values();
        CapturePolicyRegistry.ReloadResult result = capturePolicyRegistry.replace(
                configs, ++capturePolicyAssetRevision);
        if (!result.applied()) {
            getLogger().at(Level.WARNING).log(
                    "Capture-policy reload rejected; retaining revision "
                            + result.active().revision() + ": " + result.error());
        }
        return result.applied();
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

    private void onMountedDescentAssetsLoaded(
            LoadedAssetsEvent<String, TwMountedDescentConfig, DefaultAssetMap<String, TwMountedDescentConfig>> event) {
        TwMountedDescentConfig.clearProfileCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.MOUNTED_DESCENT, event.getLoadedAssets().keySet());
        }
    }

    private void onMountedDescentAssetsRemoved(
            RemovedAssetsEvent<String, TwMountedDescentConfig, DefaultAssetMap<String, TwMountedDescentConfig>> event) {
        TwMountedDescentConfig.clearProfileCache();
        emitExperimentalConfigReload(TameworkConfigFamily.MOUNTED_DESCENT, event.getRemovedAssets());
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
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
        CompanionHappinessModifierService.clearCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.BREEDING, event.getLoadedAssets().keySet());
        }
    }

    private void onBreedingAssetsRemoved(
            RemovedAssetsEvent<String, TwBreedingConfig, DefaultAssetMap<String, TwBreedingConfig>> event) {
        TwBreedingConfig.clearRoleCache();
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
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

    private void onCompanionMovementAssetsLoaded(
            LoadedAssetsEvent<String, TwCompanionMovementConfig,
                    DefaultAssetMap<String, TwCompanionMovementConfig>> event) {
        TwCompanionMovementConfig.clearRoleCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.COMPANION_MOVEMENT, event.getLoadedAssets().keySet());
        }
    }

    private void onCompanionMovementAssetsRemoved(
            RemovedAssetsEvent<String, TwCompanionMovementConfig,
                    DefaultAssetMap<String, TwCompanionMovementConfig>> event) {
        TwCompanionMovementConfig.clearRoleCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        emitExperimentalConfigReload(TameworkConfigFamily.COMPANION_MOVEMENT, event.getRemovedAssets());
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
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.TRAIT, event.getLoadedAssets().keySet());
        }
    }

    private void onTraitAssetsRemoved(
            RemovedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
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
        if (itemFeatureRegistry == null || spawnerItemConfigReloadService == null) {
            return 0;
        }
        DefaultAssetMap<String, TwSpawnerConfig> assetMap = TwSpawnerConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null) {
            return 0;
        }
        SpawnerItemConfigReloadService.ReloadResult result =
                spawnerItemConfigReloadService.reload(assetMap.getAssetMap().values());
        if (!result.applied()) {
            spawnerReloadPendingOnItemAssets = result.retryableAfterItemAssetsLoad();
            for (String error : result.errors()) {
                getLogger().at(Level.WARNING).log(
                        "Spawner config reload rejected at active revision "
                                + result.activeRevision() + "; retaining last-valid registry: " + error);
            }
            return 0;
        }
        spawnerReloadPendingOnItemAssets = false;
        return result.loadedCount();
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
        BondedCompanionConfigReloadService.ReloadResult result =
                reloadBondedCompanionConfigGeneration();
        if (!result.applied()) {
            for (String error : result.errors()) {
                getLogger().at(Level.WARNING).log(
                        "Command/bonded roster config reload rejected; "
                                + "retaining last coherent generation: "
                                + error
                );
            }
            return 0;
        }
        return result.commandCount();
    }

    private BondedCompanionConfigReloadService.ReloadResult
            reloadBondedCompanionConfigGeneration() {
        if (bondedCompanionConfigReloadService == null) {
            return new BondedCompanionConfigReloadService.ReloadResult(
                    false, 0, 0, 0L, 0L,
                    java.util.List.of("bonded-config-reload-service-unavailable")
            );
        }
        DefaultAssetMap<String, TwBondedCompanionRosterConfig> rosterAssets =
                TwBondedCompanionRosterConfig.getAssetMap();
        DefaultAssetMap<String, TwCommandItemConfig> commandAssets =
                TwCommandItemConfig.getAssetMap();
        java.util.Collection<TwBondedCompanionRosterConfig> rosters =
                rosterAssets == null || rosterAssets.getAssetMap() == null
                        ? java.util.List.of()
                        : rosterAssets.getAssetMap().values();
        java.util.Collection<TwCommandItemConfig> commands =
                commandAssets == null || commandAssets.getAssetMap() == null
                        ? java.util.List.of()
                        : commandAssets.getAssetMap().values();
        return bondedCompanionConfigReloadService.reload(rosters, commands);
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

    @Nonnull
    public OwnerPopulationLiveIndex getOwnerPopulationLiveIndex() {
        return ownerPopulationLiveIndex;
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

    public ComponentType<EntityStore, TameworkShoulderRideComponent> getShoulderRideComponentType() {
        return shoulderRideComponentType;
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

    public ComponentType<EntityStore, AvatarFlightMountSessionComponent> getAvatarFlightMountSessionComponentType() {
        return avatarFlightMountSessionComponentType;
    }

    public ComponentType<EntityStore, AvatarFlightSourceComponent> getAvatarFlightSourceComponentType() {
        return avatarFlightSourceComponentType;
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

    public ComponentType<EntityStore, HomingVisualProjectileComponent> getHomingVisualProjectileComponentType() {
        return homingVisualProjectileComponentType;
    }

    public ComponentType<EntityStore, TameworkInventoryOperationReceiptsComponent>
            getInventoryOperationReceiptsComponentType() {
        return inventoryOperationReceiptsComponentType;
    }

    public ComponentType<EntityStore, TameworkBondedReviveEscrowComponent>
            getBondedReviveEscrowComponentType() {
        return bondedReviveEscrowComponentType;
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

    public boolean isDebugAvatarFlightEnabled() {
        return debugAvatarFlightLogs;
    }

    public boolean setDebugAvatarFlightEnabled(boolean enabled) {
        debugAvatarFlightLogs = enabled;
        return debugAvatarFlightLogs;
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
    ComponentType<EntityStore, NPCMountComponent> resolveNpcMountComponentTypeOrNull() {
        try {
            return NPCMountComponent.getComponentType();
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Nullable
    ComponentType<EntityStore, MountedComponent> resolveMountedComponentTypeOrNull() {
        try {
            return MountedComponent.getComponentType();
        } catch (Throwable throwable) {
            return null;
        }
    }

}



