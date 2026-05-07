package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CommandItemConfigView;
import com.alechilles.alecstamework.api.CommandLinkView;
import com.alechilles.alecstamework.api.GlobalConfigView;
import com.alechilles.alecstamework.api.InteractionConfigView;
import com.alechilles.alecstamework.api.NameItemConfigView;
import com.alechilles.alecstamework.api.NpcProfileView;
import com.alechilles.alecstamework.api.PersistenceDiagnosticsView;
import com.alechilles.alecstamework.api.ProgressionView;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.api.RoleScopedConfigView;
import com.alechilles.alecstamework.api.SpawnerConfigView;
import com.alechilles.alecstamework.api.Vector3View;
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
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessModifierService;
import com.alechilles.alecstamework.npc.progression.CompanionHappinessService;
import com.alechilles.alecstamework.persistence.sqlite.NpcProfileRepository;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceWriteQueue;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class ApiMapper {
    private static final Gson DETACHED_DETAILS_GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes fieldAttributes) {
                    if (fieldAttributes == null) {
                        return false;
                    }
                    if ("data".equals(fieldAttributes.getName())) {
                        return true;
                    }
                    Class<?> declaredClass = fieldAttributes.getDeclaredClass();
                    return declaredClass != null
                            && "com.hypixel.hytale.assetstore.AssetExtraInfo$Data".equals(declaredClass.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return clazz != null
                            && "com.hypixel.hytale.assetstore.AssetExtraInfo$Data".equals(clazz.getName());
                }
            })
            .registerTypeHierarchyAdapter(Class.class, (JsonSerializer<Class<?>>) (src, typeOfSrc, context) ->
                    src == null ? null : new JsonPrimitive(src.getName()))
            .create();

    private ApiMapper() {
    }

    @Nonnull
    static NpcProfileView mapProfile(@Nonnull NpcProfileRepository.ProfileRecord profile) {
        return new NpcProfileView(
                profile.profileId(),
                profile.currentNpcUuid(),
                profile.ownerUuid(),
                profile.ownerName(),
                profile.roleId(),
                profile.displayName(),
                profile.customName(),
                Boolean.TRUE.equals(profile.tamed()),
                profile.coopId(),
                profile.coopSlot(),
                toOrderedSet(profile.toolIds()),
                toOrderedSet(profile.activeSnapshotTypes()),
                profile.updatedAtMs()
        );
    }

    @Nonnull
    static CommandLinkView mapCommandLink(@Nonnull NpcProfileRepository.ProfileRecord profile,
                                          @Nullable String[] toolIds,
                                          @Nullable Vector3View homePosition,
                                          @Nullable Vector3View lastKnownPosition) {
        LinkedHashSet<String> resolvedToolIds = toOrderedSet(toolIds);
        if (resolvedToolIds.isEmpty()) {
            resolvedToolIds = toOrderedSet(profile.toolIds());
        }
        return new CommandLinkView(
                profile.profileId(),
                profile.currentNpcUuid(),
                profile.ownerUuid(),
                resolvedToolIds,
                homePosition != null,
                homePosition,
                lastKnownPosition,
                toOrderedSet(profile.activeSnapshotTypes()),
                profile.updatedAtMs()
        );
    }

    @Nonnull
    static ProgressionView.HappinessView mapHappiness(@Nullable String configId,
                                                      long lastUpdateMs,
                                                      @Nonnull String source,
                                                      @Nonnull CompanionHappinessService.HappinessSnapshot snapshot) {
        List<ProgressionView.ModifierEntryView> modifiers = snapshot.modifiers().stream()
                .filter(Objects::nonNull)
                .map(ApiMapper::mapModifierEntry)
                .toList();
        return new ProgressionView.HappinessView(
                configId,
                snapshot.value(),
                snapshot.min(),
                snapshot.max(),
                lastUpdateMs,
                source,
                snapshot.baseSetpoint(),
                snapshot.target(),
                modifiers
        );
    }

    @Nonnull
    static ProgressionView.NeedsView mapNeeds(@Nonnull TameworkNeedsComponent component,
                                              @Nullable TwNeedsConfig config) {
        Integer hungerPercent = null;
        Integer thirstPercent = null;
        if (config != null) {
            TwNeedsConfig.ValueSettings values = config.getValues();
            hungerPercent = percent(component.getHunger(), values.getHungerMin(), values.getHungerMax());
            thirstPercent = percent(component.getThirst(), values.getThirstMin(), values.getThirstMax());
        }
        return new ProgressionView.NeedsView(
                component.getConfigId(),
                component.getHunger(),
                component.getThirst(),
                hungerPercent,
                thirstPercent,
                component.getAppliedHappinessPenalty(),
                component.getLastUpdateMs(),
                component.getLastPassiveSweepMs()
        );
    }

    @Nonnull
    static ProgressionView.BreedingView mapBreeding(@Nullable String configId,
                                                    @Nonnull TameworkBreedingComponent component,
                                                    long nowMs,
                                                    @Nullable Double effectiveHappiness,
                                                    @Nullable Double threshold,
                                                    @Nullable Boolean eligible,
                                                    double fertilityMultiplier) {
        boolean cooldownActive = component.isCooldownActive(nowMs);
        long cooldownRemainingMs = cooldownActive
                ? Math.max(0L, component.getCooldownUntilMs() - nowMs)
                : 0L;
        return new ProgressionView.BreedingView(
                configId,
                component.isEnabled(),
                component.isReady(),
                component.isReady() && !cooldownActive,
                cooldownActive,
                component.getCooldownUntilMs(),
                component.getCooldownStartedAtMs(),
                component.getCooldownDurationMs(),
                cooldownRemainingMs,
                component.getLastPartnerUuid(),
                component.getHappiness(),
                component.getLastHappinessUpdateMs(),
                effectiveHappiness,
                threshold,
                eligible,
                fertilityMultiplier
        );
    }

    @Nonnull
    static ProgressionView.LifeStageView mapLifeStage(@Nonnull String stage,
                                                      boolean adult,
                                                      double currentScale,
                                                      @Nullable TameworkLifeStageComponent component) {
        return new ProgressionView.LifeStageView(
                stage,
                adult,
                component != null && component.isGrowthScalingEnabled(),
                currentScale,
                component != null ? component.getBornAtMs() : 0L,
                component != null ? component.getAdolescentAtMs() : 0L,
                component != null ? component.getAdultAtMs() : 0L,
                component != null ? component.getFullyGrownAtMs() : 0L,
                component != null ? component.getBabyScale() : 0.0,
                component != null ? component.getAdolescentScale() : 0.0,
                component != null ? component.getAdolescentSwitchScale() : 0.0,
                component != null ? component.getAdultStartScale() : 0.0,
                component != null ? component.getAdultSwitchScale() : 0.0,
                component != null ? component.getAdultScale() : 0.0,
                component != null ? component.getAdultRoleId() : null,
                component != null ? component.getBabyRoleId() : null,
                component != null ? component.getAdolescentRoleId() : null,
                component != null ? component.getGender() : null
        );
    }

    @Nonnull
    static ProgressionView.TraitsView mapTraits(@Nonnull TameworkTraitsComponent component,
                                                @Nullable TwTraitConfig config) {
        LinkedHashMap<String, String> effectKeys = new LinkedHashMap<>();
        if (config != null) {
            for (TwTraitConfig.TraitDefinition definition : config.getTraits()) {
                if (definition == null || definition.getId() == null || definition.getId().isBlank()) {
                    continue;
                }
                effectKeys.putIfAbsent(definition.getId().trim().toLowerCase(), definition.getEffectKey());
            }
        }
        List<ProgressionView.TraitValueView> values = Arrays.stream(component.getTraitValues())
                .filter(Objects::nonNull)
                .filter(value -> value.getId() != null && !value.getId().isBlank())
                .map(value -> new ProgressionView.TraitValueView(
                        value.getId(),
                        value.getValue(),
                        effectKeys.get(value.getId().trim().toLowerCase())
                ))
                .toList();
        return new ProgressionView.TraitsView(
                component.getConfigId(),
                component.getRollSeed(),
                values
        );
    }

    @Nonnull
    static ProgressionView.AttachmentsView mapAttachments(@Nullable TameworkAttachmentsComponent component,
                                                          @Nullable java.util.Map<String, String> currentAttachmentIds) {
        LinkedHashMap<String, String> stored = new LinkedHashMap<>();
        if (component != null && component.getAttachmentIds() != null) {
            stored.putAll(component.getAttachmentIds());
        }
        LinkedHashMap<String, String> current = new LinkedHashMap<>();
        if (currentAttachmentIds != null) {
            current.putAll(currentAttachmentIds);
        }
        return new ProgressionView.AttachmentsView(
                component != null ? component.getConfigId() : null,
                Collections.unmodifiableMap(stored),
                Collections.unmodifiableMap(current)
        );
    }

    @Nonnull
    static EnumSet<ProfileChangeType> diffProfileChanges(@Nullable NpcProfileRepository.ProfileRecord before,
                                                         @Nullable NpcProfileRepository.ProfileRecord after) {
        EnumSet<ProfileChangeType> changes = EnumSet.noneOf(ProfileChangeType.class);
        if (before == null && after == null) {
            return changes;
        }
        if (before == null) {
            changes.add(ProfileChangeType.CREATED);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::currentNpcUuid),
                value(after, NpcProfileRepository.ProfileRecord::currentNpcUuid))) {
            changes.add(ProfileChangeType.CURRENT_NPC_UUID);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::ownerUuid),
                value(after, NpcProfileRepository.ProfileRecord::ownerUuid))
                || !Objects.equals(value(before, NpcProfileRepository.ProfileRecord::ownerName),
                value(after, NpcProfileRepository.ProfileRecord::ownerName))) {
            changes.add(ProfileChangeType.OWNER);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::roleId),
                value(after, NpcProfileRepository.ProfileRecord::roleId))) {
            changes.add(ProfileChangeType.ROLE);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::displayName),
                value(after, NpcProfileRepository.ProfileRecord::displayName))) {
            changes.add(ProfileChangeType.DISPLAY_NAME);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::customName),
                value(after, NpcProfileRepository.ProfileRecord::customName))) {
            changes.add(ProfileChangeType.CUSTOM_NAME);
        }
        if (!Objects.equals(Boolean.TRUE.equals(value(before, NpcProfileRepository.ProfileRecord::tamed)),
                Boolean.TRUE.equals(value(after, NpcProfileRepository.ProfileRecord::tamed)))) {
            changes.add(ProfileChangeType.TAMED);
        }
        if (!Objects.equals(value(before, NpcProfileRepository.ProfileRecord::coopId),
                value(after, NpcProfileRepository.ProfileRecord::coopId))
                || !Objects.equals(value(before, NpcProfileRepository.ProfileRecord::coopSlot),
                value(after, NpcProfileRepository.ProfileRecord::coopSlot))) {
            changes.add(ProfileChangeType.COOP_ASSIGNMENT);
        }
        if (!toOrderedSet(value(before, NpcProfileRepository.ProfileRecord::toolIds))
                .equals(toOrderedSet(value(after, NpcProfileRepository.ProfileRecord::toolIds)))) {
            changes.add(ProfileChangeType.TOOL_LINKS);
        }
        if (!toOrderedSet(value(before, NpcProfileRepository.ProfileRecord::activeSnapshotTypes))
                .equals(toOrderedSet(value(after, NpcProfileRepository.ProfileRecord::activeSnapshotTypes)))) {
            changes.add(ProfileChangeType.ACTIVE_SNAPSHOTS);
        }
        return changes;
    }

    @Nonnull
    static GlobalConfigView mapGlobalConfig(@Nonnull TwGlobalConfig config) {
        return new GlobalConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                new GlobalConfigView.OwnershipProtectionView(
                        config.isBlockOwnerDamage(),
                        config.isBlockAllPlayerDamageIfOwned(),
                        config.isInvulnerableIfOwned()
                ),
                new GlobalConfigView.OwnershipRequirementsView(
                        config.isOwnershipCaptureRequiresOwner(),
                        config.isOwnershipSpawnRequiresOwner(),
                        config.isOwnershipInteractionRequiresOwner(),
                        config.isOwnershipLinkingRequiresOwner()
                ),
                new GlobalConfigView.InteractionDefaultsView(
                        config.getInteractionConfigParam(),
                        config.getLovedItemsParam(),
                        config.getIsHarvestableParam(),
                        config.getIsMountableParam(),
                        config.getHarvestContextParam(),
                        config.getHarvestAlarmName(),
                        config.getInteractionCooldownAlarmPrefix()
                ),
                new GlobalConfigView.CommandView(
                        config.getCommandReturnHomeTeleportDistance(),
                        config.getCommandReturnHomePathDistanceBeforeTeleport(),
                        config.getCommandReturnHomeTeleportDelayMs(),
                        config.getCommandRecallSafeSpawnDistance(),
                        config.getCommandRecallForceRelocateDistance(),
                        config.getCommandRelocationRetryIntervalMs(),
                        config.getCommandRelocationMaxWaitMs(),
                        config.getCommandRelocationMaxRetryAttempts(),
                        config.isCommandDeadRespawnEnabled(),
                        config.getCommandDeadRespawnCooldownMs(),
                        config.getCommandDeadRespawnFollowRetryDelayMs(),
                        config.getCommandDeadRespawnDistanceClose(),
                        config.getCommandDeadRespawnDistanceNear(),
                        config.getCommandDeadRespawnDistanceMid(),
                        config.getCommandDeadRespawnDistanceFar(),
                        config.getCommandPlacementMinRelativeY(),
                        config.getCommandPlacementMaxRelativeY(),
                        config.isCommandLinkedPanelRequireUnlinkConfirm()
                ),
                new GlobalConfigView.AssetSetsView(
                        config.isTranquilizerShortbowAssetSetEnabled(),
                        config.isTranquilizerArrowAssetSetEnabled(),
                        config.isTranquilizerPotionAssetSetEnabled(),
                        config.isFeedTroughAssetSetEnabled(),
                        config.isHerbivoreFeedAssetSetEnabled(),
                        config.isCarnivoreFeedAssetSetEnabled()
                ),
                new GlobalConfigView.PopulationView(
                        config.getPopulationLimitPerPlayerOwnedTotal(),
                        config.getPopulationPerPlayerLimitScope().configValue()
                ),
                new GlobalConfigView.SimpleClaimsView(
                        config.isSimpleClaimsEnabled(),
                        new GlobalConfigView.SimpleClaimsView.BreedingView(
                                config.getSimpleClaimsBreedingLimitPerClaimChunk(),
                                config.getSimpleClaimsBreedingLimitPerClaimTotal(),
                                config.isSimpleClaimsBreedingRequiresClaim()
                        ),
                        new GlobalConfigView.SimpleClaimsView.DamageView(
                                config.isSimpleClaimsDamageProtectTamedFromNonMembers(),
                                config.getSimpleClaimsDamageAllowDamagePermissionKey()
                        )
                )
        );
    }

    @Nonnull
    static InteractionConfigView mapInteractionConfig(@Nonnull TwInteractionConfig config, @Nonnull Gson gson) {
        LinkedHashSet<String> roleIds = Arrays.stream(config.getRoleIds())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(roleId -> !roleId.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new InteractionConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                roleIds,
                config.getCooldowns() != null ? config.getCooldowns().getInteractionSeconds() : null,
                Arrays.stream(config.getInteractions())
                        .filter(Objects::nonNull)
                        .map(entry -> new InteractionConfigView.InteractionEntryView(
                                interactionType(entry),
                                entry.isEnabled(),
                                entry.getCooldownSeconds(),
                                entry.getPromptHint(),
                                entry.getShowPrompt(),
                                safeToJson(gson, entry)
                        ))
                        .toList()
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwCompanionConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwHappinessConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwNeedsConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwBreedingConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwLevelingConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwTraitConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static RoleScopedConfigView mapRoleScopedConfig(@Nonnull TwTalentConfig config, @Nonnull Gson gson) {
        return new RoleScopedConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                config.getPriority(),
                toOrderedSet(config.getRoleIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static CommandItemConfigView mapCommandItemConfig(@Nonnull TwCommandItemConfig config, @Nonnull Gson gson) {
        return new CommandItemConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.isEnabled(),
                toOrderedSet(config.getItemIds()),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static NameItemConfigView mapNameItemConfig(@Nonnull TwNameItemConfig config, @Nonnull Gson gson) {
        return new NameItemConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.getItemId(),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static SpawnerConfigView mapSpawnerConfig(@Nonnull TwSpawnerConfig config, @Nonnull Gson gson) {
        return new SpawnerConfigView(
                config.getId(),
                config.getParentIdForFallback(),
                config.getEmptyItemId(),
                config.getFilledItemId(),
                safeToJson(gson, config)
        );
    }

    @Nonnull
    static PersistenceDiagnosticsView mapPersistenceDiagnostics(
            @Nonnull TameworkPersistenceRuntime.PersistenceDiagnostics diagnostics) {
        return new PersistenceDiagnosticsView(
                diagnostics.databasePath().toString(),
                diagnostics.sqliteBytes(),
                diagnostics.walBytes(),
                diagnostics.shmBytes(),
                diagnostics.totalBytes(),
                mapQueueMetrics(diagnostics.queueMetrics()),
                mapHealth(diagnostics.healthState())
        );
    }

    @Nonnull
    static PersistenceDiagnosticsView.QueueMetricsView mapQueueMetrics(
            @Nonnull PersistenceWriteQueue.QueueMetrics metrics) {
        return new PersistenceDiagnosticsView.QueueMetricsView(
                metrics.queueDepth(),
                metrics.lastBatchSize(),
                metrics.maxBatchSize(),
                metrics.batchesProcessed(),
                metrics.operationsProcessed(),
                metrics.retryAttempts(),
                metrics.failedBatches(),
                metrics.averageBatchSize(),
                metrics.averageWriteMs(),
                metrics.lastBatchWriteMs(),
                metrics.lastFailureReason(),
                metrics.lastFailureAtMs()
        );
    }

    @Nonnull
    static PersistenceDiagnosticsView.HealthView mapHealth(@Nonnull PersistenceHealthService.HealthState healthState) {
        return new PersistenceDiagnosticsView.HealthView(
                healthState.status().name(),
                healthState.reason(),
                healthState.lastFailureAtMs()
        );
    }

    @Nullable
    static Vector3View mapVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return null;
        }
        return new Vector3View(vector.x, vector.y, vector.z);
    }

    @Nonnull
    private static String interactionType(@Nonnull TwInteractionConfig.InteractionEntry entry) {
        if (entry instanceof TwInteractionConfig.TameInteraction) {
            return "Tame";
        }
        if (entry instanceof TwInteractionConfig.FeedInteraction) {
            return "Feed";
        }
        if (entry instanceof TwInteractionConfig.HarvestInteraction) {
            return "Harvest";
        }
        if (entry instanceof TwInteractionConfig.MountInteraction) {
            return "Mount";
        }
        if (entry instanceof TwInteractionConfig.ModeCycleInteraction) {
            return "ModeCycle";
        }
        if (entry instanceof TwInteractionConfig.BreedInteraction) {
            return "Breed";
        }
        if (entry instanceof TwInteractionConfig.CustomInteraction) {
            return "Custom";
        }
        return entry.getClass().getSimpleName();
    }

    @Nonnull
    private static LinkedHashSet<String> toOrderedSet(@Nullable String[] values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    @Nonnull
    static String safeToJson(@Nonnull Gson gson, @Nullable Object value) {
        try {
            return DETACHED_DETAILS_GSON.toJson(value);
        } catch (Throwable throwable) {
            LinkedHashMap<String, String> fallback = new LinkedHashMap<>();
            fallback.put("serializationError", throwable.getClass().getName());
            if (value != null) {
                fallback.put("valueType", value.getClass().getName());
            }
            String message = throwable.getMessage();
            if (message != null && !message.isBlank()) {
                fallback.put("message", message);
            }
            return gson.toJson(fallback);
        }
    }

    @Nullable
    private static <T> T value(@Nullable NpcProfileRepository.ProfileRecord profile,
                               @Nonnull java.util.function.Function<NpcProfileRepository.ProfileRecord, T> getter) {
        if (profile == null) {
            return null;
        }
        return getter.apply(profile);
    }

    @Nonnull
    private static ProgressionView.ModifierEntryView mapModifierEntry(
            @Nonnull CompanionHappinessModifierService.ModifierEntry modifier) {
        return new ProgressionView.ModifierEntryView(
                modifier.id(),
                modifier.label(),
                modifier.value()
        );
    }

    @Nullable
    private static Integer percent(double current, double min, double max) {
        if (!Double.isFinite(current) || !Double.isFinite(min) || !Double.isFinite(max)) {
            return null;
        }
        double range = max - min;
        if (range <= 0.0) {
            return null;
        }
        double clamped = Math.max(min, Math.min(max, current));
        return (int) Math.round(((clamped - min) / range) * 100.0);
    }
}
