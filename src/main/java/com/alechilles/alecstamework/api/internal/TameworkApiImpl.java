package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ClaimAccessDecisionView;
import com.alechilles.alecstamework.api.CommandItemConfigView;
import com.alechilles.alecstamework.api.CommandLinkView;
import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.api.GlobalConfigView;
import com.alechilles.alecstamework.api.InteractionConfigView;
import com.alechilles.alecstamework.api.NameItemConfigView;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.OwnershipPolicyView;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.RoleScopedConfigView;
import com.alechilles.alecstamework.api.SpawnerConfigView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCompanionConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwNameItemConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwSpawnerConfig;
import com.alechilles.alecstamework.config.assets.TwTraitConfig;
import com.alechilles.alecstamework.integration.simpleclaims.SimpleClaimsBreedingBridge;
import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.alechilles.alecstamework.items.CommandLinkedNpcStateSnapshotService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.alechilles.alecstamework.persistence.sqlite.ApiProfileDataRepository;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

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
        implements TameworkApi, NpcProfilesApi, ProfileDataApi, TameworkConfigReadApi, PolicyApi, DiagnosticsApi {
    static final String API_VERSION = "0.1.0";
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
    private final SimpleClaimsBreedingBridge simpleClaimsBridge;
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
    private final EnumSet<TameworkApiCapability> capabilities = EnumSet.of(
            TameworkApiCapability.PROFILES,
            TameworkApiCapability.COMMAND_LINKS,
            TameworkApiCapability.POLICY,
            TameworkApiCapability.PROFILE_DATA,
            TameworkApiCapability.EVENTS,
            TameworkApiCapability.CONFIG_READ,
            TameworkApiCapability.DIAGNOSTICS
    );
    private final Gson gson = new Gson();

    public TameworkApiImpl(@Nonnull TameworkPersistenceRuntime persistenceRuntime,
                           @Nonnull TameworkEventBus eventBus,
                           @Nullable CommandLinkedNpcStateSnapshotService stateSnapshotService) {
        this.persistenceRuntime = Objects.requireNonNull(persistenceRuntime);
        this.profileRepository = Objects.requireNonNull(persistenceRuntime.getNpcProfileRepository());
        this.profileDataRepository = Objects.requireNonNull(persistenceRuntime.getApiProfileDataRepository());
        this.eventBus = Objects.requireNonNull(eventBus);
        this.stateSnapshotService = stateSnapshotService;
        this.simpleClaimsBridge = SimpleClaimsBreedingBridge.initialize();
    }

    @Override
    public String getApiVersion() {
        return API_VERSION;
    }

    @Override
    public EnumSet<TameworkApiCapability> getCapabilities() {
        return capabilities.clone();
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
    public PolicyApi policies() {
        return this;
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
        if (!globalConfig.isSimpleClaimsEnabled()) {
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
        if (liveContext == null || isBlank(liveContext.worldName()) || liveContext.currentPosition() == null) {
            return unavailableClaimDecision("live-claim-context-missing");
        }
        if (!simpleClaimsBridge.isAvailable()) {
            return new ClaimAccessDecisionView(
                    false,
                    true,
                    ClaimAccessDecisionView.Status.UNAVAILABLE,
                    simpleClaimsBridge.getUnavailableReason(),
                    null,
                    null,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
        }

        SimpleClaimsBreedingBridge.LookupResult lookup = simpleClaimsBridge.lookupClaim(
                liveContext.worldName(),
                toVector(liveContext.currentPosition())
        );
        if (lookup.status() == SimpleClaimsBreedingBridge.LookupStatus.NO_CLAIM) {
            return new ClaimAccessDecisionView(
                    true,
                    true,
                    ClaimAccessDecisionView.Status.ALLOWED,
                    "outside-claim",
                    null,
                    null,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
        }
        if (lookup.status() == SimpleClaimsBreedingBridge.LookupStatus.UNAVAILABLE) {
            return new ClaimAccessDecisionView(
                    false,
                    true,
                    ClaimAccessDecisionView.Status.UNAVAILABLE,
                    lookup.message(),
                    null,
                    null,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
        }
        if (lookup.status() == SimpleClaimsBreedingBridge.LookupStatus.ERROR) {
            return new ClaimAccessDecisionView(
                    true,
                    true,
                    ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN,
                    lookup.message(),
                    null,
                    null,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
        }

        SimpleClaimsBreedingBridge.ClaimInfo claimInfo = lookup.claimInfo();
        SimpleClaimsBreedingBridge.DamageAccessResult accessResult = simpleClaimsBridge.evaluateDamageAccess(
                liveContext.worldName(),
                toVector(liveContext.currentPosition()),
                playerUuid,
                globalConfig.getSimpleClaimsDamageAllowDamagePermissionKey()
        );
        return mapClaimAccess(accessResult, claimInfo, liveContext);
    }

    @Nonnull
    @Override
    public DamagePolicyDecisionView evaluateDamage(String profileId, @Nullable UUID attackerPlayerUuid) {
        Optional<OwnershipPolicyView> ownership = getOwnershipByProfileId(profileId);
        if (ownership.isEmpty()) {
            OwnershipPolicyView missing = new OwnershipPolicyView(
                    profileId == null ? "" : profileId,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    null,
                    false,
                    false,
                    false
            );
            return new DamagePolicyDecisionView(
                    profileId == null ? "" : profileId,
                    attackerPlayerUuid,
                    true,
                    DamagePolicyDecisionView.Status.UNAVAILABLE,
                    "profile-not-found",
                    missing,
                    null
            );
        }

        OwnershipPolicyView policy = ownership.get();
        if (policy.ownerUuid() != null && policy.invulnerableIfOwned()) {
            return new DamagePolicyDecisionView(
                    policy.profileId(),
                    attackerPlayerUuid,
                    false,
                    DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION,
                    "invulnerable-if-owned",
                    policy,
                    null
            );
        }
        if (policy.ownerUuid() != null && attackerPlayerUuid != null) {
            if (policy.blockAllPlayerDamageIfOwned()) {
                return new DamagePolicyDecisionView(
                        policy.profileId(),
                        attackerPlayerUuid,
                        false,
                        DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION,
                        "block-all-player-damage-if-owned",
                        policy,
                        null
                );
            }
            if (policy.blockOwnerDamage() && attackerPlayerUuid.equals(policy.ownerUuid())) {
                return new DamagePolicyDecisionView(
                        policy.profileId(),
                        attackerPlayerUuid,
                        false,
                        DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION,
                        "block-owner-damage",
                        policy,
                        null
                );
            }
        }

        ClaimAccessDecisionView claimAccess = evaluateClaimAccess(policy.profileId(), attackerPlayerUuid);
        if (!claimAccess.allowed()) {
            return new DamagePolicyDecisionView(
                    policy.profileId(),
                    attackerPlayerUuid,
                    false,
                    DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                    claimAccess.reason() != null ? claimAccess.reason() : "claim-protection-denied",
                    policy,
                    claimAccess
            );
        }
        if (claimAccess.status() == ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN) {
            return new DamagePolicyDecisionView(
                    policy.profileId(),
                    attackerPlayerUuid,
                    true,
                    DamagePolicyDecisionView.Status.ALLOWED_FAIL_OPEN,
                    claimAccess.reason() != null ? claimAccess.reason() : "claim-lookup-failed-open",
                    policy,
                    claimAccess
            );
        }
        if (claimAccess.status() == ClaimAccessDecisionView.Status.SKIPPED
                || claimAccess.status() == ClaimAccessDecisionView.Status.UNAVAILABLE) {
            return new DamagePolicyDecisionView(
                    policy.profileId(),
                    attackerPlayerUuid,
                    true,
                    DamagePolicyDecisionView.Status.ALLOWED_SKIPPED,
                    claimAccess.reason() != null ? claimAccess.reason() : "claim-check-skipped",
                    policy,
                    claimAccess
            );
        }
        return new DamagePolicyDecisionView(
                policy.profileId(),
                attackerPlayerUuid,
                true,
                DamagePolicyDecisionView.Status.ALLOWED,
                "allowed",
                policy,
                claimAccess
        );
    }

    @Nonnull
    @Override
    public PopulationCapDecisionView evaluatePopulationCap(@Nullable UUID ownerUuid) {
        OwnerPopulationCapService.Decision decision = OwnerPopulationCapService.evaluateAcquisition(null, ownerUuid);
        return new PopulationCapDecisionView(
                ownerUuid,
                decision.allowed(),
                decision.capEnabled(),
                decision.limit(),
                decision.currentCount(),
                decision.remainingHeadroom(),
                decision.scope() != null ? decision.scope().name() : null,
                decision.reason()
        );
    }

    @Nonnull
    @Override
    public PersistenceDiagnosticsView getPersistenceDiagnostics() {
        return ApiMapper.mapPersistenceDiagnostics(persistenceRuntime.collectDiagnostics());
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
                settings.isBlockOwnerDamage(),
                settings.isBlockAllPlayerDamageIfOwned(),
                settings.isInvulnerableIfOwned()
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
                Vector3d currentPosition = transform != null && transform.getPosition() != null
                        ? new Vector3d(transform.getPosition())
                        : null;
                return new LiveNpcContext(
                        world.getName(),
                        ApiMapper.mapVector(currentPosition),
                        links != null && links.hasHome() ? ApiMapper.mapVector(links.getHomePosition()) : null,
                        links != null ? links.getToolIds() : null
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
    private ClaimAccessDecisionView mapClaimAccess(@Nonnull SimpleClaimsBreedingBridge.DamageAccessResult accessResult,
                                                   @Nullable SimpleClaimsBreedingBridge.ClaimInfo claimInfo,
                                                   @Nonnull LiveNpcContext liveContext) {
        UUID claimPartyId = claimInfo != null ? claimInfo.partyId() : accessResult.claimPartyId();
        Integer claimChunkCount = claimInfo != null ? claimInfo.claimChunkCount() : null;
        return switch (accessResult.status()) {
            case ALLOWED -> new ClaimAccessDecisionView(
                    true,
                    true,
                    ClaimAccessDecisionView.Status.ALLOWED,
                    accessResult.message(),
                    claimPartyId,
                    claimChunkCount,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
            case DENIED -> new ClaimAccessDecisionView(
                    true,
                    false,
                    ClaimAccessDecisionView.Status.DENIED,
                    accessResult.message(),
                    claimPartyId,
                    claimChunkCount,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
            case LOOKUP_ERROR -> new ClaimAccessDecisionView(
                    true,
                    true,
                    ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN,
                    accessResult.message(),
                    claimPartyId,
                    claimChunkCount,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
            case UNAVAILABLE -> new ClaimAccessDecisionView(
                    false,
                    true,
                    ClaimAccessDecisionView.Status.UNAVAILABLE,
                    accessResult.message(),
                    claimPartyId,
                    claimChunkCount,
                    liveContext.worldName(),
                    liveContext.currentPosition(),
                    "live"
            );
        };
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

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
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
}
