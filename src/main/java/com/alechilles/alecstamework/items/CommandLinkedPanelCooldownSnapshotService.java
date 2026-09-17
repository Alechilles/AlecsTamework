package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.ExecutionContext;
import com.hypixel.hytale.server.npc.util.expression.Scope;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import javax.annotation.Nullable;

/**
 * Reads compact linked-panel cooldown snapshots from loaded companion state.
 */
final class CommandLinkedPanelCooldownSnapshotService {
    private static final String DEFAULT_HARVEST_ALARM_NAME = "Harvest_Ready";
    private final StdScopeLookupCache scopeLookupCache = new StdScopeLookupCache();

    @Nullable
    CooldownSnapshot readBreedingCooldownSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                  @Nullable Store<EntityStore> store,
                                                  @Nullable String resolvedRoleId) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        boolean availableByConfig = isBreedingAvailableForRole(resolvedRoleId);
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return new CooldownSnapshot(false, availableByConfig, false, false, 0L, 0.0);
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
        if (breeding == null) {
            return new CooldownSnapshot(false, availableByConfig, false, false, 0L, 0.0);
        }
        long now = com.alechilles.alecstamework.npc.progression.AnimalProgressionService.currentTimeMs(npcRef, store);
        long until = breeding.getCooldownUntilMs();
        boolean active = until != 0L && now < until;
        if (!active) {
            return new CooldownSnapshot(true, true, breeding.isEnabled(), false, 0L, 1.0);
        }
        long remainingGameMs = BreedingTimeService.remainingDurationMs(until, now);
        long remainingRealMs = BreedingTimeService.toEstimatedRealDurationMs(remainingGameMs, store);
        double ratio = resolveBreedingCooldownRatio(breeding, npcRef, store, resolvedRoleId, remainingGameMs);
        return new CooldownSnapshot(true, true, breeding.isEnabled(), true, remainingRealMs, ratio);
    }

    @Nullable
    CooldownSnapshot readHarvestCooldownSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                 @Nullable Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return null;
        }
        if (!hasEnabledHarvestCapability(npcRef, store)) {
            return new CooldownSnapshot(false, false, false, false, 0L, 0.0);
        }
        TameworkAlarmService.Snapshot snapshot = TameworkAlarmService.snapshot(npcRef, store, resolveHarvestAlarmName());
        return fromAlarmSnapshot(snapshot, store);
    }

    /**
     * An absent alarm means a supported animal is ready; it must not make every companion
     * advertise harvest readiness. This matches the interaction route's role config and
     * harvestability parameter before consulting the shared alarm.
     */
    private boolean hasEnabledHarvestCapability(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        if (globalConfig == null) {
            globalConfig = TwGlobalConfig.defaultConfig();
        }
        StdScope roleScope = NpcSupportAccess.sensorScope(npc.getRole(), npcRef, store);
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        // Root role parameters are not necessarily exported to the live sensor scope.
        StdScope parameters = resolveRoleParameterScope(roleId);
        return hasEnabledHarvestCapability(
                resolveInteractionConfig(roleId, globalConfig, roleScope, parameters),
                roleScope, parameters, globalConfig.getIsHarvestableParam());
    }

    /** Resolves saved-card capability without accessing another world's entity. */
    boolean hasEnabledHarvestCapability(@Nullable String roleId) {
        TwGlobalConfig globalConfig = TwGlobalConfig.resolveActive();
        if (globalConfig == null) return false;
        StdScope parameters = resolveRoleParameterScope(roleId);
        return hasEnabledHarvestCapability(resolveInteractionConfig(roleId, globalConfig, null, parameters),
                null, parameters, globalConfig.getIsHarvestableParam());
    }

    boolean hasEnabledHarvestCapability(@Nullable TwInteractionConfig config,
                                        @Nullable StdScope sensorScope,
                                        @Nullable StdScope parameters,
                                        String harvestableParam) {
        Boolean harvestable = scopeLookupCache.getBoolean(sensorScope, harvestableParam);
        if (harvestable == null) {
            harvestable = scopeLookupCache.getBoolean(parameters, harvestableParam);
        }
        return hasEnabledHarvestInteraction(config, Boolean.TRUE.equals(harvestable));
    }

    @Nullable
    private static StdScope resolveRoleParameterScope(@Nullable String roleId) {
        NPCPlugin plugin = NPCPlugin.get();
        if (plugin == null || roleId == null || roleId.isBlank()) {
            return null;
        }
        int roleIndex = plugin.getIndex(roleId);
        Builder<Role> builder = roleIndex >= 0 ? plugin.tryGetCachedValidRole(roleIndex) : null;
        return resolveRoleParameterScope(builder);
    }

    @Nullable
    static StdScope resolveRoleParameterScope(@Nullable Builder<Role> builder) {
        try {
            // Computed Modify values read the variant's execution parameters first.
            if (builder instanceof BuilderRoleVariant variant) {
                ExecutionContext context = new ExecutionContext();
                context.setScope(variant.createExecutionScope());
                Scope scope = variant.createModifierScope(context);
                return scope instanceof StdScope standard ? standard : scope != null ? new StdScope(scope) : null;
            }
            BuilderParameters parameters = builder != null ? builder.getBuilderParameters() : null;
            return parameters != null ? parameters.createScope() : null;
        } catch (RuntimeException exception) {
            // Optional presentation data must not terminate the owning world's tick.
            return null;
        }
    }

    @Nullable
    private TwInteractionConfig resolveInteractionConfig(@Nullable String roleId,
                                                         TwGlobalConfig globalConfig,
                                                         @Nullable StdScope roleScope,
                                                         @Nullable StdScope parameters) {
        String configuredId = scopeLookupCache.getString(roleScope, globalConfig.getInteractionConfigParam());
        if (configuredId == null) {
            configuredId = scopeLookupCache.getString(parameters, globalConfig.getInteractionConfigParam());
        }
        if (configuredId != null && !configuredId.isBlank()) {
            DefaultAssetMap<String, TwInteractionConfig> assetMap = TwInteractionConfig.getAssetMap();
            return assetMap != null ? assetMap.getAssetMap().get(configuredId) : null;
        }
        return TwInteractionConfig.resolveForRole(roleId);
    }

    static boolean hasEnabledHarvestInteraction(@Nullable TwInteractionConfig config,
                                                boolean isHarvestable) {
        if (config == null || !config.isEnabled()) {
            return false;
        }
        for (TwInteractionConfig.InteractionEntry entry : config.getInteractions()) {
            if (!(entry instanceof TwInteractionConfig.HarvestInteraction harvest) || !entry.isEnabled()) {
                continue;
            }
            Boolean requiresHarvestable = harvest.getRequireHarvestable();
            if (Boolean.FALSE.equals(requiresHarvestable) || isHarvestable) {
                return true;
            }
        }
        return false;
    }

    static CooldownSnapshot fromAlarmWindow(boolean known,
                                           boolean active,
                                           long nowMs,
                                           long untilMs,
                                           long startedAtMs,
                                           long durationMs,
                                           @Nullable Store<EntityStore> store) {
        if (!known) {
            return new CooldownSnapshot(false, true, true, false, 0L, 0.0);
        }
        if (!active) {
            return new CooldownSnapshot(true, true, true, false, 0L, 1.0);
        }
        long remainingGameMs = BreedingTimeService.remainingDurationMs(untilMs, nowMs);
        long remainingRealMs = BreedingTimeService.toEstimatedRealDurationMs(remainingGameMs, store);
        double ratio = resolveCooldownRatio(remainingGameMs, startedAtMs, durationMs, untilMs);
        return new CooldownSnapshot(true, true, true, true, remainingRealMs, ratio);
    }

    private static CooldownSnapshot fromAlarmSnapshot(@Nullable TameworkAlarmService.Snapshot snapshot,
                                                     @Nullable Store<EntityStore> store) {
        if (snapshot == null || !snapshot.valid) {
            return new CooldownSnapshot(false, true, true, false, 0L, 0.0);
        }
        return fromAlarmWindow(
                true,
                snapshot.active,
                snapshot.nowMs,
                snapshot.untilMs,
                snapshot.startedAtMs,
                snapshot.durationMs,
                store
        );
    }

    private double resolveBreedingCooldownRatio(@Nullable TameworkBreedingComponent breeding,
                                                @Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nullable String resolvedRoleId,
                                                long remainingMs) {
        long knownDurationMs = 0L;
        if (breeding != null) {
            knownDurationMs = Math.max(0L, breeding.getCooldownDurationMs());
            if (knownDurationMs <= 0L) {
                knownDurationMs = resolveWindowDurationMs(
                        breeding.getCooldownStartedAtMs(),
                        breeding.getCooldownUntilMs()
                );
            }
        }
        if (knownDurationMs > 0L) {
            return clamp(1.0 - ((double) remainingMs / (double) knownDurationMs));
        }

        TwBreedingConfig config = null;
        if (breeding != null && breeding.getConfigId() != null && !breeding.getConfigId().isBlank()) {
            config = TwBreedingConfig.resolveById(breeding.getConfigId());
        }
        String roleId = resolvedRoleId;
        if (roleId == null || roleId.isBlank()) {
            roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        }
        if (config == null) {
            config = TwBreedingConfig.resolveForRole(roleId);
        }
        if (config == null || config.resolveCooldowns(roleId) == null || config.resolveTiming(roleId) == null) {
            return 0.0;
        }
        long baseDurationMs = BreedingTimeService.toGameDurationMs(
                config.resolveCooldowns(roleId).getBaseCooldownSeconds(),
                config.resolveTiming(roleId).getTimerBasis(),
                store
        );
        if (baseDurationMs <= 0L) {
            return 0.0;
        }
        return clamp(1.0 - ((double) remainingMs / (double) baseDurationMs));
    }

    private boolean isBreedingAvailableForRole(@Nullable String resolvedRoleId) {
        TwBreedingConfig config = TwBreedingConfig.resolveForRole(resolvedRoleId);
        return config != null && config.isEnabled();
    }

    private static double resolveCooldownRatio(long remainingMs, long startedAtMs, long durationMs, long untilMs) {
        long knownDurationMs = Math.max(0L, durationMs);
        if (knownDurationMs <= 0L) {
            knownDurationMs = resolveWindowDurationMs(startedAtMs, untilMs);
        }
        if (knownDurationMs <= 0L) {
            return 0.0;
        }
        return clamp(1.0 - ((double) remainingMs / (double) knownDurationMs));
    }

    private static long resolveWindowDurationMs(long startedAtMs, long untilMs) {
        return startedAtMs != 0L && untilMs != 0L && untilMs > startedAtMs
                ? BreedingTimeService.saturatingSubtract(untilMs, startedAtMs)
                : 0L;
    }

    static String resolveHarvestAlarmName() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank() ? configured : DEFAULT_HARVEST_ALARM_NAME;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    static final class CooldownSnapshot {
        final boolean known;
        final boolean available;
        final boolean enabled;
        final boolean active;
        final long remainingMs;
        final double ratio;

        private CooldownSnapshot(boolean known,
                                 boolean available,
                                 boolean enabled,
                                 boolean active,
                                 long remainingMs,
                                 double ratio) {
            this.known = known;
            this.available = available;
            this.enabled = enabled;
            this.active = active;
            this.remainingMs = Math.max(0L, remainingMs);
            this.ratio = clamp(ratio);
        }
    }
}
