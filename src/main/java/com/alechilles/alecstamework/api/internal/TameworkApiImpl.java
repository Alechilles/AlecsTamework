package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ClaimAccessDecisionView;
import com.alechilles.alecstamework.api.CommandItemConfigView;
import com.alechilles.alecstamework.api.CommandLinkView;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.api.GlobalConfigView;
import com.alechilles.alecstamework.api.InteractionConfigView;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NameItemConfigView;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.OwnershipPolicyView;
import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.ProgressionMutationResult;
import com.alechilles.alecstamework.api.ProgressionMutationStatus;
import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.RoleScopedConfigView;
import com.alechilles.alecstamework.api.SpawnerConfigView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTalentConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.damage.TamedDamageOwnerPolicyResolver;
import com.alechilles.alecstamework.damage.SimpleClaimsRawAccessDecision;
import com.alechilles.alecstamework.damage.TamedDamageDecision;
import com.alechilles.alecstamework.damage.TamedDamageOwnerPolicy;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingConfigResolver;
import com.alechilles.alecstamework.npc.progression.BreedingEligibilityService;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentStateService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionModelScaleService;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionTalentService;
import com.alechilles.alecstamework.npc.progression.HappinessConfigResolver;
import com.alechilles.alecstamework.npc.progression.NeedsConfigResolver;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.npc.progression.TraitModifierService;
import com.alechilles.alecstamework.npc.progression.TraitRollService;
import com.alechilles.alecstamework.persistence.sqlite.ApiProfileDataRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class TameworkApiImpl
        implements TameworkApi, NpcProfilesApi, ProfileDataApi, TameworkConfigReadApi, PolicyApi, DiagnosticsApi,
        AutoCloseable {
    static final String API_VERSION = "0.7.0";
    static final String RESERVED_NAMESPACE = "Alechilles:Tamework";
    private static final String SNAPSHOT_CAPTURE = "capture";
    private static final String SNAPSHOT_DEATH = "death";
    private static final String SNAPSHOT_LOST = "lost";
    private static final String[] COMMAND_LINK_SNAPSHOT_PRIORITY = {SNAPSHOT_CAPTURE, SNAPSHOT_DEATH, SNAPSHOT_LOST};

    private final TameworkPersistenceRuntime persistenceRuntime;
    private final NpcProfileRepository profileRepository;
    private final ApiProfileDataRepository profileDataRepository;
    private final TameworkEventBus eventBus;
    @Nullable
    private final CommandLinkedNpcStateSnapshotService stateSnapshotService;
    private final SimpleClaimsTamedDamagePolicy damagePolicy;
    private final PopulationPolicyApiDelegate populationPolicy;
    private final InteractionExtensionApi interactionExtensionApi;
    private final TraitEffectApi traitEffectApi;
    private final CommandLinksApi commandLinksApi = new CommandLinksApi() {
        @Override
        public Optional<CommandLinkView> getByProfileId(String profileId) {
            return getCommandLinkByProfileId(profileId);
        }

        @Override
        public Optional<CommandLinkView> getByNpcUuid(UUID npcUuid) {
            return getCommandLinkByNpcUuid(npcUuid);
        }

        @Override
        public Set<String> listLinkedToolIds(String profileId) {
            return listLinkedToolIdsInternal(profileId);
        }

        @Override
        public Optional<Vector3View> getHomePosition(String profileId) {
            return getHomePositionInternal(profileId);
        }

        @Override
        public boolean hasHomePosition(String profileId) {
            return hasHomePositionInternal(profileId);
        }
    };
    private final ProgressionApi progressionApi = new ProgressionApi() {
        @Override
        public Optional<ProgressionView> getByProfileId(String profileId) {
            return getProgressionByProfileId(profileId);
        }

        @Override
        public Optional<ProgressionView> getByNpcUuid(UUID npcUuid) {
            return getProgressionByNpcUuid(npcUuid);
        }

        @Override
        public ProgressionMutationResult setHappiness(String profileId, double value) {
            return setHappinessByProfileId(profileId, value);
        }

        @Override
        public ProgressionMutationResult setHappiness(UUID npcUuid, double value) {
            return setHappinessByNpcUuid(npcUuid, value);
        }

        @Override
        public ProgressionMutationResult applyHappinessDelta(String profileId, double delta) {
            return applyHappinessDeltaByProfileId(profileId, delta);
        }

        @Override
        public ProgressionMutationResult applyHappinessDelta(UUID npcUuid, double delta) {
            return applyHappinessDeltaByNpcUuid(npcUuid, delta);
        }

        @Override
        public ProgressionMutationResult setNeeds(String profileId, @Nullable Double hunger, @Nullable Double thirst) {
            return setNeedsByProfileId(profileId, hunger, thirst);
        }

        @Override
        public ProgressionMutationResult setNeeds(UUID npcUuid, @Nullable Double hunger, @Nullable Double thirst) {
            return setNeedsByNpcUuid(npcUuid, hunger, thirst);
        }

        @Override
        public ProgressionMutationResult setBreedingReady(String profileId, boolean ready) {
            return setBreedingReadyByProfileId(profileId, ready);
        }

        @Override
        public ProgressionMutationResult setBreedingReady(UUID npcUuid, boolean ready) {
            return setBreedingReadyByNpcUuid(npcUuid, ready);
        }

        @Override
        public ProgressionMutationResult rerollTraits(String profileId) {
            return rerollTraitsByProfileId(profileId);
        }

        @Override
        public ProgressionMutationResult rerollTraits(UUID npcUuid) {
            return rerollTraitsByNpcUuid(npcUuid);
        }

        @Override
        public ProgressionMutationResult setTraits(String profileId, Map<String, Double> traitValues) {
            return setTraitsByProfileId(profileId, traitValues);
        }

        @Override
        public ProgressionMutationResult setTraits(UUID npcUuid, Map<String, Double> traitValues) {
            return setTraitsByNpcUuid(npcUuid, traitValues);
        }

        @Override
        public ProgressionMutationResult refreshLifeStage(String profileId) {
            return refreshLifeStageByProfileId(profileId);
        }

        @Override
        public ProgressionMutationResult refreshLifeStage(UUID npcUuid) {
            return refreshLifeStageByNpcUuid(npcUuid);
        }

        @Override
        public ProgressionMutationResult setStoredAttachments(String profileId, Map<String, String> attachmentSelections) {
            return setStoredAttachmentsByProfileId(profileId, attachmentSelections);
        }

        @Override
        public ProgressionMutationResult setStoredAttachments(UUID npcUuid, Map<String, String> attachmentSelections) {
            return setStoredAttachmentsByNpcUuid(npcUuid, attachmentSelections);
        }

        @Override
        public ProgressionMutationResult syncStoredAttachments(String profileId) {
            return syncStoredAttachmentsByProfileId(profileId);
        }

        @Override
        public ProgressionMutationResult syncStoredAttachments(UUID npcUuid) {
            return syncStoredAttachmentsByNpcUuid(npcUuid);
        }
    };
    private final EnumSet<TameworkApiCapability> capabilities = EnumSet.of(
            TameworkApiCapability.PROFILES,
            TameworkApiCapability.COMMAND_LINKS,
            TameworkApiCapability.PROGRESSION,
            TameworkApiCapability.PROGRESSION_MUTATIONS,
            TameworkApiCapability.POLICY,
            TameworkApiCapability.INTERACTION_EXTENSIONS,
            TameworkApiCapability.TRAIT_EFFECTS,
            TameworkApiCapability.PROFILE_DATA,
            TameworkApiCapability.EVENTS,
            TameworkApiCapability.COMPANION_XP_EVENTS,
            TameworkApiCapability.CONFIG_READ,
            TameworkApiCapability.DIAGNOSTICS
    );
    private final Gson gson = new Gson();

    public TameworkApiImpl(@Nonnull TameworkPersistenceRuntime persistenceRuntime,
                           @Nonnull TameworkEventBus eventBus,
                           @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                           @Nonnull InteractionExtensionApi interactionExtensionApi,
                           @Nonnull TraitEffectApi traitEffectApi) {
        this(
                persistenceRuntime,
                eventBus,
                stateSnapshotService,
                interactionExtensionApi,
                traitEffectApi,
                new SimpleClaimsTamedDamagePolicy()
        );
    }

    TameworkApiImpl(@Nonnull TameworkPersistenceRuntime persistenceRuntime,
                    @Nonnull TameworkEventBus eventBus,
                    @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                    @Nonnull InteractionExtensionApi interactionExtensionApi,
                    @Nonnull TraitEffectApi traitEffectApi,
                    @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy) {
        this(
                persistenceRuntime,
                eventBus,
                stateSnapshotService,
                interactionExtensionApi,
                traitEffectApi,
                damagePolicy,
                UnavailablePopulationPolicyAuthority.INSTANCE
        );
    }

    public TameworkApiImpl(@Nonnull TameworkPersistenceRuntime persistenceRuntime,
                           @Nonnull TameworkEventBus eventBus,
                           @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService,
                           @Nonnull InteractionExtensionApi interactionExtensionApi,
                           @Nonnull TraitEffectApi traitEffectApi,
                           @Nonnull SimpleClaimsTamedDamagePolicy damagePolicy,
                           @Nonnull PopulationPolicyAuthority populationPolicyAuthority) {
        this.persistenceRuntime = Objects.requireNonNull(persistenceRuntime);
        this.profileRepository = Objects.requireNonNull(persistenceRuntime.getNpcProfileRepository());
        this.profileDataRepository = Objects.requireNonNull(persistenceRuntime.getApiProfileDataRepository());
        this.eventBus = Objects.requireNonNull(eventBus);
        this.stateSnapshotService = stateSnapshotService;
        this.damagePolicy = Objects.requireNonNull(damagePolicy);
        this.populationPolicy = new PopulationPolicyApiDelegate(populationPolicyAuthority);
        this.interactionExtensionApi = Objects.requireNonNull(interactionExtensionApi);
        this.traitEffectApi = Objects.requireNonNull(traitEffectApi);
    }

    @Override
    public String getApiVersion() {
        return API_VERSION;
    }

    @Override
    public EnumSet<TameworkApiCapability> getCapabilities() {
        return capabilities.clone();
    }

    /** Drops reflected optional-claim contracts after a settings change. */
    public void onRuntimeSettingsChanged() {
        damagePolicy.onRuntimeSettingsChanged();
    }

    /** Releases optional-plugin references owned by this API instance. */
    @Override
    public void close() {
        damagePolicy.close();
    }

    @Override
    public NpcProfilesApi profiles() {
        return this;
    }

    @Override
    public CommandLinksApi commandLinks() {
        return commandLinksApi;
    }

    @Override
    public ProgressionApi progression() {
        return progressionApi;
    }

    @Override
    public PolicyApi policies() {
        return this;
    }

    @Override
    public InteractionExtensionApi interactionExtensions() {
        return interactionExtensionApi;
    }

    @Override
    public TraitEffectApi traitEffects() {
        return traitEffectApi;
    }

    @Override
    public ProfileDataApi profileData() {
        return this;
    }

    @Override
    public TameworkEventsApi events() {
        return eventBus;
    }

    @Override
    public TameworkConfigReadApi configs() {
        return this;
    }

    @Override
    public DiagnosticsApi diagnostics() {
        return this;
    }

    private ProgressionMutationResult setHappinessByProfileId(@Nullable String profileId,
                                                              double value) {
        if (!Double.isFinite(value)) {
            return invalidMutation("Happiness value must be finite.");
        }
        return withLoadedProgressionTargetByProfileId(profileId, target -> setHappinessInternal(target, value));
    }

    private ProgressionMutationResult setHappinessByNpcUuid(@Nullable UUID npcUuid,
                                                            double value) {
        if (!Double.isFinite(value)) {
            return invalidMutation("Happiness value must be finite.");
        }
        return withLoadedProgressionTargetByNpcUuid(npcUuid, target -> setHappinessInternal(target, value));
    }

    private ProgressionMutationResult applyHappinessDeltaByProfileId(@Nullable String profileId,
                                                                     double delta) {
        if (!Double.isFinite(delta)) {
            return invalidMutation("Happiness delta must be finite.");
        }
        return withLoadedProgressionTargetByProfileId(profileId, target -> applyHappinessDeltaInternal(target, delta));
    }

    private ProgressionMutationResult applyHappinessDeltaByNpcUuid(@Nullable UUID npcUuid,
                                                                   double delta) {
        if (!Double.isFinite(delta)) {
            return invalidMutation("Happiness delta must be finite.");
        }
        return withLoadedProgressionTargetByNpcUuid(npcUuid, target -> applyHappinessDeltaInternal(target, delta));
    }

    private ProgressionMutationResult setNeedsByProfileId(@Nullable String profileId,
                                                          @Nullable Double hunger,
                                                          @Nullable Double thirst) {
        if (hunger == null && thirst == null) {
            return invalidMutation("At least one of hunger or thirst must be provided.");
        }
        if ((hunger != null && !Double.isFinite(hunger)) || (thirst != null && !Double.isFinite(thirst))) {
            return invalidMutation("Needs values must be finite.");
        }
        return withLoadedProgressionTargetByProfileId(profileId, target -> setNeedsInternal(target, hunger, thirst));
    }

    private ProgressionMutationResult setNeedsByNpcUuid(@Nullable UUID npcUuid,
                                                        @Nullable Double hunger,
                                                        @Nullable Double thirst) {
        if (hunger == null && thirst == null) {
            return invalidMutation("At least one of hunger or thirst must be provided.");
        }
        if ((hunger != null && !Double.isFinite(hunger)) || (thirst != null && !Double.isFinite(thirst))) {
            return invalidMutation("Needs values must be finite.");
        }
        return withLoadedProgressionTargetByNpcUuid(npcUuid, target -> setNeedsInternal(target, hunger, thirst));
    }

    private ProgressionMutationResult setBreedingReadyByProfileId(@Nullable String profileId,
                                                                  boolean ready) {
        return withLoadedProgressionTargetByProfileId(profileId, target -> setBreedingReadyInternal(target, ready));
    }

    private ProgressionMutationResult setBreedingReadyByNpcUuid(@Nullable UUID npcUuid,
                                                                boolean ready) {
        return withLoadedProgressionTargetByNpcUuid(npcUuid, target -> setBreedingReadyInternal(target, ready));
    }

    private ProgressionMutationResult rerollTraitsByProfileId(@Nullable String profileId) {
        return withLoadedProgressionTargetByProfileId(profileId, this::rerollTraitsInternal);
    }

    private ProgressionMutationResult rerollTraitsByNpcUuid(@Nullable UUID npcUuid) {
        return withLoadedProgressionTargetByNpcUuid(npcUuid, this::rerollTraitsInternal);
    }

    private ProgressionMutationResult setTraitsByProfileId(@Nullable String profileId,
                                                           @Nullable Map<String, Double> traitValues) {
        if (traitValues == null) {
            return invalidMutation("Trait values map is required.");
        }
        return withLoadedProgressionTargetByProfileId(profileId, target -> setTraitsInternal(target, traitValues));
    }

    private ProgressionMutationResult setTraitsByNpcUuid(@Nullable UUID npcUuid,
                                                         @Nullable Map<String, Double> traitValues) {
        if (traitValues == null) {
            return invalidMutation("Trait values map is required.");
        }
        return withLoadedProgressionTargetByNpcUuid(npcUuid, target -> setTraitsInternal(target, traitValues));
    }

    private ProgressionMutationResult refreshLifeStageByProfileId(@Nullable String profileId) {
        return withLoadedProgressionTargetByProfileId(profileId, this::refreshLifeStageInternal);
    }

    private ProgressionMutationResult refreshLifeStageByNpcUuid(@Nullable UUID npcUuid) {
        return withLoadedProgressionTargetByNpcUuid(npcUuid, this::refreshLifeStageInternal);
    }

    private ProgressionMutationResult setStoredAttachmentsByProfileId(@Nullable String profileId,
                                                                      @Nullable Map<String, String> attachmentSelections) {
        if (attachmentSelections == null) {
            return invalidMutation("Attachment selections map is required.");
        }
        return withLoadedProgressionTargetByProfileId(
                profileId,
                target -> setStoredAttachmentsInternal(target, attachmentSelections)
        );
    }

    private ProgressionMutationResult setStoredAttachmentsByNpcUuid(@Nullable UUID npcUuid,
                                                                    @Nullable Map<String, String> attachmentSelections) {
        if (attachmentSelections == null) {
            return invalidMutation("Attachment selections map is required.");
        }
        return withLoadedProgressionTargetByNpcUuid(
                npcUuid,
                target -> setStoredAttachmentsInternal(target, attachmentSelections)
        );
    }

    private ProgressionMutationResult syncStoredAttachmentsByProfileId(@Nullable String profileId) {
        return withLoadedProgressionTargetByProfileId(profileId, this::syncStoredAttachmentsInternal);
    }

    private ProgressionMutationResult syncStoredAttachmentsByNpcUuid(@Nullable UUID npcUuid) {
        return withLoadedProgressionTargetByNpcUuid(npcUuid, this::syncStoredAttachmentsInternal);
    }

    @Override
    public Optional<String> resolveProfileId(UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.resolveProfileId(npcUuid));
    }

    @Override
    public Optional<NpcProfileView> getByProfileId(String profileId) {
        if (isBlank(profileId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.loadProfileById(profileId.trim()))
                .map(ApiMapper::mapProfile);
    }

    @Override
    public Optional<NpcProfileView> getByNpcUuid(UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.loadProfileByNpcUuid(npcUuid))
                .map(ApiMapper::mapProfile);
    }

    @Override
    public Optional<String> getActiveSnapshot(String profileId, String snapshotType) {
        if (isBlank(profileId) || isBlank(snapshotType)) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.loadActiveSnapshotPayload(profileId.trim(), snapshotType.trim()));
    }

    @Override
    public Set<String> listActiveSnapshotTypes(String profileId) {
        if (isBlank(profileId)) {
            return Set.of();
        }
        return Collections.unmodifiableSet(profileRepository.listActiveSnapshotTypes(profileId.trim()));
    }

    @Override
    public Optional<String> get(String profileId, String namespace, String key) {
        if (!isValidProfileDataScope(profileId, namespace, key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileDataRepository.get(profileId.trim(), namespace.trim(), key.trim()));
    }

    @Override
    public Map<String, String> list(String profileId, String namespace) {
        if (isBlank(profileId) || !isValidNamespace(namespace)) {
            return Map.of();
        }
        LinkedHashMap<String, String> values = profileDataRepository.list(profileId.trim(), namespace.trim());
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public boolean put(String profileId, String namespace, String key, String jsonPayload) {
        if (!isValidProfileDataScope(profileId, namespace, key) || isBlank(jsonPayload)) {
            return false;
        }
        try {
            String normalizedJson = JsonParser.parseString(jsonPayload).toString();
            return profileDataRepository.putAsync(profileId.trim(), namespace.trim(), key.trim(), normalizedJson);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean delete(String profileId, String namespace, String key) {
        if (!isValidProfileDataScope(profileId, namespace, key)) {
            return false;
        }
        return profileDataRepository.deleteAsync(profileId.trim(), namespace.trim(), key.trim());
    }

    @Override
    public GlobalConfigView getGlobalConfig() {
        return ApiMapper.mapGlobalConfig(TwGlobalConfig.resolveActive());
    }

    @Override
    public Optional<InteractionConfigView> getInteractionConfigById(String id) {
        return configById(id, TwInteractionConfig.getAssetMap() != null
                        ? TwInteractionConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapInteractionConfig(config, gson));
    }

    @Override
    public Optional<InteractionConfigView> resolveInteractionConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwInteractionConfig::resolveForRole)
                .map(config -> ApiMapper.mapInteractionConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getCompanionConfigById(String id) {
        return configById(id, TwCompanionConfig.getAssetMap() != null
                        ? TwCompanionConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveCompanionConfigForRole(String roleId) {
        return resolveCompanionConfig(roleId)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<SpawnerConfigView> getSpawnerConfigById(String id) {
        return configById(id, TwSpawnerConfig.getAssetMap() != null
                        ? TwSpawnerConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapSpawnerConfig(config, gson));
    }

    @Override
    public Optional<SpawnerConfigView> resolveSpawnerConfigForItemId(String itemId) {
        if (isBlank(itemId) || TwSpawnerConfig.getAssetMap() == null) {
            return Optional.empty();
        }
        String normalizedItemId = normalizeItemId(itemId);
        for (TwSpawnerConfig config : TwSpawnerConfig.getAssetMap().getAssetMap().values()) {
            if (config == null) {
                continue;
            }
            if (matchesItemId(config.getEmptyItemId(), normalizedItemId)
                    || matchesItemId(config.getFilledItemId(), normalizedItemId)) {
                return Optional.of(ApiMapper.mapSpawnerConfig(config, gson));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<NameItemConfigView> getNameItemConfigById(String id) {
        return configById(id, TwNameItemConfig.getAssetMap() != null
                        ? TwNameItemConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapNameItemConfig(config, gson));
    }

    @Override
    public Optional<NameItemConfigView> resolveNameItemConfigForItemId(String itemId) {
        if (isBlank(itemId) || TwNameItemConfig.getAssetMap() == null) {
            return Optional.empty();
        }
        String normalizedItemId = normalizeItemId(itemId);
        for (TwNameItemConfig config : TwNameItemConfig.getAssetMap().getAssetMap().values()) {
            if (config != null && matchesItemId(config.getItemId(), normalizedItemId)) {
                return Optional.of(ApiMapper.mapNameItemConfig(config, gson));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<CommandItemConfigView> getCommandItemConfigById(String id) {
        return configById(id, TwCommandItemConfig.getAssetMap() != null
                        ? TwCommandItemConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapCommandItemConfig(config, gson));
    }

    @Override
    public Optional<CommandItemConfigView> resolveCommandItemConfigForItemId(String itemId) {
        if (isBlank(itemId) || TwCommandItemConfig.getAssetMap() == null) {
            return Optional.empty();
        }
        String normalizedItemId = normalizeItemId(itemId);
        for (TwCommandItemConfig config : TwCommandItemConfig.getAssetMap().getAssetMap().values()) {
            if (config == null) {
                continue;
            }
            for (String configuredItemId : config.getItemIds()) {
                if (matchesItemId(configuredItemId, normalizedItemId)) {
                    return Optional.of(ApiMapper.mapCommandItemConfig(config, gson));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<RoleScopedConfigView> getHappinessConfigById(String id) {
        return configById(id, TwHappinessConfig.getAssetMap() != null
                        ? TwHappinessConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveHappinessConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwHappinessConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getNeedsConfigById(String id) {
        return configById(id, TwNeedsConfig.getAssetMap() != null
                        ? TwNeedsConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveNeedsConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwNeedsConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getBreedingConfigById(String id) {
        return configById(id, TwBreedingConfig.getAssetMap() != null
                        ? TwBreedingConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveBreedingConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwBreedingConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getLevelingConfigById(String id) {
        return configById(id, TwLevelingConfig.getAssetMap() != null
                        ? TwLevelingConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveLevelingConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwLevelingConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getTraitConfigById(String id) {
        return configById(id, TwTraitConfig.getAssetMap() != null
                        ? TwTraitConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveTraitConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwTraitConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> getTalentConfigById(String id) {
        return configById(id, TwTalentConfig.getAssetMap() != null
                        ? TwTalentConfig.getAssetMap().getAssetMap()
                        : Map.of())
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<RoleScopedConfigView> resolveTalentConfigForRole(String roleId) {
        return resolveConfigForRole(roleId, TwTalentConfig::resolveForRole)
                .map(config -> ApiMapper.mapRoleScopedConfig(config, gson));
    }

    @Override
    public Optional<OwnershipPolicyView> getOwnershipByProfileId(String profileId) {
        if (isBlank(profileId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.loadProfileById(profileId.trim()))
                .map(this::mapOwnershipPolicy);
    }

    @Override
    public Optional<OwnershipPolicyView> getOwnershipByNpcUuid(UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profileRepository.loadProfileByNpcUuid(npcUuid))
                .map(this::mapOwnershipPolicy);
    }

    @Override
    public boolean isOwner(String profileId, UUID playerUuid) {
        if (isBlank(profileId) || playerUuid == null) {
            return false;
        }
        return getOwnershipByProfileId(profileId)
                .map(OwnershipPolicyView::ownerUuid)
                .filter(playerUuid::equals)
                .isPresent();
    }

    @Nonnull
    @Override
    public ClaimAccessDecisionView evaluateClaimAccess(String profileId, @Nullable UUID playerUuid) {
        if (isBlank(profileId)) {
            return unavailableClaimDecision("profile-id-missing");
        }
        NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileById(profileId.trim());
        if (profile == null) {
            return unavailableClaimDecision("profile-not-found");
        }
        TwGlobalConfig globalConfig = resolveSimpleClaimsConfig();
        if (!TameworkRuntimeSettings.simpleClaimsEnabled(globalConfig.isSimpleClaimsEnabled())) {
            return new ClaimAccessDecisionView(
                    false,
                    true,
                    ClaimAccessDecisionView.Status.SKIPPED,
                    "simpleclaims-disabled",
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        LiveNpcContext liveContext = readLiveNpcContext(profile.currentNpcUuid());
        SimpleClaimsRawAccessDecision decision = damagePolicy.evaluateRawClaimAccess(
                liveContext != null ? liveContext.worldName() : null,
                liveContext != null ? toVector(liveContext.currentPosition()) : null,
                playerUuid,
                globalConfig
        );
        return mapClaimAccess(decision, liveContext);
    }

    @Nonnull
    @Override
    public DamagePolicyDecisionView evaluateDamage(String profileId, @Nullable UUID attackerPlayerUuid) {
        Optional<OwnershipPolicyView> ownership = getOwnershipByProfileId(profileId);
        if (ownership.isEmpty()) {
            return ApiDamagePolicyMapper.profileMissing(profileId, attackerPlayerUuid);
        }

        OwnershipPolicyView policy = ownership.orElseThrow();
        ResolvedLiveNpc liveNpc = resolveLiveNpc(policy.currentNpcUuid());
        TamedDamageOwnerPolicyResolver.Resolution liveOwner = liveNpc == null
                ? null
                : TamedDamageOwnerPolicyResolver.resolveLive(
                        liveNpc.reference(), liveNpc.store()
                );
        OwnershipPolicyView effectivePolicy = liveOwner == null
                ? policy
                : ApiDamagePolicyMapper.withLiveOwnerPolicy(policy, liveOwner);
        Vector3d targetPosition = liveNpc != null
                && liveNpc.transform() != null
                && liveNpc.transform().getPosition() != null
                ? new Vector3d(liveNpc.transform().getPosition())
                : null;
        String worldName = liveNpc != null ? liveNpc.world().getName() : null;
        TamedDamageDecision decision = damagePolicy.evaluate(
                new TamedDamageOwnerPolicy(
                        effectivePolicy.ownerUuid(),
                        effectivePolicy.blockOwnerDamage(),
                        effectivePolicy.blockAllPlayerDamageIfOwned(),
                        effectivePolicy.invulnerableIfOwned()
                ),
                liveNpc != null ? liveNpc.reference() : null,
                liveNpc != null ? liveNpc.store() : null,
                worldName,
                targetPosition,
                attackerPlayerUuid,
                resolveSimpleClaimsConfig()
        );
        return ApiDamagePolicyMapper.map(
                effectivePolicy,
                attackerPlayerUuid,
                decision,
                worldName,
                ApiMapper.mapVector(targetPosition)
        );
    }

    @Nonnull
    @Override
    @Deprecated(since = "0.7.0", forRemoval = false)
    public PopulationCapDecisionView evaluatePopulationCap(@Nullable UUID ownerUuid) {
        return populationPolicy.evaluateLegacy(ownerUuid);
    }

    @Nonnull
    @Override
    public OwnerPopulationCapDecisionViewV2 evaluatePopulationCap(@Nonnull OwnerPopulationCapRequestV2 request) {
        return populationPolicy.evaluate(request);
    }

    @Nonnull
    @Override
    public PopulationAdmissionApi populationAdmissions() {
        return populationPolicy.admissions();
    }

    @Nonnull
    @Override
    public PersistenceDiagnosticsView getPersistenceDiagnostics() {
        return ApiMapper.mapPersistenceDiagnostics(persistenceRuntime.collectDiagnostics());
    }

    @Nonnull
    @Override
    public PopulationDiagnosticsView getPopulationDiagnostics() {
        return populationPolicy.diagnostics();
    }

    private ProgressionMutationResult withLoadedProgressionTargetByProfileId(
            @Nullable String profileId,
            @Nonnull Function<ResolvedProgressionTarget, ProgressionMutationResult> operation) {
        String normalizedProfileId = normalizeBlank(profileId);
        try {
            if (normalizedProfileId == null) {
                return invalidMutation("Profile id is required.");
            }
            NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileById(normalizedProfileId);
            if (profile == null) {
                return notFoundMutation("No profile found for id '" + normalizedProfileId + "'.");
            }
            UUID currentNpcUuid = profile.currentNpcUuid();
            if (currentNpcUuid == null) {
                return notLoadedMutation("Profile '" + profile.profileId() + "' has no current NPC UUID.");
            }
            ResolvedLiveNpc liveNpc = resolveLiveNpc(currentNpcUuid);
            if (liveNpc == null) {
                return notLoadedMutation("NPC for profile '" + profile.profileId() + "' is not currently loaded.");
            }
            return operation.apply(new ResolvedProgressionTarget(
                    profile.profileId(),
                    profile.roleId(),
                    currentNpcUuid,
                    liveNpc
            ));
        } catch (Throwable throwable) {
            return errorMutation("profile '" + (normalizedProfileId != null ? normalizedProfileId : "<unknown>") + "'", throwable);
        }
    }

    private ProgressionMutationResult withLoadedProgressionTargetByNpcUuid(
            @Nullable UUID npcUuid,
            @Nonnull Function<ResolvedProgressionTarget, ProgressionMutationResult> operation) {
        try {
            if (npcUuid == null) {
                return invalidMutation("NPC UUID is required.");
            }
            NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileByNpcUuid(npcUuid);
            ResolvedLiveNpc liveNpc = resolveLiveNpc(npcUuid);
            if (liveNpc == null) {
                if (profile != null) {
                    return notLoadedMutation("NPC '" + npcUuid + "' is not currently loaded.");
                }
                return notFoundMutation("No live NPC or profile found for UUID '" + npcUuid + "'.");
            }
            return operation.apply(new ResolvedProgressionTarget(
                    profile != null ? profile.profileId() : null,
                    profile != null ? profile.roleId() : null,
                    npcUuid,
                    liveNpc
            ));
        } catch (Throwable throwable) {
            return errorMutation("NPC '" + (npcUuid != null ? npcUuid : "<unknown>") + "'", throwable);
        }
    }

    private ProgressionMutationResult setHappinessInternal(@Nonnull ResolvedProgressionTarget target,
                                                           double requestedValue) {
        if (!Double.isFinite(requestedValue)) {
            return invalidMutation("Happiness value must be finite.");
        }
        String roleId = target.resolvedRoleId();
        CompanionProgressionBootstrapService.ensureProgressionComponents(target.reference(), target.store(), roleId);

        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (happinessType == null && breedingType == null) {
            return unsupportedMutation("Happiness state is not available for this NPC.");
        }

        TameworkHappinessComponent happiness = happinessType != null
                ? target.store().getComponent(target.reference(), happinessType)
                : null;
        TameworkBreedingComponent breeding = breedingType != null
                ? target.store().getComponent(target.reference(), breedingType)
                : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(target.reference(), target.store(), happiness);
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return unsupportedMutation("No happiness progression is configured for this NPC.");
        }

        double appliedValue = clampHappinessValue(requestedValue, happinessConfig);
        long now = System.currentTimeMillis();

        if (happinessType != null) {
            if (happiness == null) {
                happiness = new TameworkHappinessComponent(
                        happinessConfig != null ? normalizeBlank(happinessConfig.getId()) : null,
                        appliedValue,
                        now
                );
            } else {
                happiness.setValue(appliedValue);
                happiness.setLastUpdateMs(now);
                if ((happiness.getConfigId() == null || happiness.getConfigId().isBlank()) && happinessConfig != null) {
                    happiness.setConfigId(happinessConfig.getId());
                }
            }
            target.store().putComponent(target.reference(), happinessType, happiness);
        }

        if (breedingType != null && breeding != null) {
            breeding.setHappiness(appliedValue);
            breeding.setLastHappinessUpdateMs(now);
            TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(target.reference(), target.store(), breeding);
            if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                    && breedingConfig != null
                    && breedingConfig.getId() != null
                    && !breedingConfig.getId().isBlank()) {
                breeding.setConfigId(breedingConfig.getId());
            }
            if (breedingConfig != null) {
                breeding.setReady(breeding.isEnabled()
                        && appliedValue >= TameworkRuntimeSettings.breedingHappinessThreshold(
                                breedingConfig.resolveHappiness(roleId).getThreshold(),
                                TwHappinessConfig.isEnabledForRole(roleId)
                        ));
            }
            target.store().putComponent(target.reference(), breedingType, breeding);
        }

        return appliedMutation(target, "Set happiness to " + formatDecimal(appliedValue) + ".");
    }

    private ProgressionMutationResult applyHappinessDeltaInternal(@Nonnull ResolvedProgressionTarget target,
                                                                  double delta) {
        if (!Double.isFinite(delta)) {
            return invalidMutation("Happiness delta must be finite.");
        }
        CompanionProgressionBootstrapService.ensureProgressionComponents(
                target.reference(),
                target.store(),
                target.resolvedRoleId()
        );
        if (buildHappinessView(target.reference(), target.store()) == null) {
            return unsupportedMutation("No happiness progression is configured for this NPC.");
        }
        CompanionHappinessService.applyDelta(target.reference(), target.store(), delta);
        return appliedMutation(target, "Applied happiness delta " + formatSignedDecimal(delta) + ".");
    }

    private ProgressionMutationResult setNeedsInternal(@Nonnull ResolvedProgressionTarget target,
                                                       @Nullable Double requestedHunger,
                                                       @Nullable Double requestedThirst) {
        if (requestedHunger == null && requestedThirst == null) {
            return invalidMutation("At least one of hunger or thirst must be provided.");
        }
        if ((requestedHunger != null && !Double.isFinite(requestedHunger))
                || (requestedThirst != null && !Double.isFinite(requestedThirst))) {
            return invalidMutation("Needs values must be finite.");
        }

        String roleId = target.resolvedRoleId();
        TameworkNeedsComponent needs = CompanionNeedsService.ensureNeedsComponent(target.reference(), target.store(), roleId);
        if (needs == null) {
            return unsupportedMutation("No enabled needs config resolved for this NPC.");
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return unsupportedMutation("Needs state is not available for this NPC.");
        }
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(target.reference(), target.store(), needs);
        if (!NeedsConfigResolver.isRuntimeEnabled(config)) {
            return unsupportedMutation("No enabled needs config resolved for this NPC.");
        }

        TwNeedsConfig.ValueSettings values = config.getValues();
        double appliedHunger = requestedHunger != null
                ? clamp(requestedHunger, values.getHungerMin(), values.getHungerMax())
                : clamp(needs.getHunger(), values.getHungerMin(), values.getHungerMax());
        double appliedThirst = requestedThirst != null
                ? clamp(requestedThirst, values.getThirstMin(), values.getThirstMax())
                : clamp(needs.getThirst(), values.getThirstMin(), values.getThirstMax());

        long now = System.currentTimeMillis();
        needs.setHunger(appliedHunger);
        needs.setThirst(appliedThirst);
        needs.setLastUpdateMs(now);
        needs.setLastPassiveSweepMs(now);
        target.store().putComponent(target.reference(), needsType, needs);
        CompanionNeedsService.tickNeeds(target.reference(), target.store(), roleId);

        return appliedMutation(
                target,
                "Set needs to hunger=" + formatDecimal(appliedHunger) + ", thirst=" + formatDecimal(appliedThirst) + "."
        );
    }

    private ProgressionMutationResult setBreedingReadyInternal(@Nonnull ResolvedProgressionTarget target,
                                                               boolean ready) {
        CompanionProgressionBootstrapService.ensureProgressionComponents(
                target.reference(),
                target.store(),
                target.resolvedRoleId()
        );
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return unsupportedMutation("Breeding state is not available for this NPC.");
        }
        TameworkBreedingComponent breeding = target.store().getComponent(target.reference(), breedingType);
        if (breeding == null) {
            return unsupportedMutation("Breeding state is not available for this NPC.");
        }

        long now = BreedingTimeService.resolveCurrentTimeMs(target.store());
        if (ready) {
            breeding.setReady(true);
            breeding.setCooldownUntilMs(0L);
            breeding.setCooldownStartedAtMs(0L);
            breeding.setCooldownDurationMs(0L);
            breeding.setLastPartnerUuid(null);
        } else {
            breeding.setReady(false);
        }
        breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
        if (isBlank(breeding.getConfigId())) {
            TwBreedingConfig config = BreedingConfigResolver.resolveConfig(target.reference(), target.store(), breeding);
            if (config != null && !isBlank(config.getId())) {
                breeding.setConfigId(config.getId());
            }
        }
        target.store().putComponent(target.reference(), breedingType, breeding);
        return appliedMutation(target, ready ? "Forced breeding ready state." : "Cleared breeding ready state.");
    }

    private ProgressionMutationResult rerollTraitsInternal(@Nonnull ResolvedProgressionTarget target) {
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return unsupportedMutation("Trait state is not available for this NPC.");
        }
        TameworkTraitsComponent existing = target.store().getComponent(target.reference(), traitsType);
        TwTraitConfig config = resolveTraitConfig(target.reference(), target.store(), existing, target.profileRoleId());
        if (config == null || !config.isEnabled()) {
            return unsupportedMutation("No enabled trait config resolved for this NPC.");
        }

        long previousSeed = existing != null ? existing.getRollSeed() : 0L;
        long seed = freshTraitRollSeed(target.npcUuid(), previousSeed);
        TameworkTraitsComponent updated = new TameworkTraitsComponent(
                resolveTraitConfigId(config, existing),
                seed,
                TraitRollService.rollTraits(config, seed)
        );
        applyTraitMutation(target, config, existing, updated);
        return appliedMutation(target, "Rerolled traits using seed " + seed + ".");
    }

    private ProgressionMutationResult setTraitsInternal(@Nonnull ResolvedProgressionTarget target,
                                                        @Nullable Map<String, Double> traitValues) {
        if (traitValues == null) {
            return invalidMutation("Trait values map is required.");
        }
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return unsupportedMutation("Trait state is not available for this NPC.");
        }
        TameworkTraitsComponent existing = target.store().getComponent(target.reference(), traitsType);
        TwTraitConfig config = resolveTraitConfig(target.reference(), target.store(), existing, target.profileRoleId());
        if (config == null || !config.isEnabled()) {
            return unsupportedMutation("No enabled trait config resolved for this NPC.");
        }

        LinkedHashMap<String, TwTraitConfig.TraitDefinition> definitions = traitDefinitionMap(config);
        if (definitions.isEmpty()) {
            return unsupportedMutation("Trait config '" + config.getId() + "' has no trait definitions.");
        }

        LinkedHashMap<String, TameworkTraitsComponent.TraitValue> normalizedValues = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : traitValues.entrySet()) {
            if (entry == null) {
                continue;
            }
            String normalizedId = normalizeTraitId(entry.getKey());
            Double requestedValue = entry.getValue();
            if (normalizedId == null || requestedValue == null || !Double.isFinite(requestedValue)) {
                return invalidMutation("Trait values must use known ids with finite numeric values.");
            }
            TwTraitConfig.TraitDefinition definition = definitions.get(normalizedId);
            if (definition == null) {
                return invalidMutation(
                        "Unknown trait '" + entry.getKey() + "'. Known traits: " + String.join(", ", definitions.keySet())
                );
            }
            double appliedValue = clampTraitValue(requestedValue, definition);
            normalizedValues.put(
                    normalizedId,
                    new TameworkTraitsComponent.TraitValue(definition.getId(), appliedValue)
            );
        }

        long seed = existing != null && existing.getRollSeed() != 0L
                ? existing.getRollSeed()
                : deriveTraitRollSeed(target.npcUuid());
        TameworkTraitsComponent updated = new TameworkTraitsComponent(
                resolveTraitConfigId(config, existing),
                seed,
                normalizedValues.values().toArray(new TameworkTraitsComponent.TraitValue[0])
        );
        applyTraitMutation(target, config, existing, updated);
        return appliedMutation(target, "Set " + updated.getTraitValues().length + " trait values.");
    }

    private ProgressionMutationResult refreshLifeStageInternal(@Nonnull ResolvedProgressionTarget target) {
        String roleId = target.resolvedRoleId();
        CompanionLifeStageService.ensureLifeStageComponent(target.reference(), target.store(), roleId);
        NPCEntity npc = target.store().getComponent(target.reference(), NPCEntity.getComponentType());
        CompanionLifeStageService.refreshLifeStage(target.reference(), npc, target.store());
        CompanionLifeStageService.ensureGrowthTickScheduled(target.reference(), npc, target.store());
        if (buildLifeStageView(target.reference(), target.store(), roleId) == null) {
            return unsupportedMutation("No lifecycle progression is configured for this NPC.");
        }
        return appliedMutation(target, "Refreshed life-stage state.");
    }

    private ProgressionMutationResult setStoredAttachmentsInternal(@Nonnull ResolvedProgressionTarget target,
                                                                  @Nullable Map<String, String> attachmentSelections) {
        if (attachmentSelections == null) {
            return invalidMutation("Attachment selections map is required.");
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null) {
            return unsupportedMutation("Attachment state is not available for this NPC.");
        }

        Map<String, String> sanitizedSelections = CompanionModelAttachmentService.sanitizeAttachmentSelections(attachmentSelections);
        if (sanitizedSelections.size() != attachmentSelections.size()) {
            return invalidMutation("Attachment selections must use nonblank set ids and value ids.");
        }

        var modelAsset = CompanionModelAttachmentService.resolveModelAsset(target.reference(), target.store());
        if (modelAsset == null) {
            return unsupportedMutation("Current NPC model does not support attachment mutations.");
        }
        Map<String, java.util.Set<String>> options = CompanionModelAttachmentService.resolveAttachmentOptionIds(modelAsset);
        Map<String, String> filteredSelections = CompanionModelAttachmentService.filterAttachmentSelections(
                sanitizedSelections,
                options
        );
        if (filteredSelections.size() != sanitizedSelections.size()) {
            return invalidMutation("One or more attachment selections do not exist on the current NPC model.");
        }

        NPCEntity npc = target.store().getComponent(target.reference(), NPCEntity.getComponentType());
        if (!CompanionModelAttachmentService.applyAttachments(target.reference(), npc, target.store(), filteredSelections)) {
            return unsupportedMutation("Current NPC model does not support attachment mutations.");
        }

        if (filteredSelections.isEmpty()) {
            target.store().tryRemoveComponent(target.reference(), attachmentsType);
        } else {
            TameworkAttachmentsComponent existing = target.store().getComponent(target.reference(), attachmentsType);
            target.store().putComponent(
                    target.reference(),
                    attachmentsType,
                    new TameworkAttachmentsComponent(existing != null ? existing.getConfigId() : null, filteredSelections)
            );
        }
        return appliedMutation(target, "Updated stored attachments.");
    }

    private ProgressionMutationResult syncStoredAttachmentsInternal(@Nonnull ResolvedProgressionTarget target) {
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null) {
            return unsupportedMutation("Attachment state is not available for this NPC.");
        }
        TameworkAttachmentsComponent existing = target.store().getComponent(target.reference(), attachmentsType);
        if (existing == null || existing.getAttachmentIds().isEmpty()) {
            return unsupportedMutation("No stored attachments are present for this NPC.");
        }
        CompanionAttachmentStateService.syncStoredAttachments(target.reference(), target.store());
        return appliedMutation(target, "Synced stored attachments to the live NPC model.");
    }

    private void applyTraitMutation(@Nonnull ResolvedProgressionTarget target,
                                    @Nonnull TwTraitConfig config,
                                    @Nullable TameworkTraitsComponent existing,
                                    @Nonnull TameworkTraitsComponent updated) {
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return;
        }
        double previousSizeMultiplier = TraitModifierService.resolveMultiplier(existing, config, "SizeMultiplier", 1.0);
        double nextSizeMultiplier = TraitModifierService.resolveMultiplier(updated, config, "SizeMultiplier", 1.0);
        target.store().putComponent(target.reference(), traitsType, updated);
        CompanionStatModifierService.applyTraitModifiers(target.reference(), target.store());
        CompanionLifeStageService.applySizeMultiplierDelta(
                target.reference(),
                target.store(),
                previousSizeMultiplier,
                nextSizeMultiplier
        );
    }

    @Nonnull
    private LinkedHashMap<String, TwTraitConfig.TraitDefinition> traitDefinitionMap(@Nonnull TwTraitConfig config) {
        LinkedHashMap<String, TwTraitConfig.TraitDefinition> definitions = new LinkedHashMap<>();
        for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
            if (definition == null) {
                continue;
            }
            String normalizedId = normalizeTraitId(definition.getId());
            if (normalizedId != null) {
                definitions.putIfAbsent(normalizedId, definition);
            }
        }
        return definitions;
    }

    @Nullable
    private String normalizeTraitId(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return rawValue.trim().toLowerCase(Locale.ROOT);
    }

    private double clampTraitValue(double requestedValue,
                                   @Nonnull TwTraitConfig.TraitDefinition definition) {
        double min = Double.isFinite(definition.getBreedingMin()) ? definition.getBreedingMin() : definition.getDefaultValue();
        double max = Double.isFinite(definition.getBreedingMax()) ? definition.getBreedingMax() : definition.getDefaultValue();
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        return clamp(requestedValue, min, max);
    }

    @Nullable
    private String resolveTraitConfigId(@Nonnull TwTraitConfig config,
                                        @Nullable TameworkTraitsComponent existing) {
        String configId = normalizeBlank(config.getId());
        if (configId != null) {
            return configId;
        }
        return existing != null ? normalizeBlank(existing.getConfigId()) : null;
    }

    private long deriveTraitRollSeed(@Nonnull UUID npcUuid) {
        long seed = npcUuid.getMostSignificantBits() ^ npcUuid.getLeastSignificantBits();
        if (seed != 0L) {
            return seed;
        }
        long fallback = System.nanoTime();
        return fallback != 0L ? fallback : 1L;
    }

    private long freshTraitRollSeed(@Nonnull UUID npcUuid,
                                    long previousSeed) {
        long seed = System.nanoTime() ^ System.currentTimeMillis() ^ deriveTraitRollSeed(npcUuid);
        if (seed == 0L || seed == previousSeed) {
            seed = deriveTraitRollSeed(npcUuid) ^ 0x9E3779B97F4A7C15L;
        }
        if (seed == 0L || seed == previousSeed) {
            seed = previousSeed == Long.MAX_VALUE ? 1L : previousSeed + 1L;
        }
        return seed;
    }

    private double clampHappinessValue(double requestedValue,
                                       @Nullable TwHappinessConfig config) {
        if (config != null && config.isEnabled()) {
            double min = config.getValues().getMin();
            double max = config.getValues().getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            return clamp(requestedValue, min, max);
        }
        return clamp(requestedValue, 0.0, 100.0);
    }

    private double clamp(double value,
                         double min,
                         double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    @Nonnull
    private ProgressionMutationResult appliedMutation(@Nonnull ResolvedProgressionTarget target,
                                                      @Nonnull String message) {
        ProgressionView progression = null;
        String resolvedMessage = message;
        try {
            progression = snapshotProgression(target);
        } catch (Throwable throwable) {
            resolvedMessage = message + " Progression snapshot unavailable (" + describeThrowable(throwable) + ").";
        }
        return new ProgressionMutationResult(
                ProgressionMutationStatus.APPLIED,
                resolvedMessage,
                progression
        );
    }

    @Nonnull
    private ProgressionMutationResult invalidMutation(@Nonnull String message) {
        return new ProgressionMutationResult(ProgressionMutationStatus.INVALID_ARGUMENT, message, null);
    }

    @Nonnull
    private ProgressionMutationResult notFoundMutation(@Nonnull String message) {
        return new ProgressionMutationResult(ProgressionMutationStatus.NOT_FOUND, message, null);
    }

    @Nonnull
    private ProgressionMutationResult notLoadedMutation(@Nonnull String message) {
        return new ProgressionMutationResult(ProgressionMutationStatus.NOT_LOADED, message, null);
    }

    @Nonnull
    private ProgressionMutationResult unsupportedMutation(@Nonnull String message) {
        return new ProgressionMutationResult(ProgressionMutationStatus.UNSUPPORTED, message, null);
    }

    @Nonnull
    private ProgressionMutationResult errorMutation(@Nonnull String targetDescription,
                                                    @Nonnull Throwable throwable) {
        return new ProgressionMutationResult(
                ProgressionMutationStatus.ERROR,
                "Unexpected error while applying progression mutation for "
                        + targetDescription
                        + " ("
                        + describeThrowable(throwable)
                        + ").",
                null
        );
    }

    @Nullable
    private ProgressionView snapshotProgression(@Nonnull ResolvedProgressionTarget target) {
        return buildProgressionView(target.profileId(), target.profileRoleId(), target.npcUuid()).orElse(null);
    }

    @Nonnull
    private String describeThrowable(@Nonnull Throwable throwable) {
        String typeName = throwable.getClass().getSimpleName();
        if (typeName == null || typeName.isBlank()) {
            typeName = throwable.getClass().getName();
        }
        String detail = normalizeBlank(throwable.getMessage());
        return detail != null ? typeName + ": " + detail : typeName;
    }

    @Nonnull
    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    @Nonnull
    private String formatSignedDecimal(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
    }

    private Optional<ProgressionView> getProgressionByProfileId(@Nullable String profileId) {
        if (isBlank(profileId)) {
            return Optional.empty();
        }
        NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileById(profileId.trim());
        if (profile == null || profile.currentNpcUuid() == null) {
            return Optional.empty();
        }
        return buildProgressionView(profile.profileId(), profile.roleId(), profile.currentNpcUuid());
    }

    private Optional<ProgressionView> getProgressionByNpcUuid(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        NpcProfileRepository.ProfileRecord profile = profileRepository.loadProfileByNpcUuid(npcUuid);
        return buildProgressionView(
                profile != null ? profile.profileId() : null,
                profile != null ? profile.roleId() : null,
                npcUuid
        );
    }

    private Optional<ProgressionView> buildProgressionView(@Nullable String profileId,
                                                           @Nullable String profileRoleId,
                                                           @Nonnull UUID npcUuid) {
        ResolvedLiveNpc liveNpc = resolveLiveNpc(npcUuid);
        if (liveNpc == null) {
            return Optional.empty();
        }

        String roleId = firstNonBlank(CompanionRoleIdResolver.resolveRoleId(liveNpc.reference(), liveNpc.store()), profileRoleId);
        ProgressionView.HappinessView happiness = buildHappinessView(liveNpc.reference(), liveNpc.store());
        ProgressionView.NeedsView needs = buildNeedsView(liveNpc.reference(), liveNpc.store());
        ProgressionView.BreedingView breeding = buildBreedingView(liveNpc.reference(), liveNpc.store(), roleId);
        ProgressionView.LevelingView leveling = buildLevelingView(liveNpc.reference(), liveNpc.store(), roleId);
        ProgressionView.LifeStageView lifeStage = buildLifeStageView(liveNpc.reference(), liveNpc.store(), roleId);
        ProgressionView.TraitsView traits = buildTraitsView(liveNpc.reference(), liveNpc.store(), roleId);
        ProgressionView.TalentsView talents = buildTalentsView(liveNpc.reference(), liveNpc.store(), roleId);
        ProgressionView.AttachmentsView attachments = buildAttachmentsView(liveNpc.reference(), liveNpc.store());

        if (happiness == null
                && needs == null
                && breeding == null
                && leveling == null
                && lifeStage == null
                && traits == null
                && talents == null
                && attachments == null) {
            return Optional.empty();
        }

        return Optional.of(new ProgressionView(
                profileId,
                npcUuid,
                liveNpc.world().getName(),
                roleId,
                happiness,
                needs,
                breeding,
                leveling,
                lifeStage,
                traits,
                talents,
                attachments
        ));
    }

    @Nullable
    private ProgressionView.HappinessView buildHappinessView(@Nonnull Ref<EntityStore> npcRef,
                                                             @Nonnull Store<EntityStore> store) {
        TameworkHappinessComponent happinessComponent = readComponent(npcRef, store, TameworkHappinessComponent.getComponentType());
        TameworkBreedingComponent breedingComponent = readComponent(npcRef, store, TameworkBreedingComponent.getComponentType());
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happinessComponent);
        if (!HappinessConfigResolver.isRuntimeEnabled(happinessConfig)) {
            return null;
        }
        CompanionHappinessService.HappinessSnapshot snapshot = CompanionHappinessService.resolveSnapshot(npcRef, store);
        if (snapshot == null) {
            return null;
        }

        String source = "computed";
        String configId = null;
        long lastUpdateMs = 0L;
        if (happinessComponent != null && Double.isFinite(happinessComponent.getValue())) {
            source = "shared";
            configId = normalizeBlank(happinessComponent.getConfigId());
            lastUpdateMs = happinessComponent.getLastUpdateMs();
        } else if (breedingComponent != null && Double.isFinite(breedingComponent.getHappiness())) {
            source = "breeding-legacy";
            configId = normalizeBlank(breedingComponent.getConfigId());
            lastUpdateMs = breedingComponent.getLastHappinessUpdateMs();
        }
        if (configId == null && happinessConfig != null) {
            configId = normalizeBlank(happinessConfig.getId());
        }
        return ApiMapper.mapHappiness(configId, lastUpdateMs, source, snapshot);
    }

    @Nullable
    private ProgressionView.NeedsView buildNeedsView(@Nonnull Ref<EntityStore> npcRef,
                                                     @Nonnull Store<EntityStore> store) {
        TameworkNeedsComponent needsComponent = readComponent(npcRef, store, TameworkNeedsComponent.getComponentType());
        if (needsComponent == null) {
            return null;
        }
        TwNeedsConfig config = resolveRuntimeNeedsConfig(npcRef, store, needsComponent);
        if (config == null) {
            return null;
        }
        return ApiMapper.mapNeeds(
                needsComponent,
                config
        );
    }

    @Nullable
    private TwNeedsConfig resolveRuntimeNeedsConfig(@Nonnull Ref<EntityStore> npcRef,
                                                   @Nonnull Store<EntityStore> store,
                                                   @Nonnull TameworkNeedsComponent needsComponent) {
        TwNeedsConfig config = NeedsConfigResolver.resolveConfig(npcRef, store, needsComponent);
        return NeedsConfigResolver.isRuntimeEnabled(config) ? config : null;
    }

    @Nullable
    private ProgressionView.BreedingView buildBreedingView(@Nonnull Ref<EntityStore> npcRef,
                                                           @Nonnull Store<EntityStore> store,
                                                           @Nullable String roleIdFallback) {
        TameworkBreedingComponent breedingComponent = readComponent(npcRef, store, TameworkBreedingComponent.getComponentType());
        if (breedingComponent == null) {
            return null;
        }

        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breedingComponent);
        String roleId = firstNonBlank(CompanionRoleIdResolver.resolveRoleId(npcRef, store), roleIdFallback);
        String configId = normalizeBlank(breedingComponent.getConfigId());
        if (configId == null && config != null) {
            configId = normalizeBlank(config.getId());
        }

        Double threshold = null;
        if (config != null) {
            threshold = TameworkRuntimeSettings.breedingHappinessThreshold(
                    config.resolveHappiness(roleId).getThreshold(),
                    TwHappinessConfig.isEnabledForRole(roleId)
            );
        }
        double fertilityMultiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                "FertilityMultiplier",
                1.0
        );
        Double effectiveHappiness = Double.isFinite(breedingComponent.getHappiness())
                ? BreedingEligibilityService.resolveEffectiveHappiness(
                        breedingComponent.getHappiness(),
                        fertilityMultiplier,
                        null
                )
                : null;
        Boolean eligible = effectiveHappiness != null && threshold != null
                ? BreedingEligibilityService.isEligible(effectiveHappiness, threshold)
                : null;
        long nowMs = BreedingTimeService.resolveCurrentTimeMs(store);
        return ApiMapper.mapBreeding(
                configId,
                breedingComponent,
                nowMs,
                effectiveHappiness,
                threshold,
                eligible,
                fertilityMultiplier
        );
    }

    @Nullable
    private ProgressionView.LifeStageView buildLifeStageView(@Nonnull Ref<EntityStore> npcRef,
                                                             @Nonnull Store<EntityStore> store,
                                                             @Nullable String roleIdFallback) {
        TameworkLifeStageComponent lifeStageComponent = readComponent(npcRef, store, TameworkLifeStageComponent.getComponentType());
        String roleId = firstNonBlank(CompanionRoleIdResolver.resolveRoleId(npcRef, store), roleIdFallback);
        if (lifeStageComponent == null && !hasLifeStageSignal(roleId)) {
            return null;
        }

        String stage = CompanionLifeStageService.resolveCurrentStage(npcRef, store, roleId);
        boolean adult = CompanionLifeStageService.isAdult(npcRef, store, roleId);
        double currentScale = CompanionModelScaleService.resolveCurrentScale(npcRef, store, 1.0);
        return ApiMapper.mapLifeStage(stage, adult, currentScale, lifeStageComponent);
    }

    @Nullable
    private ProgressionView.LevelingView buildLevelingView(@Nonnull Ref<EntityStore> npcRef,
                                                           @Nonnull Store<EntityStore> store,
                                                           @Nullable String roleIdFallback) {
        CompanionLevelingService.LevelingSnapshot snapshot = CompanionLevelingService.resolveSnapshot(
                npcRef,
                store,
                roleIdFallback
        );
        if (snapshot == null) {
            return null;
        }
        return new ProgressionView.LevelingView(
                snapshot.configId(),
                snapshot.level(),
                snapshot.currentXp(),
                snapshot.totalXp(),
                snapshot.nextLevelDeltaXp(),
                snapshot.maxLevel(),
                snapshot.atMaxLevel()
        );
    }

    @Nullable
    private ProgressionView.TraitsView buildTraitsView(@Nonnull Ref<EntityStore> npcRef,
                                                       @Nonnull Store<EntityStore> store,
                                                       @Nullable String roleIdFallback) {
        TameworkTraitsComponent traitsComponent = readComponent(npcRef, store, TameworkTraitsComponent.getComponentType());
        if (traitsComponent == null) {
            return null;
        }
        return ApiMapper.mapTraits(traitsComponent, resolveTraitConfig(npcRef, store, traitsComponent, roleIdFallback));
    }

    @Nullable
    private ProgressionView.TalentsView buildTalentsView(@Nonnull Ref<EntityStore> npcRef,
                                                         @Nonnull Store<EntityStore> store,
                                                         @Nullable String roleIdFallback) {
        String resolvedRoleId = firstNonBlank(CompanionRoleIdResolver.resolveRoleId(npcRef, store), roleIdFallback);
        TwTalentConfig talentConfig = resolvedRoleId != null ? TwTalentConfig.resolveForRole(resolvedRoleId) : null;
        TameworkTalentsComponent talentsComponent = readComponent(npcRef, store, TameworkTalentsComponent.getComponentType());
        if ((talentConfig == null || !talentConfig.isEnabled()) && talentsComponent == null) {
            return null;
        }
        int availablePoints = CompanionTalentService.resolveAvailablePoints(npcRef, store);
        int spentPoints = talentsComponent != null ? talentsComponent.getSpentPoints() : 0;
        java.util.List<String> purchasedTalentIds = talentsComponent != null
                ? java.util.List.of(talentsComponent.getPurchasedTalentIds())
                : java.util.List.of();
        String configId = talentsComponent != null && talentsComponent.getConfigId() != null && !talentsComponent.getConfigId().isBlank()
                ? talentsComponent.getConfigId()
                : talentConfig != null ? talentConfig.getId() : null;
        return new ProgressionView.TalentsView(configId, availablePoints, spentPoints, purchasedTalentIds);
    }

    @Nullable
    private ProgressionView.AttachmentsView buildAttachmentsView(@Nonnull Ref<EntityStore> npcRef,
                                                                 @Nonnull Store<EntityStore> store) {
        TameworkAttachmentsComponent attachmentsComponent = readComponent(
                npcRef,
                store,
                TameworkAttachmentsComponent.getComponentType()
        );
        Map<String, String> currentAttachments = CompanionModelAttachmentService.resolveCurrentAttachments(npcRef, store);
        if ((attachmentsComponent == null || attachmentsComponent.getAttachmentIds().isEmpty())
                && currentAttachments.isEmpty()) {
            return null;
        }
        return ApiMapper.mapAttachments(attachmentsComponent, currentAttachments);
    }

    private boolean isValidProfileDataScope(String profileId, String namespace, String key) {
        return !isBlank(profileId) && isValidNamespace(namespace) && !isBlank(key);
    }

    private boolean isValidNamespace(String namespace) {
        if (isBlank(namespace)) {
            return false;
        }
        return !RESERVED_NAMESPACE.equalsIgnoreCase(namespace.trim());
    }

    @Nonnull
    private OwnershipPolicyView mapOwnershipPolicy(@Nonnull NpcProfileRepository.ProfileRecord profile) {
        TwCompanionConfig.EffectiveSettings settings = TwCompanionConfig.resolveEffectiveForRole(profile.roleId());
        return new OwnershipPolicyView(
                profile.profileId(),
                profile.currentNpcUuid(),
                profile.ownerUuid(),
                profile.ownerName(),
                profile.roleId(),
                Boolean.TRUE.equals(profile.tamed()),
                !isBlank(profile.coopId()) || profile.coopSlot() != null,
                profile.coopId(),
                profile.coopSlot(),
                TameworkRuntimeSettings.blockOwnerDamage(settings.isBlockOwnerDamage()),
                TameworkRuntimeSettings.blockAllPlayerDamageIfOwned(settings.isBlockAllPlayerDamageIfOwned()),
                TameworkRuntimeSettings.invulnerableIfOwned(settings.isInvulnerableIfOwned())
        );
    }

    private Optional<CommandLinkView> buildCommandLinkView(@Nullable NpcProfileRepository.ProfileRecord profile) {
        if (profile == null) {
            return Optional.empty();
        }

        LiveNpcContext liveContext = readLiveNpcContext(profile.currentNpcUuid());
        ResolvedCommandLinkState cachedState = readCachedCommandLinkState(profile.currentNpcUuid());
        ResolvedCommandLinkState persistedState = readPersistedSnapshotState(profile.profileId(), profile.activeSnapshotTypes());

        String[] resolvedToolIds = liveContext != null && !isEmpty(liveContext.toolIds())
                ? liveContext.toolIds()
                : !isEmpty(cachedState.toolIds())
                ? cachedState.toolIds()
                : profile.toolIds();
        Vector3View homePosition = firstNonNull(
                liveContext != null ? liveContext.homePosition() : null,
                cachedState.homePosition(),
                persistedState.homePosition()
        );
        Vector3View lastKnownPosition = firstNonNull(
                liveContext != null ? liveContext.currentPosition() : null,
                cachedState.lastKnownPosition(),
                persistedState.lastKnownPosition()
        );

        if (isEmpty(resolvedToolIds)
                && homePosition == null
                && lastKnownPosition == null
                && profile.activeSnapshotTypes().length == 0) {
            return Optional.empty();
        }

        return Optional.of(ApiMapper.mapCommandLink(
                profile,
                resolvedToolIds,
                homePosition,
                lastKnownPosition
        ));
    }

    private Optional<CommandLinkView> getCommandLinkByProfileId(String profileId) {
        if (isBlank(profileId)) {
            return Optional.empty();
        }
        return buildCommandLinkView(profileRepository.loadProfileById(profileId.trim()));
    }

    private Optional<CommandLinkView> getCommandLinkByNpcUuid(UUID npcUuid) {
        if (npcUuid == null) {
            return Optional.empty();
        }
        return buildCommandLinkView(profileRepository.loadProfileByNpcUuid(npcUuid));
    }

    private Set<String> listLinkedToolIdsInternal(String profileId) {
        return getCommandLinkByProfileId(profileId)
                .map(CommandLinkView::toolIds)
                .orElseGet(Set::of);
    }

    private Optional<Vector3View> getHomePositionInternal(String profileId) {
        return getCommandLinkByProfileId(profileId).map(CommandLinkView::homePosition);
    }

    private boolean hasHomePositionInternal(String profileId) {
        return getHomePositionInternal(profileId).isPresent();
    }

    @Nullable
    private LiveNpcContext readLiveNpcContext(@Nullable UUID npcUuid) {
        ResolvedLiveNpc liveNpc = resolveLiveNpc(npcUuid);
        if (liveNpc == null) {
            return null;
        }
        Vector3d currentPosition = liveNpc.transform() != null && liveNpc.transform().getPosition() != null
                ? new Vector3d(liveNpc.transform().getPosition())
                : null;
        return new LiveNpcContext(
                liveNpc.world().getName(),
                ApiMapper.mapVector(currentPosition),
                liveNpc.commandLinks() != null && liveNpc.commandLinks().hasHome()
                        ? ApiMapper.mapVector(liveNpc.commandLinks().getHomePosition())
                        : null,
                liveNpc.commandLinks() != null ? liveNpc.commandLinks().getToolIds() : null
        );
    }

    @Nullable
    private ResolvedLiveNpc resolveLiveNpc(@Nullable UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null || universe.getWorlds().isEmpty()) {
                return null;
            }
            ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = null;
            try {
                linksType = TameworkCommandLinksComponent.getComponentType();
            } catch (Throwable ignored) {
                linksType = null;
            }
            ComponentType<EntityStore, TransformComponent> transformType = null;
            try {
                transformType = TransformComponent.getComponentType();
            } catch (Throwable ignored) {
                transformType = null;
            }
            for (World world : new ArrayList<>(universe.getWorlds().values())) {
                if (world == null) {
                    continue;
                }
                Ref<EntityStore> reference = world.getEntityRef(npcUuid);
                if (reference == null || !reference.isValid()) {
                    continue;
                }
                Store<EntityStore> store = world.getEntityStore() != null ? world.getEntityStore().getStore() : null;
                if (store == null) {
                    continue;
                }
                TameworkCommandLinksComponent links = linksType != null ? store.getComponent(reference, linksType) : null;
                TransformComponent transform = transformType != null ? store.getComponent(reference, transformType) : null;
                return new ResolvedLiveNpc(
                        world,
                        reference,
                        store,
                        links,
                        transform
                );
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    @Nonnull
    private ResolvedCommandLinkState readCachedCommandLinkState(@Nullable UUID npcUuid) {
        if (npcUuid == null || stateSnapshotService == null) {
            return ResolvedCommandLinkState.empty();
        }
        CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot = stateSnapshotService.getSnapshot(npcUuid);
        if (snapshot == null) {
            return ResolvedCommandLinkState.empty();
        }
        return new ResolvedCommandLinkState(
                snapshot.toolIds(),
                ApiMapper.mapVector(snapshot.homePosition()),
                ApiMapper.mapVector(snapshot.lastKnownPosition())
        );
    }

    @Nonnull
    private ResolvedCommandLinkState readPersistedSnapshotState(@Nonnull String profileId, @Nonnull String[] snapshotTypes) {
        if (snapshotTypes.length == 0) {
            return ResolvedCommandLinkState.empty();
        }
        for (String snapshotType : COMMAND_LINK_SNAPSHOT_PRIORITY) {
            if (!contains(snapshotTypes, snapshotType)) {
                continue;
            }
            String payload = profileRepository.loadActiveSnapshotPayload(profileId, snapshotType);
            if (isBlank(payload)) {
                continue;
            }
            try {
                JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
                Vector3View homePosition = readVector(json, "homePosition");
                Vector3View lastKnownPosition = readVector(json, "lastKnownPosition");
                if (homePosition != null || lastKnownPosition != null) {
                    return new ResolvedCommandLinkState(null, homePosition, lastKnownPosition);
                }
            } catch (Exception ignored) {
                // Ignore malformed payloads and keep walking lower-priority snapshots.
            }
        }
        return ResolvedCommandLinkState.empty();
    }

    @Nullable
    private Vector3View readVector(@Nonnull JsonObject json, @Nonnull String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            return null;
        }
        JsonObject vector = json.getAsJsonObject(key);
        try {
            return new Vector3View(
                    vector.get("x").getAsDouble(),
                    vector.get("y").getAsDouble(),
                    vector.get("z").getAsDouble()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private ClaimAccessDecisionView mapClaimAccess(@Nonnull SimpleClaimsRawAccessDecision decision,
                                                   @Nullable LiveNpcContext liveContext) {
        ClaimAccessDecisionView.Status status = switch (decision.status()) {
            case ALLOWED -> ClaimAccessDecisionView.Status.ALLOWED;
            case DENIED -> ClaimAccessDecisionView.Status.DENIED;
            case ALLOW_FAIL_OPEN -> ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN;
            case SKIPPED -> ClaimAccessDecisionView.Status.SKIPPED;
            case UNAVAILABLE -> ClaimAccessDecisionView.Status.UNAVAILABLE;
        };
        return new ClaimAccessDecisionView(
                decision.available(),
                decision.allowed(),
                status,
                decision.reason(),
                decision.claimPartyId(),
                null,
                liveContext != null ? liveContext.worldName() : null,
                liveContext != null ? liveContext.currentPosition() : null,
                liveContext != null ? "live" : null
        );
    }

    @Nonnull
    private ClaimAccessDecisionView unavailableClaimDecision(@Nonnull String reason) {
        return new ClaimAccessDecisionView(
                false,
                true,
                ClaimAccessDecisionView.Status.UNAVAILABLE,
                reason,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Nonnull
    private TwGlobalConfig resolveSimpleClaimsConfig() {
        TwGlobalConfig config = TwGlobalConfig.resolveSimpleClaimsSettingsConfig();
        if (config != null) {
            return config;
        }
        TwGlobalConfig active = TwGlobalConfig.resolveActive();
        return active != null ? active : TwGlobalConfig.defaultConfig();
    }

    @Nullable
    private Vector3d toVector(@Nullable Vector3View view) {
        if (view == null) {
            return null;
        }
        return new Vector3d(view.x(), view.y(), view.z());
    }

    private <T> Optional<T> configById(@Nullable String id, @Nonnull Map<String, T> assets) {
        if (isBlank(id)) {
            return Optional.empty();
        }
        return Optional.ofNullable(assets.get(id.trim()));
    }

    @Nonnull
    private Optional<TwCompanionConfig> resolveCompanionConfig(@Nullable String roleId) {
        if (isBlank(roleId)) {
            return Optional.empty();
        }
        Optional<TwCompanionConfig> direct = resolveConfigForRole(roleId, TwCompanionConfig::resolveForRole);
        return direct.isPresent() ? direct : Optional.ofNullable(TwCompanionConfig.resolveDefaultConfig());
    }

    @Nonnull
    private <T> Optional<T> resolveConfigForRole(@Nullable String roleId,
                                                 @Nonnull Function<String, T> resolver) {
        if (isBlank(roleId)) {
            return Optional.empty();
        }
        for (String candidate : buildRoleIdCandidates(roleId)) {
            T resolved = resolver.apply(candidate);
            if (resolved != null) {
                return Optional.of(resolved);
            }
        }
        return Optional.empty();
    }

    @Nonnull
    static Set<String> buildRoleIdCandidates(@Nullable String roleId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addRoleIdCandidate(candidates, roleId);
        if (roleId == null) {
            return candidates;
        }

        String trimmed = roleId.trim();
        if (trimmed.isEmpty()) {
            return candidates;
        }

        addTranslationKeyCandidate(candidates, trimmed);

        String withoutExtension = stripJsonExtension(trimmed);
        addRoleIdCandidate(candidates, withoutExtension);

        String pathSegment = lastSegment(withoutExtension, '/', '\\');
        addRoleIdCandidate(candidates, pathSegment);
        addTranslationKeyCandidate(candidates, pathSegment);

        String namespaced = lastSegment(pathSegment, ':');
        addRoleIdCandidate(candidates, namespaced);
        addTranslationKeyCandidate(candidates, namespaced);

        if (!isTranslationKey(namespaced)) {
            addRoleIdCandidate(candidates, lastSegment(namespaced, '.'));
        }
        return candidates;
    }

    private static void addTranslationKeyCandidate(@Nonnull Set<String> candidates, @Nullable String rawValue) {
        if (rawValue == null) {
            return;
        }
        String trimmed = rawValue.trim();
        if (!isTranslationKey(trimmed)) {
            return;
        }
        int prefixLength = "npcRoles.".length();
        int suffixStart = trimmed.length() - ".name".length();
        if (suffixStart <= prefixLength) {
            return;
        }
        addRoleIdCandidate(candidates, trimmed.substring(prefixLength, suffixStart));
    }

    private static boolean isTranslationKey(@Nullable String rawValue) {
        if (rawValue == null) {
            return false;
        }
        String lower = rawValue.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("npcroles.") && lower.endsWith(".name");
    }

    private static void addRoleIdCandidate(@Nonnull Set<String> candidates, @Nullable String rawValue) {
        if (rawValue == null) {
            return;
        }
        String trimmed = rawValue.trim();
        if (!trimmed.isEmpty()) {
            candidates.add(trimmed);
        }
    }

    @Nullable
    private static String stripJsonExtension(@Nullable String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        if (trimmed.length() > 5 && trimmed.regionMatches(true, trimmed.length() - 5, ".json", 0, 5)) {
            return trimmed.substring(0, trimmed.length() - 5);
        }
        return trimmed;
    }

    @Nullable
    private static String lastSegment(@Nullable String rawValue, char... separators) {
        if (rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        int lastIndex = -1;
        for (char separator : separators) {
            lastIndex = Math.max(lastIndex, value.lastIndexOf(separator));
        }
        if (lastIndex < 0 || lastIndex + 1 >= value.length()) {
            return value;
        }
        return value.substring(lastIndex + 1).trim();
    }

    @Nonnull
    private String normalizeItemId(@Nonnull String itemId) {
        String normalized = ItemFeatureRegistry.normalizeStateItemId(itemId.trim());
        return normalized == null || normalized.isBlank() ? itemId.trim() : normalized.trim();
    }

    private boolean matchesItemId(@Nullable String configuredItemId, @Nonnull String normalizedTargetItemId) {
        if (configuredItemId == null || configuredItemId.isBlank()) {
            return false;
        }
        String normalizedConfigured = normalizeItemId(configuredItemId);
        return normalizedConfigured.equalsIgnoreCase(normalizedTargetItemId);
    }

    private boolean contains(@Nonnull String[] values, @Nonnull String expected) {
        for (String value : values) {
            if (expected.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEmpty(@Nullable String[] values) {
        return values == null || values.length == 0;
    }

    @SafeVarargs
    @Nullable
    private final <T> T firstNonNull(@Nullable T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @Nullable
    private <T extends com.hypixel.hytale.component.Component<EntityStore>> T readComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> componentType) {
        if (componentType == null) {
            return null;
        }
        return store.getComponent(reference, componentType);
    }

    @Nullable
    private TwTraitConfig resolveTraitConfig(@Nonnull Ref<EntityStore> npcRef,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull TameworkTraitsComponent component,
                                            @Nullable String roleIdFallback) {
        String roleId = firstNonBlank(CompanionRoleIdResolver.resolveRoleId(npcRef, store), roleIdFallback);
        if (!isBlank(roleId)) {
            TwTraitConfig byRole = TwTraitConfig.resolveForRole(roleId);
            if (byRole != null) {
                return byRole;
            }
        }
        String configId = normalizeBlank(component.getConfigId());
        return configId != null ? TwTraitConfig.resolveById(configId) : null;
    }

    private boolean hasLifeStageSignal(@Nullable String roleId) {
        if (isBlank(roleId)) {
            return false;
        }
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("baby") || normalized.contains("adolescent")) {
            return true;
        }
        TwBreedingConfig breedingConfig = TwBreedingConfig.resolveForRole(roleId);
        return breedingConfig != null
                && breedingConfig.isEnabled()
                && breedingConfig.resolveOffspringLifecycle(roleId).isEnabled();
    }

    @Nullable
    private static String firstNonBlank(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalizeBlank(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    @Nullable
    private static String normalizeBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ResolvedCommandLinkState(@Nullable String[] toolIds,
                                            @Nullable Vector3View homePosition,
                                            @Nullable Vector3View lastKnownPosition) {
        @Nonnull
        private static ResolvedCommandLinkState empty() {
            return new ResolvedCommandLinkState(null, null, null);
        }
    }

    private record LiveNpcContext(@Nullable String worldName,
                                  @Nullable Vector3View currentPosition,
                                  @Nullable Vector3View homePosition,
                                  @Nullable String[] toolIds) {
    }

    private record ResolvedProgressionTarget(@Nullable String profileId,
                                             @Nullable String profileRoleId,
                                             @Nonnull UUID npcUuid,
                                             @Nonnull ResolvedLiveNpc liveNpc) {
        @Nonnull
        private Ref<EntityStore> reference() {
            return liveNpc.reference();
        }

        @Nonnull
        private Store<EntityStore> store() {
            return liveNpc.store();
        }

        @Nullable
        private String resolvedRoleId() {
            return firstNonBlank(
                    CompanionRoleIdResolver.resolveRoleId(liveNpc.reference(), liveNpc.store()),
                    profileRoleId
            );
        }
    }

    private record ResolvedLiveNpc(@Nonnull World world,
                                   @Nonnull Ref<EntityStore> reference,
                                   @Nonnull Store<EntityStore> store,
                                   @Nullable TameworkCommandLinksComponent commandLinks,
                                   @Nullable TransformComponent transform) {
    }
}
