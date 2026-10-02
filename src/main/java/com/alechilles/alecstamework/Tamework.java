package com.alechilles.alecstamework;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.TameworkProgressionTimeScales;
import com.alechilles.alecstamework.api.internal.AdmissionProviderRegistry;
import com.alechilles.alecstamework.api.internal.CommandHudRegistry;
import com.alechilles.alecstamework.api.internal.CompanionEventPublisher;
import com.alechilles.alecstamework.api.internal.IndexDiagnosticsApi;
import com.alechilles.alecstamework.api.internal.IndexNpcProfilesApi;
import com.alechilles.alecstamework.api.internal.IndexPopulationGroupApi;
import com.alechilles.alecstamework.api.internal.IndexProfileDataApi;
import com.alechilles.alecstamework.api.internal.IndexTameworkApi;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRegistry;
import com.alechilles.alecstamework.api.internal.InteractionExtensionRuntime;
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
import com.alechilles.alecstamework.commands.SpawnBeaconVisualizationService;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.NameItemRegistry;
import com.alechilles.alecstamework.config.SpawnerItemConfigReloadService;
import com.alechilles.alecstamework.items.CommandNpcPortraitAssets;
import com.alechilles.alecstamework.config.bonded.BondedCompanionConfigReloadService;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import com.alechilles.alecstamework.config.managed.ManagedActivityAssetRegistrar;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
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
import com.alechilles.alecstamework.config.assets.TwDynamicIconConfig;
import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwMountedGlideConfig;
import com.alechilles.alecstamework.config.assets.TwMountedDescentConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.actions.HusbandryHarvestUseContext;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNamesConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.compat.HytaleApiLevel;
import com.alechilles.alecstamework.damage.DamageTargetMemorySystem;
import com.alechilles.alecstamework.damage.OwnerDamageFilterSystem;
import com.alechilles.alecstamework.damage.CompanionHappinessDamageImpulseSystem;
import com.alechilles.alecstamework.damage.CompanionCombatExperienceSystem;
import com.alechilles.alecstamework.damage.CompanionCombatDefeatSystem;
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
import com.alechilles.alecstamework.items.OwnedNpcTransformationInteractionService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionSignalBus;
import com.alechilles.alecstamework.integration.creditor.CreditorIntegration;
import com.alechilles.alecstamework.integration.nameplatebuilder.NameplateBuilderBridgeLoader;
import com.alechilles.alecstamework.items.CommandItemFeatureHandler;
import com.alechilles.alecstamework.items.TameworkNpcCullService;
import com.alechilles.alecstamework.items.CaptureChannelVfxSystem;
import com.alechilles.alecstamework.items.CaptureChannelSessionCleanupSystem;
import com.alechilles.alecstamework.items.capturepolicy.CapturePolicyRegistry;
import com.alechilles.alecstamework.items.CommandWorldChangeArrivalSystem;
import com.alechilles.alecstamework.items.CommandWorldChangeTravelEventHandler;
import com.alechilles.alecstamework.items.CommandLinkedNpcInventoryCanonicalizationSystem;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.items.CommandHotswapHudService;
import com.alechilles.alecstamework.items.CommandNpcRelocationService;
import com.alechilles.alecstamework.items.CommandHudDirtySink;
import com.alechilles.alecstamework.items.CommandActiveNpcHighlightSystem;
import com.alechilles.alecstamework.items.CommandHudPlayerLifecycleSystem;
import com.alechilles.alecstamework.items.CommandHudStoreLifecycleSystem;
import com.alechilles.alecstamework.items.CommandTargetHudActivationTracker;
import com.alechilles.alecstamework.items.CommandTargetHudActiveSlotSystem;
import com.alechilles.alecstamework.items.CommandTargetHudInventoryChangeSystem;
import com.alechilles.alecstamework.items.CommandTargetHudService;
import com.alechilles.alecstamework.items.CommandTargetInspector;
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
import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.localization.ModLanguageDiscovery;
import com.alechilles.alecstamework.localization.TranslationRegistry;
import com.alechilles.alecstamework.metrics.CrashTelemetryService;
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
import com.alechilles.alecstamework.npc.progression.NeedsResourceHotPathDiagnostics;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.persistence.TameworkDataPathService;
import com.alechilles.alecstamework.persistence.activation.TameworkPersistenceActivationEvidence;
import com.alechilles.alecstamework.ownership.live.OwnerPopulationLiveIndex;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureManager;
import com.alechilles.alecstamework.selftest.ApiSelfTestFixtureMarkerComponent;
import com.alechilles.alecstamework.selftest.ApiSelfTestRunner;
import com.alechilles.alecstamework.ui.TameworkSettingsAnnouncementService;
import com.alechilles.alecstamework.ui.LinkedNpcPanelPortraitItemIndex;
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
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.alechilles.alecstamework.api.internal.CommandUiRegistry;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.admission.ProviderDecisionCache;
import com.alechilles.alecstamework.items.locate.CaptureItemHolderSystems;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.companion.flow.CompanionBodyLifecycle;
import com.alechilles.alecstamework.companion.item.AdmissionCache;
import com.alechilles.alecstamework.companion.item.CaptureItemFlows;
import com.alechilles.alecstamework.companion.flow.CompanionBodies;
import com.alechilles.alecstamework.companion.flow.CompanionSnapshotSource;
import com.alechilles.alecstamework.companion.flow.HytaleCompanionSpawner;
import com.alechilles.alecstamework.companion.flow.CaptureFlow;
import com.alechilles.alecstamework.companion.flow.HytaleCaptureDelivery;
import com.alechilles.alecstamework.companion.flow.RestoreFlow;
import com.alechilles.alecstamework.companion.flow.CompanionOwnerDeathSystem;
import com.alechilles.alecstamework.companion.flow.HytaleStoreCapture;
import com.alechilles.alecstamework.companion.flow.RosterSummons;
import com.alechilles.alecstamework.companion.flow.StoreFlow;
import com.alechilles.alecstamework.companion.flow.SummonExpiryScheduler;
import com.alechilles.alecstamework.items.CompanionRestoreRecallSink;
import com.hypixel.hytale.component.Ref;
import com.alechilles.alecstamework.companion.flow.CompanionStartupAdmission;
import com.alechilles.alecstamework.companion.flow.CompanionWorldRemovalListener;
import com.alechilles.alecstamework.companion.live.CompanionBodySystem;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.CompanionSummaries;
import com.alechilles.alecstamework.companion.live.HytaleSummarySources;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.runtime.CompanionPersistenceModule;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.alechilles.alecstamework.companion.store.CompanionStorage;
import com.alechilles.alecstamework.companion.store.HytaleCompanionFileIo;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import java.nio.file.Files;
import com.hypixel.hytale.server.npc.components.SpawnBeaconReference;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

/**
 * Main entry point for the Alec's Tamework! plugin.
 */
public class Tamework extends JavaPlugin {
    private static Tamework instance;
    private final Object companionProgressionSignalToken = new Object();

    private ItemFeatureRegistry itemFeatureRegistry;
    private SpawnerItemConfigReloadService spawnerItemConfigReloadService;
    private final CommandNpcPortraitAssets commandNpcPortraitAssets = new CommandNpcPortraitAssets();
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
    /**
     * The public API, built in setup once the companion module is ready. Null in
     * migration-required mode, when the companion store failed to open, and after shutdown;
     * {@link #getApi()} callers handle null.
     */
    @Nullable
    private volatile IndexTameworkApi api;
    private CompanionPersistenceModule companionModule;
    /** Whether an NPC UUID is a body an imported world knew for a live record; set with the companion runtime. */
    private java.util.function.Predicate<java.util.UUID> companionLegacyBody = npcUuid -> false;
    /** Backs {@code /tw persistence start-fresh}; null unless old saves block this world. */
    @Nullable
    private Supplier<CompanionPersistenceModule.FreshStart> companionStartFresh;
    @Nullable
    private volatile AdmissionCache captureAdmissionCache;
    /** Cached admission provider decisions for the synchronous sites; closed in {@link #closeApiComposition}. */
    @Nullable
    private volatile ProviderDecisionCache providerDecisionCache;
    private volatile CaptureItemHolderSystems.Transfers captureItemTransfers;
    private ReleaseFlow companionReleaseFlow;
    /** Forget, Recall and destroyed capture items (spec 8.14); null unless the companion module is ready. */
    @Nullable
    private CaptureItemFlows captureItemFlows;
    /** Roster summon and store; null unless the companion module is ready and generic persistence is active. */
    private RosterSummons companionRosterSummons;
    private com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi bondedCompanionApi;
    /** Summon aura, expiry warning and expiry fall protection of bonded companions; closed with the bonded API. */
    private com.alechilles.alecstamework.companion.bonded.BondedSummonEffects bondedSummonEffects;
    private CompanionStartupAdmission companionStartupAdmission;
    /** Retired 3.x/4.x entity component types that nothing reads any more (plan 7 R15). */
    private List<ComponentType<EntityStore, ?>> retiredEntityComponentTypes = List.of();
    private com.alechilles.alecstamework.companion.migrate.RetiredComponentCleanup retiredComponentCleanup;
    private TameworkEventBus apiEventBus;
    private CompanionProgressionSignalBus companionProgressionSignalBus;
    private AutoCloseable companionXpLegacyAdapter;
    private InteractionExtensionRegistry interactionExtensionRegistry;
    private TraitEffectRegistry traitEffectRegistry;
    private CapturePolicyRegistry capturePolicyRegistry;
    private BondedCompanionRosterRegistry bondedCompanionRosterRegistry;
    private BondedCompanionConfigReloadService bondedCompanionConfigReloadService;
    private PopulationGroupConfigRegistry populationGroupConfigRegistry;
    private PopulationGroupAssetRegistrar populationGroupAssetRegistrar;
    private ManagedActivityConfigRegistry managedActivityConfigRegistry;
    /** External admission providers; built with the public API and closed with it. */
    private AdmissionProviderRegistry admissionProviderRegistry;
    private ManagedActivityAssetRegistrar managedActivityAssetRegistrar;
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
    private TameworkDiagnosticRuntime diagnosticRuntime;
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
    private boolean dynamicIconAssetsRegistered;
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
    /** Retired; the coop schedule system strips it from coop blocks. */
    private ComponentType<ChunkStore,
            com.alechilles.alecstamework.companion.coop.runtime.TameworkCoopCaptureReceiptsComponent>
            coopCaptureReceiptsComponentType;
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

    /** Checks the private token used by the internal progression composition. */
    public final boolean ownsCompanionProgressionSignalToken(@Nullable Object candidate) {
        return candidate != null && candidate == companionProgressionSignalToken;
    }

    @Override
    protected void setup() {
        setupInternal();
    }

    private void setupInternal() {
        companionProgressionSignalBus = new CompanionProgressionSignalBus();
        if (!CompanionProgressionSignalBus.installForTamework(
                this, companionProgressionSignalToken, companionProgressionSignalBus)) {
            companionProgressionSignalBus = null;
            throw new IllegalStateException(
                    "Could not install Tamework's companion progression signal bus.");
        }
        runtimeParticipants = new TameworkRuntimeParticipantRegistry(
                eventType -> getEntityStoreRegistry().registerEntityEventType(eventType),
                getLogger()
        );
        itemFeatureRegistry = new ItemFeatureRegistry();
        spawnerItemConfigReloadService = new SpawnerItemConfigReloadService(itemFeatureRegistry);
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
        managedActivityConfigRegistry = new ManagedActivityConfigRegistry(
                populationGroupConfigRegistry
        );
        managedActivityAssetRegistrar = new ManagedActivityAssetRegistrar(
                this,
                managedActivityConfigRegistry,
                this::emitExperimentalConfigReload
        );
        populationGroupAssetRegistrar = new PopulationGroupAssetRegistrar(
                this,
                populationGroupConfigRegistry,
                this::publishPopulationConfigReload
        );
        assetEditorPackService = new TameworkAssetEditorPackService(this);
        // Patchwork must subscribe to Hytale's one-time LoadAssetEvent before plugin setup returns.
        try {
            patchworkRuntime = new TameworkPatchworkRuntime(this);
            patchworkRuntime.start();
            configOverrideManager = new TwConfigOverrideManager(
                    this, patchworkRuntime::generatedPatchRoot
            );
        } catch (RuntimeException | LinkageError exception) {
            getLogger().at(Level.SEVERE).withCause(exception).log(
                    "Patchwork failed to start; Tamework will continue without generated asset patches."
            );
        }
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
        managedActivityAssetRegistrar.registerAssetStore();
        deferAssetSubscription(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "population-group-assets",
                populationGroupAssetRegistrar::activate
        );
        deferAssetSubscription(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "managed-activity-assets",
                managedActivityAssetRegistrar::activate
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
        registerDynamicIconAssets();
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
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "item-assets-loaded",
                () -> getEventRegistry().register(
                        LoadedAssetsEvent.class, Item.class, this::onItemAssetsLoaded
                )
        );
        deferAssetSubscription(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "item-assets-removed",
                () -> getEventRegistry().register(
                        RemovedAssetsEvent.class, Item.class, this::onItemAssetsRemoved
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
        coopCaptureReceiptsComponentType = components.coopCaptureReceipts();
        retiredEntityComponentTypes = List.of(components.persistenceRetirement(),
                components.captureSourceReceipts(), components.inventoryOperationReceipts());

        spawnMarkerEntityType = TameworkCompanionRuntimeParticipants.add(this, runtimeParticipants);
        deferPersistenceIndependentRuntimeParticipants();

        runtimeServiceInitializer = () -> {
        if (runtimeStartupPlan == null) {
            return;
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
        companionXpLegacyAdapter = companionProgressionSignalBus.installLegacyAdapter(
                this, companionProgressionSignalToken, apiEventBus);
        if (companionXpLegacyAdapter == null) {
            throw new IllegalStateException(
                    "Could not install Tamework's companion XP legacy adapter.");
        }
        TameworkDataPathLayout dataPaths = new TameworkDataPathService(getLogger())
                .resolveAndInitializeDataPathLayout(getDataDirectory());
        runtimeDataDirectory = dataPaths.targetDirectory();
        com.alechilles.alecstamework.npc.progression.AnimalProgressionClock.get().start(runtimeDataDirectory);
        if (diagnosticRuntime != null) {
            diagnosticRuntime.preparePersistence(runtimeDataDirectory);
        }
        openCompanionPersistence(dataPaths.persistenceSourceDirectories());
        ReleaseFlow releaseFlow = null;
        CompanionQueries companionQueries = null;
        RestoreFlow<Ref<EntityStore>> restoreFlow = null;
        CompanionRestoreRecallSink recallRestore = null;
        CompanionAdmissionGate admissionGate = null;
        ProviderAdmission providerAdmission = ProviderAdmission.none();
        // Bonded families come from the roster config on every call, so a reload applies at once.
        com.alechilles.alecstamework.companion.bonded.BondedRecords.Families bondedFamilies =
                com.alechilles.alecstamework.companion.bonded.BondedRecords.families(bondedCompanionRosterRegistry);
        if (companionModule != null && companionModule.ready()) {
            releaseFlow = new ReleaseFlow(companionModule.index(), companionModule.loaded(),
                    companionModule.writer()::queueSnapshotDelete);
            companionQueries = companionModule.queries();
            admissionProviderRegistry = new AdmissionProviderRegistry();
            providerAdmission = ProviderAdmission.of(managedActivityConfigRegistry, admissionProviderRegistry);
            ProviderDecisionCache providerDecisions = startProviderDecisionCache(providerAdmission);
            admissionGate = new CompanionAdmissionGate(companionModule.index(), populationGroupConfigRegistry::snapshot,
                    providerDecisions);
            OwnerPopulationCapService.useAdmissionGate(admissionGate);
            restoreFlow = createRestoreFlow(companionModule, providerAdmission, admissionGate, bondedFamilies);
            captureItemFlows = new CaptureItemFlows(companionModule.index(),
                    companionModule.writer()::queueSnapshotDelete, restoreFlow);
            recallRestore = new CompanionRestoreRecallSink(restoreFlow, companionQueries);
            registerCompanionPersistenceRuntime(admissionGate, restoreFlow, bondedFamilies);
            // One store flow serves the bonded API, roster dismiss, summon expiry and owner logout.
            StoreFlow<Ref<EntityStore>> storeFlow = createStoreFlow(companionModule);
            bondedCompanionApi = createBondedCompanionApi(companionModule, restoreFlow, storeFlow, bondedFamilies);
            // A provisioned bonded companion counts against the built-in owned caps, like a captured
            // one. Remove this one call to count provisioned companions against family limits only.
            bondedCompanionApi.useBuiltInCaps(admissionGate::deny);
            companionRosterSummons = startRosterSummons(companionModule, restoreFlow, storeFlow, bondedCompanionApi);
        } else if (companionModule != null && companionModule.legacyKind() == null) {
            registerCompanionPersistenceFailedNotice();
        }
        this.companionReleaseFlow = releaseFlow;
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
        commandNpcRelocationService = recallRestore != null
                ? new CommandNpcRelocationService(getLogger(), recallRestore)
                : new CommandNpcRelocationService(getLogger());
        commandLinkedNpcStateSnapshotService = new CommandLinkedNpcStateSnapshotService();
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
        OwnedNpcTransformationInteractionService ownedNpcTransformations =
                new OwnedNpcTransformationInteractionService(releaseFlow, companionQueries);
        interactionExtensionRegistry.registerBuiltInEffect(
                "tamework:transform_owned_npc", ownedNpcTransformations::apply);
        // Without a ready companion index there is no profile API: trait effects see a null profile id.
        IndexNpcProfilesApi profilesApi = companionQueries == null ? null : new IndexNpcProfilesApi(companionQueries);
        traitEffectRegistry = new TraitEffectRegistry(getLogger(), profilesApi);
        deferEntitySystem(TameworkRuntimeModule.CAPTURE,
                "capture-channel-vfx", CaptureChannelVfxSystem::new);
        deferEntitySystem(TameworkRuntimeModule.CAPTURE,
                "capture-channel-session-cleanup", CaptureChannelSessionCleanupSystem::new);
        if (profilesApi != null) {
            CompanionPersistenceModule apiModule = companionModule;
            IndexTameworkApi indexApi = new IndexTameworkApi(
                    profilesApi,
                    new IndexProfileDataApi(apiModule.index(), apiModule.writer()::flushNow),
                    new IndexDiagnosticsApi(apiModule.index(), apiModule.writer()::status,
                            apiModule.root().toString(), apiModule::folderBytes, apiModule::unreadableCount),
                    new IndexPopulationGroupApi(apiModule.index(), populationGroupConfigRegistry::snapshot),
                    apiEventBus,
                    commandLinkedNpcStateSnapshotService,
                    interactionExtensionRegistry,
                    traitEffectRegistry,
                    new SimpleClaimsTamedDamagePolicy(simpleClaimsCapabilityRuntime),
                    new CommandUiRegistry(),
                    new CommandHudRegistry(),
                    itemFeatureRegistry,
                    capturePolicyRegistry,
                    admissionProviderRegistry,
                    managedActivityConfigRegistry);
            ActivityRuntime.install(indexApi.activityPublisher(), managedActivityConfigRegistry);
            indexApi.useBondedCompanions(bondedCompanionApi);
            api = indexApi;
            // Companion events go out after the index lock is released, on the changing thread (spec 9).
            apiModule.addAfterUnlockListener(new CompanionEventPublisher(
                    apiEventBus,
                    roleId -> populationGroupConfigRegistry.snapshot().resolvePoliciesForRole(roleId).stream()
                            .map(policy -> policy.groupId()).toList(),
                    System::currentTimeMillis));
        } else {
            // No public API without the companion index; activity producers and care credits
            // still need their runtime.
            ReplacementTameworkApiFactory.installStandaloneActivityRuntime(managedActivityConfigRegistry);
        }
        companionXpEventDebugLogService = new CompanionXpEventDebugLogService(
                () -> null,
                message -> getLogger().at(Level.INFO).log(message),
                companionProgressionSignalBus
        );
        apiSelfTestFixtureManager = new ApiSelfTestFixtureManager();
        apiSelfTestRunner = new ApiSelfTestRunner();
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-npc-relocation-on-load", () -> new CommandNpcRelocationOnLoadSystem(
                        commandNpcRelocationService,
                        commandLinkedNpcStateSnapshotService,
                        null
                )
        );
        // Initial assets load before deferred subscriptions; publish portraits even without spawners.
        reconcileNpcPortraitAssets();

        // Load item feature configs from bundled defaults and mod overrides.
        int loadedSpawner = loadSpawnerItemAssets();
        int loadedNaming = loadNameItemAssets();
        int loadedCommands = loadCommandItemAssets();

        // Load translation entries from mods so messages can be localized.
        translationRegistry = new TranslationRegistry();
        int langLoaded = ModLanguageDiscovery.loadAll(translationRegistry, getLogger(), getDataDirectory());
        getLogger().at(Level.INFO).log("Tamework language entries loaded: " + langLoaded);
        NameplateBuilderBridgeLoader.initialize(this);

        // Capture into an item and release from one (spec 8.2, 8.3). Without a ready companion
        // index it is not built, and capture and spawner interactions fail before changing anything.
        if (restoreFlow != null) {
            CompanionPersistenceModule module = companionModule;
            spawnerFeatureHandler = new SpawnerFeatureHandler(getLogger(), itemFeatureRegistry, translationRegistry,
                    capturePolicyRegistry, interactionExtensionRegistry, module.index(), module.loaded(),
                    new CaptureFlow<>(module.index(), module.loaded(),
                            (profileId, snapshot) -> module.writer().queueSnapshot(snapshot),
                            module.writer()::flushNow, providerAdmission,
                            // A capture into bonded storage re-checks the family's owned limit under the index lock.
                            com.alechilles.alecstamework.companion.bonded.BondedAdmission.withFamilyCaps(
                                    admissionGate::deny, module.index()::fileRecords, bondedFamilies),
                            // A leftover 4.x body is never captured into a second record (plan 7 R14).
                            companionLegacyBody),
                    restoreFlow, new HytaleCaptureDelivery(module.index(), CompanionSnapshots.production(),
                            module.writer()::queueSnapshot),
                    CompanionSnapshots.production(), new CompanionSummaries(new HytaleSummarySources()),
                    admissionGate, commandItemRegistry, apiEventBus::publishPersistenceEvent, bondedFamilies,
                    // A 2.x capture item gets its record and stored state on its first release (plan 7 R18).
                    new com.alechilles.alecstamework.companion.migrate.LegacyItemAdoption(module.index(),
                            module.legacyAliases(), module.writer()::queueSnapshot,
                            admissionGate::admit, System::currentTimeMillis));
        }
        // Core handler for naming flows.
        namingFeatureHandler = new NamingFeatureHandler(nameItemRegistry, translationRegistry);
        // Core handler for command-item linking and dispatch.
        commandItemFeatureHandler = new CommandItemFeatureHandler(
                commandItemRegistry,
                commandNpcRelocationService,
                commandLinkedNpcStateSnapshotService,
                null,
                restoreFlow,
                // The bonded panel reads the index-backed API; after shutdown it reports unavailable.
                bondedCompanionApi == null ? null : () -> {
                    com.alechilles.alecstamework.api.BondedCompanionApi current = bondedCompanionApi;
                    return current != null ? current : com.alechilles.alecstamework.api.BondedCompanionApi.unavailable();
                },
                companionProgressionSignalBus,
                companionQueries,
                releaseFlow,
                () -> companionRosterSummons,
                admissionGate
        );
        commandItemFeatureHandler.configureRecallRestore(recallRestore);
        commandItemFeatureHandler.configureCaptureItemFlows(captureItemFlows);
        if (captureItemFlows != null && spawnerFeatureHandler != null) {
            // After a Recall or Forget the item turns empty at once in its owner's inventory and
            // where the item locator last saw it (a container, a dropped item or another player).
            SpawnerFeatureHandler spawner = spawnerFeatureHandler;
            var itemTracker = commandItemFeatureHandler.capturedItemTracker();
            captureItemFlows.useItemSweep((owner, profileId, itemGeneration) -> {
                spawner.emptyHeldCaptureItems(owner, profileId);
                spawner.emptyLocatedCaptureItem(itemTracker, owner, profileId, itemGeneration);
            });
        }
        // The command pages use the registry the public API hands to renderers and contributors.
        IndexTameworkApi commandUiApi = api;
        commandItemFeatureHandler.configureCommandUi(
                commandUiApi != null ? commandUiApi.commandUi() : new CommandUiRegistry());
        // Capture item ownership follows the holder (spec 8.14); without a ready index only the locator runs.
        CaptureItemHolderSystems.Transfers captureItemTransfers = admissionGate == null ? null
                : createCaptureItemTransfers(admissionGate);
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS, "capture-item-player-locations", () -> {
            var tracker = commandItemFeatureHandler.capturedItemTracker();
            tracker.start(runtimeDataDirectory.resolve("cache/captured-item-locations.json"));
            return new CaptureItemHolderSystems.Lifecycle(tracker, captureItemTransfers);
        });
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS, "capture-item-inventory-locations",
                () -> new CaptureItemHolderSystems.Changes(
                        commandItemFeatureHandler.capturedItemTracker(), captureItemTransfers));
        CaptureItemFlows destroyedCaptureItems = captureItemFlows;
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS, "capture-item-dropped-locations",
                () -> new com.alechilles.alecstamework.items.locate.CapturedItemDropSystem(
                        commandItemFeatureHandler.capturedItemTracker(), destroyedCaptureItems));
        deferChunkSystem(TameworkRuntimeModule.COMMAND_ITEMS, "capture-item-container-locations",
                () -> new com.alechilles.alecstamework.items.locate.CapturedItemContainerSystem(
                        commandItemFeatureHandler.capturedItemTracker()));
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
        CommandTargetHudActivationTracker commandHotswapHudActivationTracker = new CommandTargetHudActivationTracker();
        CommandTargetHudActivationTracker commandHighlightActivationTracker =
                HytaleApiLevel.isUpdate6OrLater() ? new CommandTargetHudActivationTracker() : null;
        CommandHudDirtySink commandHudDirtySink = CommandHudDirtySink.fanOut(
                commandTargetHudActivationTracker,
                commandHotswapHudActivationTracker,
                commandHighlightActivationTracker
        );
        CommandTargetInspector commandTargetInspector = new CommandTargetInspector();
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud-active-slot",
                () -> new CommandTargetHudActiveSlotSystem(commandHudDirtySink));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud-inventory-change",
                () -> new CommandTargetHudInventoryChangeSystem(commandHudDirtySink));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-target-hud",
                () -> new CommandTargetHudService(
                        commandItemRegistry,
                        commandTargetHudActivationTracker,
                        commandTargetInspector
                ));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-hotswap-hud",
                () -> new CommandHotswapHudService(
                        commandItemRegistry,
                        commandHotswapHudActivationTracker,
                        commandTargetInspector
                ));
        if (commandHighlightActivationTracker != null) {
            deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                    "command-active-npc-highlight",
                    () -> new CommandActiveNpcHighlightSystem(
                            commandItemRegistry,
                            commandHighlightActivationTracker,
                            commandLinkedNpcStateSnapshotService.getLoadedNpcIdentityIndex()
                    ));
        }
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-hud-player-lifecycle",
                () -> new CommandHudPlayerLifecycleSystem(commandHudDirtySink));
        deferEntitySystem(TameworkRuntimeModule.COMMAND_ITEMS,
                "command-hud-store-lifecycle",
                () -> new CommandHudStoreLifecycleSystem(commandHudDirtySink));

        applyDebugConfigDefaults();
        settingsAnnouncementService = new TameworkSettingsAnnouncementService(this);
        registerCompanionMigrationPopup(companionModule == null ? null : companionModule.legacyKind());

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
                TameworkRuntimeModule.LEVELING,
                "combat-contribution-world-cleanup",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        RemoveWorldEvent.class,
                        this::onWorldRemovedForCombatContributions,
                        "combat contribution world cleanup"
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
        if (com.alechilles.alecstamework.compat.runes.RuneInputRuntime.isSupported()) {
            deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                    "rune-input-load", com.alechilles.alecstamework.compat.runes.RuneInputRuntime.Load::new);
            deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                    "rune-input-tick", com.alechilles.alecstamework.compat.runes.RuneInputRuntime.Tick::new);
            deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                    "rune-input-active-slot", com.alechilles.alecstamework.compat.runes.RuneInputRuntime.ActiveSlot::new);
            deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                    "rune-input-hotbar-change", com.alechilles.alecstamework.compat.runes.RuneInputRuntime.HotbarChange::new);
            deferEntitySystem(TameworkRuntimeModule.CORE_OWNERSHIP,
                    "rune-input-flight-change", com.alechilles.alecstamework.compat.runes.RuneInputRuntime.FlightChange::new);
        }
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
        deferEntitySystem(TameworkRuntimeModule.LEVELING,
                "companion-combat-defeat", () ->
                        new CompanionCombatDefeatSystem(
                                UUIDComponent.getComponentType()));
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
            if (diagnosticRuntime != null) {
                diagnosticRuntime.recordStartCompleted();
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
            if (diagnosticRuntime != null) {
                diagnosticRuntime.captureStartFailure(throwable);
            }
            throw throwable;
        }
    }

    private void startInternal() {
        prepareRuntimeActivation();
        if (runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            diagnosticRuntime = TameworkDiagnosticRuntime.create(this);
            if (diagnosticRuntime != null) {
                diagnosticRuntime.start();
            }
        }
        TameworkActiveAssetInitializer.initialize(
                runtimeStartupPlan,
                () -> {
                    populationGroupAssetRegistrar.initialize();
                    managedActivityAssetRegistrar.initialize();
                },
                this::rebuildCapturePolicyIndex, this::rebuildBondedCompanionRosterIndex);
        preflightDeclaredRuntimeParticipants();
        initializeRuntimeServices();
        registerRuntimeParticipants();
        admitCompanionsAlreadyLoaded();
        startLegacyBodyLocate();
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
        if (assetEditorPackService != null
                && runtimeStartupPlan.isActive(TameworkRuntimeModule.CORE_OWNERSHIP)) {
            assetEditorPackService.ensurePackVisible();
        }
    }

    private void registerCommandRoot() {
        if (getCommandRegistry() != null) {
            CompanionQueries companions = companionModule != null && companionModule.ready()
                    ? companionModule.queries() : null;
            var root = TameworkCommandRootFactory.create(
                    spawnBeaconVisualizationService,
                    diagnosticRuntime == null
                            ? null : diagnosticRuntime.failureSink(),
                    companionReleaseFlow,
                    companions
            );
            root.addCompanionCommands(captureItemFlows, companions);
            root.addPersistenceCommands(companionStartFresh);
            root.addBondedCommands(() -> bondedCompanionApi, () -> bondedCompanionRosterRegistry == null
                    ? null : bondedCompanionRosterRegistry.snapshot());
            getCommandRegistry().registerCommand(root);
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

    /**
     * Opens the companion store before the world systems register. {@code open} blocks on file
     * I/O; this runs in plugin start, not on a world thread. The universe's StorageManager is a
     * final field set when the core Universe plugin is constructed, so it is usable here.
     */
    private void openCompanionPersistence(@Nonnull List<Path> legacyDirectories) {
        Universe universe = Universe.get();
        if (universe == null) {
            getLogger().at(Level.SEVERE).log(
                    "Universe is unavailable at Tamework start; companion persistence is disabled.");
            return;
        }
        Path companionRoot = CompanionStorage.root(universe.getPath());
        HytaleCompanionFileIo companionIo = new HytaleCompanionFileIo(() -> Universe.get().getStorageManager());
        String version = String.valueOf(getManifest().getVersion());
        companionModule = CompanionPersistenceModule.open(
                companionRoot,
                legacyDirectories,
                Files::exists,
                companionIo,
                System::currentTimeMillis,
                version,
                runtimeDataDirectory);
        // Command items resolve links that name an old body through the import's alias file (plan 7 R19).
        com.alechilles.alecstamework.companion.migrate.LegacyItemAdoption.install(companionModule.legacyAliases());
        // Only a world blocked by old saves may start fresh (spec 12.3).
        if (companionModule.state() == CompanionPersistenceModule.State.MIGRATION_REQUIRED) {
            companionStartFresh = () -> CompanionPersistenceModule.startFresh(companionRoot, legacyDirectories,
                    Files::exists, companionIo, System::currentTimeMillis, version);
        }
    }

    /**
     * Runs the one-shot companion startup pass after the companion systems registered. Worlds
     * already loading when Tamework starts keep their bodies; the add systems never see those.
     */
    private void admitCompanionsAlreadyLoaded() {
        CompanionStartupAdmission admission = companionStartupAdmission;
        companionStartupAdmission = null;
        Universe universe = Universe.get();
        if (admission == null || universe == null
                || !runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
            return;
        }
        admission.admitLoadedWorlds(universe.getWorlds().values());
        // Queued after the admission pass, which reads the retired projection identity (plan 7 R15).
        com.alechilles.alecstamework.companion.migrate.RetiredComponentCleanup cleanup = retiredComponentCleanup;
        if (cleanup != null) {
            cleanup.stripLoadedWorlds(universe.getWorlds().values());
        }
    }

    /**
     * Builds the restore used by recall, world-change follow and the panel's Recover and Revive.
     * The snapshot is a fresh capture when the body is loaded, otherwise the stored one.
     */
    private static RestoreFlow<Ref<EntityStore>> createRestoreFlow(
            CompanionPersistenceModule module, ProviderAdmission providerAdmission,
            CompanionAdmissionGate admissionGate,
            com.alechilles.alecstamework.companion.bonded.BondedRecords.Families bondedFamilies) {
        CompanionSnapshotSource snapshots = new CompanionSnapshotSource(
                module.queries()::loadedBody, module.index()::get, module.writer()::queueSnapshot,
                module::readSnapshot, CompanionSnapshots.production());
        HytaleCompanionSpawner spawner =
                new HytaleCompanionSpawner(TameworkCompanionComponent.getComponentType(), module.index()::get,
                        module.writer()::queueSnapshot, module.writer()::queueSnapshotDelete);
        // The flow unregistered the old body at commit, so its removal raises no LOST transition.
        // A ref no longer valid means the body left its store; if its chunk loads it again, the
        // generation fence removes it.
        return new RestoreFlow<>(module.index(), module.loaded(), snapshots::read,
                module.writer()::flushNow, spawner, (profileId, body) -> CompanionBodies.removeOnOwnWorld(body),
                System::currentTimeMillis, providerAdmission,
                // A bonded family's owned and active limits join the caps under the index lock (plan 6 R15).
                com.alechilles.alecstamework.companion.bonded.BondedAdmission.withFamilyCaps(
                        admissionGate::deny, module.index()::fileRecords, bondedFamilies));
    }

    /**
     * Builds the bonded companion API over the companion index (plan 6 task 9). Its change events
     * go out after the index lock is released. {@link #closeApiComposition} closes it.
     *
     * <p>Also starts the summon effects (plan 6 task 11): the summon aura plays once a restore
     * has brought the body back, and the expiry warning runs on the companion module's timer
     * thread, which the module stops at shutdown.
     */
    private com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi createBondedCompanionApi(
            CompanionPersistenceModule module, RestoreFlow<Ref<EntityStore>> restoreFlow,
            StoreFlow<Ref<EntityStore>> storeFlow,
            com.alechilles.alecstamework.companion.bonded.BondedRecords.Families bondedFamilies) {
        com.alechilles.alecstamework.companion.bonded.runtime.HytaleBondedBodies bondedBodies =
                new com.alechilles.alecstamework.companion.bonded.runtime.HytaleBondedBodies(module.loaded()::get);
        com.alechilles.alecstamework.companion.bonded.BondedSummonEffects effects =
                new com.alechilles.alecstamework.companion.bonded.BondedSummonEffects(
                        module.index()::get, bondedFamilies, bondedBodies,
                        (task, delayMs) -> {
                            java.util.concurrent.ScheduledFuture<?> scheduled = module.timers().schedule(
                                    task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                            return () -> scheduled.cancel(false);
                        },
                        System::currentTimeMillis);
        bondedSummonEffects = effects;
        module.addAfterUnlockListener(effects::onChanged);
        effects.rebuild(module.index());
        java.util.function.Function<RestoreFlow.Request,
                java.util.concurrent.CompletableFuture<RestoreFlow.Result>> restore =
                request -> restoreFlow.restore(request).whenComplete((result, failure) -> {
                    if (result == RestoreFlow.Result.RESTORED) {
                        effects.summoned(request.profileId());
                    }
                });
        com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi bonded =
                new com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi(
                        module.index(), bondedFamilies, restore, storeFlow::store,
                        module.writer()::flushNow,
                        com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi.bodies(
                                module.loaded(), CompanionBodies::removeOnOwnWorld),
                        module.writer()::queueSnapshotDelete,
                        com.alechilles.alecstamework.companion.bonded.BondedTalentTimers.fromSnapshots(
                                module::readSnapshot),
                        System::currentTimeMillis);
        module.addAfterUnlockListener(bonded::onChanged);
        bonded.useTalents(new com.alechilles.alecstamework.companion.bonded.BondedTalentUpdates(
                module.index(), module::readSnapshot, module.writer()::queueSnapshot, bondedBodies));
        return bonded;
    }

    /**
     * Builds the store used by the panel's dismiss, summon expiry and owner logout or death. A
     * loaded body is captured on its world thread; otherwise the stored snapshot stands.
     */
    private static StoreFlow<Ref<EntityStore>> createStoreFlow(CompanionPersistenceModule module) {
        return new StoreFlow<>(module.index(), module.loaded(),
                new HytaleStoreCapture(CompanionSnapshots.production(),
                        new CompanionSummaries(new HytaleSummarySources())),
                module::readSnapshot, (profileId, snapshot) -> module.writer().queueSnapshot(snapshot),
                module.writer()::flushNow, (profileId, body) -> CompanionBodies.removeOnOwnWorld(body),
                System::currentTimeMillis);
    }

    /**
     * The admission provider decisions the synchronous sites read (plan 6 R11). An owner's are
     * dropped when the owner's counts change (index listener, after the lock) and on disconnect,
     * all on config reload and on shutdown; they are warmed when a player enters a world. The
     * listeners only pass the player id and the world name on; the provider is asked on the
     * provider registry's threads.
     */
    private ProviderDecisionCache startProviderDecisionCache(ProviderAdmission providerAdmission) {
        ProviderDecisionCache cache = new ProviderDecisionCache(providerAdmission, System::currentTimeMillis);
        providerDecisionCache = cache;
        companionModule.addAfterUnlockListener(cache::onRecordChanged);
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "admission-provider-decision-warm",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        AddPlayerToWorldEvent.class,
                        event -> {
                            if (event == null || event.getWorld() == null || event.getHolder() == null) {
                                return;
                            }
                            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
                            String world = event.getWorld().getName();
                            if (player != null && player.getUuid() != null && world != null && !world.isBlank()) {
                                cache.warm(player.getUuid(), world);
                            }
                        },
                        "admission provider decision warm-up"
                )
        );
        deferGlobalListener(
                TameworkRuntimeModule.CORE_OWNERSHIP,
                "admission-provider-decision-disconnect",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerDisconnectEvent.class,
                        event -> {
                            PlayerRef player = event == null ? null : event.getPlayerRef();
                            if (player != null && player.getUuid() != null) {
                                cache.forgetOwner(player.getUuid());
                            }
                        },
                        "admission provider decision cleanup"
                )
        );
        return cache;
    }

    /**
     * Capture item ownership follows the holder, and ineligible players cannot pick the item up
     * (spec 8.14). The pickup filters read cached admission decisions: a player's are dropped when
     * their counts change (index listener) and all on config reload.
     */
    private CaptureItemHolderSystems.Transfers createCaptureItemTransfers(CompanionAdmissionGate admissionGate) {
        AdmissionCache admissionCache = new AdmissionCache(System::currentTimeMillis);
        companionModule.addChangeListener(admissionCache::onRecordChanged);
        captureAdmissionCache = admissionCache;
        CaptureItemHolderSystems.Transfers transfers = new CaptureItemHolderSystems.Transfers(
                companionModule.index(), companionModule.writer(), admissionGate, itemFeatureRegistry, admissionCache,
                // The handler exists whenever the admission gate does; a failed stamp keeps the owner id.
                (stack, owner, ownerName) -> spawnerFeatureHandler.withCaptureOwner(stack, owner, ownerName));
        captureItemTransfers = transfers;
        return transfers;
    }

    /**
     * Builds roster summon and store and starts timed-summon expiry on the module's timer thread,
     * which the module stops before its final flush. Timed summons are also stored when their
     * owner logs out or dies (spec 8.4). The expiry listener is added and filled in one index
     * step, so no change is missed between them. Returns null when generic persistence is not
     * active: a timed summon would never expire, so there are no summons.
     */
    @Nullable
    private RosterSummons startRosterSummons(
            CompanionPersistenceModule module, RestoreFlow<Ref<EntityStore>> restoreFlow,
            StoreFlow<Ref<EntityStore>> storeFlow,
            com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi bonded) {
        if (!runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
            return null;
        }
        RosterSummons summons = new RosterSummons(module.index()::get, module.queries()::owned,
                restoreFlow::restoreOutcome, storeFlow::store, module.writer()::flushNow, RosterSummons.Policy::forRole,
                System::currentTimeMillis, bonded::storeActive);
        com.alechilles.alecstamework.companion.bonded.BondedSummonEffects summonEffects = bondedSummonEffects;
        SummonExpiryScheduler expiry = new SummonExpiryScheduler(profileId -> {
            // A rider's fall protection is armed while the body is still there. Its world task is
            // queued before the store's, so it runs first on the body's world thread.
            if (summonEffects != null) {
                summonEffects.expiring(profileId);
            }
            return summons.storeExpired(profileId);
        });
        module.index().atomically(() -> {
            module.addChangeListener(expiry::onChange);
            expiry.rebuild(module.index());
            return null;
        });
        expiry.start(module.timers(), System::currentTimeMillis);
        deferGlobalListener(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "companion-timed-summon-logout",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        PlayerDisconnectEvent.class,
                        event -> {
                            PlayerRef player = event == null ? null : event.getPlayerRef();
                            if (player != null && player.getUuid() != null) {
                                summons.storeTimedSummons(player.getUuid(), true);
                            }
                        },
                        "companion timed summon logout auto-store"
                )
        );
        // An active bonded companion does not follow its owner to another world (plan 6 R18). Only
        // the owner id and the world name leave the event; the store hops to the body's world itself.
        deferGlobalListener(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "bonded-companion-owner-world-change",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        AddPlayerToWorldEvent.class,
                        event -> {
                            if (event == null || event.getWorld() == null || event.getHolder() == null) {
                                return;
                            }
                            PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
                            if (player != null && player.getUuid() != null) {
                                summons.storeBondedOutside(player.getUuid(), event.getWorld().getName());
                            }
                        },
                        "bonded companion owner world-change auto-store"
                )
        );
        deferEntitySystem(TameworkRuntimeModule.GENERIC_PERSISTENCE, "companion-timed-summon-owner-death",
                () -> new CompanionOwnerDeathSystem(owner -> summons.storeTimedSummons(owner, false)));
        return summons;
    }

    /**
     * Starts the background search for imported companions in the worlds' saved chunks (plan 7
     * task 13) when the companion module has one: only on a world imported from 3.x/4.x that still
     * has companions nobody has seen since. It reads the worlds that are loaded once the universe
     * is ready, and any world started while it runs. Worlds that are deleted when removed
     * (instances) are not read. It is stopped before the worlds shut down, so no storage read of
     * its own is running when a world's storage closes.
     */
    private void startLegacyBodyLocate() {
        CompanionPersistenceModule module = companionModule;
        Universe universe = Universe.get();
        com.alechilles.alecstamework.companion.migrate.LegacyBodyLocator locator =
                module == null || !module.ready() ? null : module.legacyLocator();
        if (locator == null || universe == null
                || !runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
            return;
        }
        locator.begin();
        java.util.function.Consumer<World> offer = world -> {
            if (world != null && world.getWorldConfig() != null && !world.getWorldConfig().isDeleteOnRemove()) {
                locator.offer(new com.alechilles.alecstamework.companion.migrate.SavedChunks(world));
            }
        };
        TameworkEventRegistrationSupport.registerGlobal(this,
                com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent.class,
                (com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent event) ->
                        offer.accept(event.getWorld()),
                "companion locate world start");
        TameworkEventRegistrationSupport.registerGlobal(this, Short.MAX_VALUE, RemoveWorldEvent.class,
                (RemoveWorldEvent event) -> {
                    if (event.getWorld() != null && (!event.isCancelled()
                            || event.getRemovalReason() == RemoveWorldEvent.RemovalReason.EXCEPTIONAL)) {
                        locator.worldRemoved(event.getWorld().getName());
                    }
                }, "companion locate world removal");
        // Between UNBIND_LISTENERS (-40) and SHUTDOWN_WORLDS (-32).
        getEventRegistry().register((short) -36, ShutdownEvent.class, event -> locator.stop());
        java.util.concurrent.CompletableFuture<Void> ready = universe.getUniverseReady();
        Runnable begin = () -> {
            universe.getWorlds().values().forEach(offer);
            locator.start();
        };
        if (ready == null) {
            begin.run();
        } else {
            // Also after a failed world load: the worlds that did load are still read.
            ready.whenComplete((ignored, failure) -> begin.run());
        }
    }

    /** Registers the companion index systems, world-removal listener and final flush. */
    private void registerCompanionPersistenceRuntime(
            CompanionAdmissionGate admissionGate, RestoreFlow<Ref<EntityStore>> restoreFlow,
            com.alechilles.alecstamework.companion.bonded.BondedRecords.Families bondedFamilies) {
        CompanionPersistenceModule module = companionModule;
        CompanionBodyLifecycle lifecycle = new CompanionBodyLifecycle(
                module.index(), module.writer(), module.loaded(),
                TameworkCompanionComponent.getComponentType(),
                CompanionSnapshots.production(),
                new CompanionSummaries(new HytaleSummarySources()),
                module.warnings(), System::currentTimeMillis, admissionGate::admit, bondedFamilies,
                module.legacyAliases(), module.unreadable());
        companionLegacyBody = lifecycle::isLegacyBody;
        CompanionBodySystem bodySystem = new CompanionBodySystem(TameworkCompanionComponent.getComponentType(),
                ownerComponentType, tamedComponentType, module.index(), module.unreadable(), module.loaded(),
                lifecycle);
        TameworkCompanionRuntimeParticipants.addCompanionIndex(this, runtimeParticipants, bodySystem, lifecycle);
        com.alechilles.alecstamework.companion.coop.HytaleCoopIntake coopIntake =
                new com.alechilles.alecstamework.companion.coop.HytaleCoopIntake(module.index(), module.loaded(),
                        (profileId, snapshot) -> module.writer().queueSnapshot(snapshot), module.writer()::flushNow,
                        CompanionSnapshots.production(), new CompanionSummaries(new HytaleSummarySources()),
                        lifecycle::isLegacyBody);
        // Same gate as the coop systems below: without them no intake may commit coop moves.
        if (runtimeStartupPlan.isActive(TameworkRuntimeModule.GENERIC_PERSISTENCE)) {
            com.alechilles.alecstamework.companion.coop.HytaleCoopIntake.install(coopIntake);
        }
        com.alechilles.alecstamework.companion.coop.HytaleCoopResidents coopResidents =
                new com.alechilles.alecstamework.companion.coop.HytaleCoopResidents(module.index(), restoreFlow,
                        new HytaleCompanionSpawner(TameworkCompanionComponent.getComponentType(), module.index()::get,
                                module.writer()::queueSnapshot, module.writer()::queueSnapshotDelete));
        deferChunkSystem(TameworkRuntimeModule.GENERIC_PERSISTENCE, "coopschedulesystem",
                () -> new com.alechilles.alecstamework.companion.coop.CoopScheduleSystem(coopIntake, coopResidents,
                        coopCaptureReceiptsComponentType));
        deferChunkSystem(TameworkRuntimeModule.GENERIC_PERSISTENCE, "coopbreaksystem",
                () -> new com.alechilles.alecstamework.companion.coop.CoopBreakSystem(coopResidents,
                        com.alechilles.alecstamework.companion.coop.TameworkCoopSlotsComponent.getComponentType()));
        companionStartupAdmission = new CompanionStartupAdmission(bodySystem, lifecycle, module.loaded(),
                TameworkCompanionComponent.getComponentType(), NPCEntity.getComponentType(),
                ownerComponentType, tamedComponentType,
                lifecycle.hasLegacyBodies() ? projectionIdentityComponentType : null);
        // Retired 3.x/4.x components (plan 7 R15, R16). Declared after the companion index systems:
        // the strip system depends on CompanionOwnershipSystems.OnAdd. The projection identity
        // goes once its body is stamped; only the old-body match above still reads it.
        com.alechilles.alecstamework.companion.migrate.RetiredComponentCleanup retiredCleanup =
                new com.alechilles.alecstamework.companion.migrate.RetiredComponentCleanup(
                        retiredEntityComponentTypes, projectionIdentityComponentType,
                        TameworkCompanionComponent.getComponentType(), lifecycle.hasLegacyBodies());
        retiredComponentCleanup = retiredCleanup;
        deferEntitySystem(TameworkRuntimeModule.GENERIC_PERSISTENCE, "retired-component-cleanup",
                retiredCleanup::addSystem);
        deferEntitySystem(TameworkRuntimeModule.GENERIC_PERSISTENCE, "retired-revive-escrow-refund",
                () -> new com.alechilles.alecstamework.companion.migrate.EscrowRefund(
                        bondedReviveEscrowComponentType).system());
        CompanionWorldRemovalListener worldRemoval =
                new CompanionWorldRemovalListener(lifecycle, module.index(), module.loaded());
        deferGlobalListener(
                TameworkRuntimeModule.GENERIC_PERSISTENCE,
                "companion-world-removal",
                () -> TameworkEventRegistrationSupport.registerGlobal(
                        this,
                        Short.MAX_VALUE,
                        RemoveWorldEvent.class,
                        worldRemoval::onRemoveWorld,
                        "companion world removal"
                )
        );
        // After worlds shut down (-32) and before universe resources flush (-24). Nothing may
        // change the index after this flush; the writer drops later changes.
        // Coop intake stops first so no intake commits after the flush.
        getEventRegistry().register((short) -28, ShutdownEvent.class, event -> {
            com.alechilles.alecstamework.companion.coop.HytaleCoopIntake.uninstall();
            module.shutdown(System.currentTimeMillis() + 10_000L);
        });
    }

    /**
     * Tells admins on connect that companion saving is paused because the store could not be
     * read. The module logged the cause.
     */
    private void registerCompanionPersistenceFailedNotice() {
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerConnectEvent.class,
                event -> {
                    PlayerRef player = event == null ? null : event.getPlayerRef();
                    PermissionsModule permissions = PermissionsModule.get();
                    if (player == null || player.getUuid() == null || permissions == null
                            || !permissions.getGroupsForUser(player.getUuid())
                            .contains(HytalePermissionsProvider.GROUP_ADMIN)) {
                        return;
                    }
                    player.sendMessage(Message.translation("server.tamework.companions.persistence.failed"));
                },
                "companion persistence operator notice"
        );
    }

    /**
     * Tells admins, operators and the local singleplayer owner that the world needs converting,
     * on every login while old saves block the store: a chat message on connect and a popup when
     * the player is ready. Registered directly, not through a runtime module, so it does not
     * depend on which Tamework features are active. The disconnect listener lets the popup
     * return on the next login.
     */
    private void registerCompanionMigrationPopup(@Nullable CompanionStorage.LegacyKind legacyKind) {
        TameworkSettingsAnnouncementService service = settingsAnnouncementService;
        if (legacyKind == null || service == null) {
            return;
        }
        // 2.x: the Tamework version that converts the data. 3.x/4.x: the report of the failed import.
        String reportName = companionModule == null ? null : companionModule.importReportName();
        String noticeValue = reportName != null ? reportName : legacyKind.converterVersion();
        service.requireMigrationNotice(legacyKind, noticeValue);
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerConnectEvent.class,
                event -> {
                    PlayerRef player = event == null ? null : event.getPlayerRef();
                    if (service.migrationNoticeFor(player) == null) {
                        return;
                    }
                    player.sendMessage(Message.translation("server."
                                    + TameworkSettingsAnnouncementService.migrationNoticeKey(legacyKind))
                            .param("0", noticeValue));
                },
                "companion migration chat notice"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerReadyEvent.class,
                service::onPlayerReadyMigrationNotice,
                "companion migration popup"
        );
        TameworkEventRegistrationSupport.registerGlobal(
                this,
                PlayerDisconnectEvent.class,
                service::onPlayerDisconnect,
                "companion migration popup session reset"
        );
    }

    /** Validates setup-declared factories before persistence can mutate state. */
    private void preflightDeclaredRuntimeParticipants() {
        runtimeParticipants.preflight(runtimeStartupPlan);
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
        runtimeHandle = runtimeParticipants.register(
                runtimeStartupPlan,
                target,
                participant -> TameworkRuntimeRegistrationTelemetry.record(
                        runtimeStartupDiagnostics, participant
                ),
                TameworkRuntimeRegistrationContext.Participant.prepared(
                        TameworkRuntimeModule.HSTATS,
                        "hstats-worker",
                        TameworkRuntimeRegistrationContext.RegistrationKind.WORKER,
                        () -> new TameworkHStatsIntegration(this),
                        (ignoredTarget, prepared) -> {
                            hStatsIntegration = (TameworkHStatsIntegration) prepared;
                            hStatsIntegration.initialize();
                            return hStatsIntegration;
                        }
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
        com.alechilles.alecstamework.npc.progression.CompanionLifeStageService.shutdown();
        HusbandryHarvestUseContext.clearPendingUses();
        TameworkNpcCullService.clearPendingItemUses();
        TameworkShutdownSequence.run(
                this::closeRuntimeParticipants,
                this::closeRuntimeApiDependents,
                this::closeApiComposition);
        simpleClaimsCapabilityRuntime.close();
        if (commandNpcRelocationService != null) {
            commandNpcRelocationService.close();
            commandNpcRelocationService = null;
        }
        com.alechilles.alecstamework.companion.coop.HytaleCoopIntake.uninstall();
        com.alechilles.alecstamework.companion.migrate.LegacyItemAdoption.install(
                com.alechilles.alecstamework.companion.migrate.LegacyAliases.EMPTY);
        if (companionModule != null) {
            // Returns the -28 ShutdownEvent flush result when that already ran.
            if (!companionModule.shutdown(System.currentTimeMillis() + 2_000L)) {
                getLogger().at(Level.SEVERE).log(
                        "Companion saves were not fully written before shutdown.");
            }
            companionModule = null;
        }
        OwnerPopulationCapService.useAdmissionGate(null);
        companionReleaseFlow = null;
        captureItemFlows = null;
        companionRosterSummons = null;
        companionStartupAdmission = null;
        if (diagnosticRuntime != null) {
            diagnosticRuntime.close();
            diagnosticRuntime = null;
        }
        if (companionXpLegacyAdapter != null) {
            try {
                companionXpLegacyAdapter.close();
            } catch (Exception exception) {
                getLogger().at(Level.WARNING).log("Failed to close companion XP legacy adapter.", exception);
            }
            companionXpLegacyAdapter = null;
        }
        if (apiEventBus != null) {
            apiEventBus.close();
            apiEventBus = null;
        }
        if (companionProgressionSignalBus != null) {
            companionProgressionSignalBus.close();
            companionProgressionSignalBus = null;
        }
        ownerPopulationLiveIndex.clear();
        LinkedNpcPanelPortraitItemIndex.clear();
        AvatarFlightStaleOwnerRecoveryRegistry.clear();
        com.alechilles.alecstamework.npc.progression.AnimalProgressionClock.get().close();
        runtimeDataDirectory = null;
        apiSelfTestFixtureManager = null;
        apiSelfTestRunner = null;
        settingsAnnouncementService = null;
        runtimeActivationState = null;
        getLogger().at(Level.INFO).log("Alec's Tamework! has been disabled!");
    }

    private void closeRuntimeParticipants() {
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
    }

    private void closeRuntimeApiDependents() {
        CompanionCombatDefeatSystem.clearAll();
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
        if (companionXpEventDebugLogService != null) {
            companionXpEventDebugLogService.close();
            companionXpEventDebugLogService = null;
        }
        overrideInitializedScopeKeys.clear();
        if (commandItemFeatureHandler != null) {
            commandItemFeatureHandler.close();
            commandItemFeatureHandler = null;
        }
    }

    private void closeApiComposition() {
        ActivityRuntime.clear();
        IndexTameworkApi closing = api;
        api = null;
        if (closing != null) {
            closing.close();
        }
        com.alechilles.alecstamework.companion.bonded.IndexBondedCompanionApi closingBonded = bondedCompanionApi;
        bondedCompanionApi = null;
        if (closingBonded != null) {
            closingBonded.close();
        }
        com.alechilles.alecstamework.companion.bonded.BondedSummonEffects closingEffects = bondedSummonEffects;
        bondedSummonEffects = null;
        if (closingEffects != null) {
            closingEffects.close();
        }
        ProviderDecisionCache closingDecisions = providerDecisionCache;
        providerDecisionCache = null;
        if (closingDecisions != null) {
            closingDecisions.close();
        }
        AdmissionProviderRegistry closingProviders = admissionProviderRegistry;
        admissionProviderRegistry = null;
        if (closingProviders != null) {
            closingProviders.close();
        }
    }

    private void onWorldRemovedForCrashTelemetry(@Nonnull RemoveWorldEvent event) {
        if (diagnosticRuntime != null) {
            diagnosticRuntime.captureExceptionalWorldRemoval(event);
        }
    }

    private void onWorldRemovedForProgressionTiming(@Nonnull RemoveWorldEvent event) {
        if (event != null) {
            TameworkProgressionTimeScales.clearWorldScale(event.getWorld());
        }
    }

    private void onWorldRemovedForCombatContributions(
            @Nonnull RemoveWorldEvent event
    ) {
        if (event != null) {
            CompanionCombatDefeatSystem.clearWorld(event.getWorld());
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

    /** Returns the active provider-neutral managed-activity content resolver. */
    @Nonnull
    public ManagedActivityConfigRegistry getManagedActivityConfigRegistry() {
        return managedActivityConfigRegistry;
    }

    /** Returns the internal profile publisher used by admitted admin spawns. */
    @Nullable
    public CommandLinkedNpcStateSnapshotService
    getCommandLinkedNpcStateSnapshotService() {
        return commandLinkedNpcStateSnapshotService;
    }

    /** Refreshes runtime-backed API settings without exposing its implementation. */
    public void onRuntimeSettingsChanged() {
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        IndexTameworkApi currentApi = api;
        if (currentApi != null) {
            currentApi.onRuntimeSettingsChanged();
        }
        AdmissionCache admissionCache = captureAdmissionCache;
        if (admissionCache != null) {
            // Limits, limit scope and the capture item ownership mode decide the cached pickup admissions.
            admissionCache.clear();
        }
        ProviderDecisionCache providerDecisions = providerDecisionCache;
        if (providerDecisions != null) {
            providerDecisions.clear();
        }
        refreshCapturePickupFilters();
    }

    /** Pickup blocking may have turned on: players already online get their filters now. */
    private void refreshCapturePickupFilters() {
        CaptureItemHolderSystems.Transfers transfers = captureItemTransfers;
        if (transfers != null) {
            transfers.refreshFilters();
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
        return diagnosticRuntime == null ? null : diagnosticRuntime.telemetry();
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

    private void registerDynamicIconAssets() {
        if (dynamicIconAssetsRegistered) return;
        getAssetRegistry().register(
                HytaleAssetStore.builder(TwDynamicIconConfig.class, new DefaultAssetMap<>())
                        .setPath("Tamework/DynamicIcons")
                        .setCodec(TwDynamicIconConfig.CODEC)
                        .setKeyFunction(TwDynamicIconConfig::getId)
                        .build()
        );
        // Shared presentation assets must remain available without active spawner items.
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "dynamic-icon-assets-loaded",
                () -> getEventRegistry().register(LoadedAssetsEvent.class,
                        TwDynamicIconConfig.class, this::onDynamicIconAssetsLoaded));
        deferAssetSubscription(TameworkRuntimeModule.CORE_OWNERSHIP, "dynamic-icon-assets-removed",
                () -> getEventRegistry().register(RemovedAssetsEvent.class,
                        TwDynamicIconConfig.class, this::onDynamicIconAssetsRemoved));
        dynamicIconAssetsRegistered = true;
    }

    private void onDynamicIconAssetsLoaded(
            LoadedAssetsEvent<String, TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> event) {
        TwDynamicIconConfig.clearRoleCache();
        reconcileNpcPortraitAssets();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ICONS, event.getLoadedAssets().keySet());
        }
    }

    private void onDynamicIconAssetsRemoved(
            RemovedAssetsEvent<String, TwDynamicIconConfig, DefaultAssetMap<String, TwDynamicIconConfig>> event) {
        TwDynamicIconConfig.clearRoleCache();
        reconcileNpcPortraitAssets();
        emitExperimentalConfigReload(TameworkConfigFamily.DYNAMIC_ICONS, event.getRemovedAssets());
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
        reconcileNpcPortraitAssets();
    }

    private void onItemAssetsRemoved(
            RemovedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        LinkedNpcPanelPortraitItemIndex.refresh();
    }

    private void reconcileNpcPortraitAssets() {
        try {
            int registered = commandNpcPortraitAssets.reconcile();
            if (registered > 0) {
                getLogger().at(Level.INFO).log("Registered " + registered
                        + " companion portrait icons from dynamic icon assets.");
            }
        } catch (RuntimeException failure) {
            getLogger().at(Level.WARNING).withCause(failure).log(
                    "Could not register companion portrait icons; affected images will remain hidden.");
        } finally {
            LinkedNpcPanelPortraitItemIndex.refresh();
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

    private void publishPopulationConfigReload(
            @Nonnull TameworkConfigFamily family,
            @Nonnull Iterable<String> changedIds
    ) {
        emitExperimentalConfigReload(family, changedIds);
        if (family == TameworkConfigFamily.POPULATION_GROUP
                && managedActivityAssetRegistrar != null) {
            managedActivityAssetRegistrar.onPopulationGroupsChanged();
        }
    }

    private void emitExperimentalConfigReload(@Nonnull TameworkConfigFamily family, @Nullable Iterable<String> changedIds) {
        AdmissionCache admissionCache = captureAdmissionCache;
        if (admissionCache != null) {
            // Every config reload, so a changed limit or population group applies to pickup filters at once.
            admissionCache.clear();
        }
        ProviderDecisionCache providerDecisions = providerDecisionCache;
        if (providerDecisions != null) {
            // A provider decided against the config that was loaded; a reload asks again.
            providerDecisions.clear();
        }
        refreshCapturePickupFilters();
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
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.LEVELING, event.getLoadedAssets().keySet());
        }
    }

    private void onLevelingAssetsRemoved(
            RemovedAssetsEvent<String, TwLevelingConfig, DefaultAssetMap<String, TwLevelingConfig>> event) {
        TwLevelingConfig.clearRoleCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        emitExperimentalConfigReload(TameworkConfigFamily.LEVELING, event.getRemovedAssets());
    }

    private void onTraitAssetsLoaded(
            LoadedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.TRAIT, event.getLoadedAssets().keySet());
        }
    }

    private void onTraitAssetsRemoved(
            RemovedAssetsEvent<String, TwTraitConfig, DefaultAssetMap<String, TwTraitConfig>> event) {
        TwTraitConfig.clearRoleCache();
        CompanionLifeStageService.invalidateAdultRoleAppearanceCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        emitExperimentalConfigReload(TameworkConfigFamily.TRAIT, event.getRemovedAssets());
    }

    private void onTalentAssetsLoaded(
            LoadedAssetsEvent<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> event) {
        TwTalentConfig.clearRoleCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
        if (!event.isInitial()) {
            emitExperimentalConfigReload(TameworkConfigFamily.TALENT, event.getLoadedAssets().keySet());
        }
    }

    private void onTalentAssetsRemoved(
            RemovedAssetsEvent<String, TwTalentConfig, DefaultAssetMap<String, TwTalentConfig>> event) {
        TwTalentConfig.clearRoleCache();
        CompanionMovementSpeedSyncSystem.invalidateConfigRevision();
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
            for (String error : result.errors()) {
                getLogger().at(Level.WARNING).log(
                        "Spawner config reload rejected at active revision "
                                + result.activeRevision() + "; retaining last-valid registry: " + error);
            }
            return 0;
        }
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

    /** Companion index reads, or null while companion saving is paused (the module is not ready). */
    @Nullable
    public CompanionQueries getCompanionQueries() {
        CompanionPersistenceModule module = companionModule;
        return module != null && module.ready() ? module.queries() : null;
    }

    /** True when the companion record of {@code profileId} could not be read at startup. */
    public boolean isCompanionRecordUnreadable(@Nonnull java.util.UUID profileId) {
        CompanionPersistenceModule module = companionModule;
        return module != null && module.unreadable().test(profileId);
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
        refreshNeedsResourceHotPathDiagnostics();
        return debugNeedsSeekDiagnosticsLogs;
    }

    public boolean toggleDebugNeedsSeekDiagnosticsEnabled() {
        return setDebugNeedsSeekDiagnosticsEnabled(!debugNeedsSeekDiagnosticsLogs);
    }

    public boolean isDebugNeedsTelemetryDiagnosticsEnabled() {
        return debugNeedsTelemetryDiagnostics;
    }

    public boolean setDebugNeedsTelemetryDiagnosticsEnabled(boolean enabled) {
        debugNeedsTelemetryDiagnostics = enabled;
        refreshNeedsResourceHotPathDiagnostics();
        return debugNeedsTelemetryDiagnostics;
    }

    public boolean toggleDebugNeedsTelemetryDiagnosticsEnabled() {
        return setDebugNeedsTelemetryDiagnosticsEnabled(!debugNeedsTelemetryDiagnostics);
    }

    private void refreshNeedsResourceHotPathDiagnostics() {
        NeedsResourceHotPathDiagnostics.setEnabled(debugNeedsSeekDiagnosticsLogs);
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



