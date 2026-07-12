package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.HappinessTimestampPolicy;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionSpawnAdmissionRequest;
import com.alechilles.alecstamework.ownership.CompanionSpawnSourceFinalizationContext;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.alechilles.alecstelemetry.api.TelemetryEventContext;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles linked-companion respawn for command item flows.
 */
final class CommandRespawnService {
    private static final String MASTER_TARGET_SLOT = "MasterTarget";
    private final CommandCompanionPlacementService companionPlacementService;
    private final CommandLinkPolicyService linkPolicyService;
    private final CommandLinkMutationService linkMutationService;
    private final CommandNpcNameResolver npcNameResolver;
    private final CommandLinkedNpcDeathService deathService;
    private final CommandStepExecutionService stepExecutionService;
    private final CommandRespawnProgressionRestoreService progressionRestoreService;
    private final CommandPreparedRestoreSpawnService preparedSpawnService;
    CommandRespawnService(CommandCompanionPlacementService companionPlacementService,
                          CommandLinkPolicyService linkPolicyService,
                          CommandLinkMutationService linkMutationService,
                          CommandNpcNameResolver npcNameResolver,
                          CommandLinkedNpcDeathService deathService,
                          CommandStepExecutionService stepExecutionService) {
        this.companionPlacementService = companionPlacementService;
        this.linkPolicyService = linkPolicyService;
        this.linkMutationService = linkMutationService;
        this.npcNameResolver = npcNameResolver;
        this.deathService = deathService;
        this.stepExecutionService = stepExecutionService;
        this.progressionRestoreService = new CommandRespawnProgressionRestoreService();
        this.preparedSpawnService = new CommandPreparedRestoreSpawnService();
    }
    boolean respawnDeadLinkedNpc(Player player,
                                 Ref<EntityStore> playerRef,
                                 Store<EntityStore> store,
                                 String toolId,
                                 ItemStack stack,
                                 LinkedNpcRecord record,
                                 CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot,
                                 double safeSpawnDistance,
                                 long followRetryDelayMs,
                                 Completion completion) {
        return respawnDeadLinkedNpc(
                player,
                playerRef,
                store,
                toolId,
                stack,
                record,
                deadSnapshot,
                safeSpawnDistance,
                followRetryDelayMs,
                "dead_respawn",
                completion
        );
    }
    boolean respawnDeadLinkedNpc(Player player,
                                 Ref<EntityStore> playerRef,
                                 Store<EntityStore> store,
                                 String toolId,
                                 ItemStack stack,
                                 LinkedNpcRecord record,
                                 CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot,
                                 double safeSpawnDistance,
                                 long followRetryDelayMs,
                                 String traceBranch,
                                 Completion completion) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null || stack == null
                || stack.isEmpty() || record == null || deadSnapshot == null || completion == null) {
            recordRespawnFailure("invalid_input", deadSnapshot, null, traceBranch, toolId);
            return false;
        }
        String roleId = deadSnapshot.roleId();
        if (roleId == null || roleId.isBlank()) {
            recordRespawnFailure("missing_role_id", deadSnapshot, null, traceBranch, toolId);
            return false;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            recordRespawnFailure("npc_plugin_unavailable", deadSnapshot, roleId, traceBranch, toolId);
            return false;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            recordRespawnFailure("unknown_role_id", deadSnapshot, roleId, traceBranch, toolId);
            return false;
        }
        RecentRespawnTraceService.Trace respawnTrace =
                RespawnTraceLogSupport.startTrace(traceBranch, record.npcUuid, deadSnapshot.ownerId(), roleId, toolId);
        Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : deadSnapshot.lastKnownPosition();
        RespawnTraceLogSupport.log(
                respawnTrace,
                "start source=dead_snapshot"
                        + " sourceHint=" + sourceHint
                        + " safeSpawnDistance=" + safeSpawnDistance
                        + " cooldownUntil=" + deadSnapshot.respawnAvailableAtMs()
                        + " displayName=" + deadSnapshot.displayName()
        );
        Vector3d destination = companionPlacementService.computeSafeRespawnPosition(
                playerRef,
                store,
                safeSpawnDistance,
                roleId,
                sourceHint
        );
        if (destination == null) {
            RespawnTraceLogSupport.warn(respawnTrace, "failed stage=safe_position reason=safe_position_not_found");
            recordRespawnFailure("safe_position_not_found", deadSnapshot, roleId, traceBranch, toolId);
            return false;
        }
        Rotation3f rotation = resolveRespawnRotation(store, playerRef, destination);
        UUID ownerId = deadSnapshot.ownerId() != null ? deadSnapshot.ownerId() : player.getUuid();
        Vector3d homePosition = record.homePosition != null ? record.homePosition : deadSnapshot.homePosition();
        String[] toolIds = linkPolicyService.mergeToolIds(deadSnapshot.toolIds(), toolId);
        Tamework plugin = Tamework.getInstance();
        CompanionIdentityResolver identityResolver = plugin == null ? null : plugin.getCompanionIdentityResolver();
        String canonicalProfileId = identityResolver == null
                ? null
                : identityResolver.resolveProfileId(deadSnapshot.npcUuid()).orElse(null);
        if (canonicalProfileId == null) {
            completion.onDenied("respawn-canonical-profile-unavailable");
            return true;
        }
        CompanionLifecycleState sourceLifecycle = traceBranch != null && traceBranch.startsWith("lost_")
                ? CompanionLifecycleState.LOST
                : CompanionLifecycleState.DEAD_REVIVABLE;
        UUID playerUuid = player.getUuid();
        CompanionSpawnAdmissionRequest request = new CompanionSpawnAdmissionRequest(
                canonicalProfileId,
                deadSnapshot.npcUuid(),
                sourceLifecycle,
                false,
                ownerId,
                deadSnapshot.ownerName(),
                player.getWorld().getName(),
                com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(destination.x),
                com.hypixel.hytale.math.util.ChunkUtil.chunkCoordinate(destination.z),
                OwnerPopulationOperation.RESTORE,
                sourceLifecycle == CompanionLifecycleState.LOST ? "lost_restore" : "dead_restore",
                "command-respawn:" + deadSnapshot.npcUuid() + ":" + traceBranch,
                false,
                CompanionSpawnSourceFinalizationContext.extensionJson(
                        sourceLifecycle == CompanionLifecycleState.LOST
                                ? CompanionSpawnSourceFinalizationContext.Kind.LOST_RECORD
                                : CompanionSpawnSourceFinalizationContext.Kind.DEATH_RECORD,
                        "command-respawn-source:" + deadSnapshot.npcUuid() + ":" + traceBranch,
                        deadSnapshot.npcUuid(),
                        playerUuid,
                        null,
                        toolId + "|" + Integer.toUnsignedString(stack.hashCode(), 16),
                        null
                )
        );
        return preparedSpawnService.schedule(
                player.getWorld(),
                store,
                npcPlugin,
                roleIndex,
                destination,
                rotation,
                request,
                new CommandPreparedRestoreSpawnService.Callbacks() {
                    @Nullable
                    private AppliedRespawn pending;

                    @Override
                    public void onSpawned(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        WorldPlayerResolver.ResolvedPlayer resolved =
                                WorldPlayerResolver.resolve(live.world(), playerUuid);
                        if (resolved == null) {
                            throw new IllegalStateException(
                                    "Respawn owner is unavailable after population commit."
                            );
                        }
                        Ref<EntityStore> spawnedRef = live.ref();
                        NPCEntity spawnedNpc = live.npc();
                        Store<EntityStore> liveStore = live.store();
                        String physicsReset = CommandCompanionSpawnPhysicsResetService
                                .resetSpawnedCompanionPhysics(spawnedRef, spawnedNpc, liveStore);
                        RecentRespawnTraceService.Trace finalizedTrace =
                                RespawnTraceLogSupport.recordReplacement(respawnTrace, live.plannedNpcUuid());
                        RespawnTraceLogSupport.log(finalizedTrace, "spawn_physics_reset " + physicsReset);
                        RespawnTraceLogSupport.log(finalizedTrace, "spawned destination=" + destination
                                + " " + RespawnTraceLogSupport.describeNpcState(spawnedRef, liveStore));
                        ItemStack updated = applyRestoredStateAndBuildItem(
                                resolved.player(), resolved.ref(), liveStore,
                                toolId, stack, record, deadSnapshot,
                                roleId, destination, ownerId, homePosition, toolIds,
                                spawnedRef, spawnedNpc, followRetryDelayMs, finalizedTrace
                        );
                        pending = new AppliedRespawn(updated, live.plannedNpcUuid());
                    }

                    @Override
                    public boolean finalizeSource(CompanionPreparedSpawnService.SpawnedCompanion live) {
                        AppliedRespawn result = pending;
                        if (result == null || !completion.onApplied(result)) {
                            return false;
                        }
                        if (deathService != null) {
                            deathService.clearDeadSnapshot(deadSnapshot.npcUuid());
                        }
                        return true;
                    }

                    @Override
                    public void onDenied(@Nonnull String reason) {
                        completion.onDenied(reason);
                    }

                    @Override
                    public void onDurabilityDegraded(String reason) {
                        completion.onDurabilityDegraded(reason);
                    }
                }
        );
    }
    private ItemStack applyRestoredStateAndBuildItem(
            Player player,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            String toolId,
            ItemStack stack,
            LinkedNpcRecord record,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
            String roleId,
            Vector3d destination,
            UUID ownerId,
            @Nullable Vector3d homePosition,
            String[] toolIds,
            Ref<EntityStore> spawnedRef,
            NPCEntity spawnedNpc,
            long followRetryDelayMs,
            RecentRespawnTraceService.Trace respawnTrace) {
        applyRestoredComponents(
                spawnedRef,
                spawnedNpc,
                playerRef,
                store,
                ownerId,
                homePosition,
                toolIds,
                roleId,
                snapshot
        );
        scheduleRespawnFollowRetry(
                player.getWorld(), spawnedNpc.getUuid(), player.getUuid(), followRetryDelayMs
        );
        RespawnTraceLogSupport.scheduleProbe(
                player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 250L, "after_250ms"
        );
        RespawnTraceLogSupport.scheduleProbe(
                player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 1000L, "after_1000ms"
        );
        ItemStack updated = buildUpdatedLinkItem(
                player,
                store,
                toolId,
                stack,
                record,
                snapshot,
                destination,
                homePosition,
                spawnedRef,
                spawnedNpc
        );
        RespawnTraceLogSupport.log(
                respawnTrace,
                "linked_record_updated oldNpc=" + record.npcUuid + " newNpc=" + spawnedNpc.getUuid()
        );
        return updated;
    }

    private void applyRestoredComponents(
            Ref<EntityStore> spawnedRef,
            NPCEntity spawnedNpc,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            UUID ownerId,
            @Nullable Vector3d homePosition,
            String[] toolIds,
            String roleId,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(
                    spawnedRef,
                    linksType,
                    new TameworkCommandLinksComponent(ownerId, toolIds, homePosition)
            );
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(spawnedRef, tamedType, new TameworkTamedComponent(snapshot.tamed()));
        }
        progressionRestoreService.applyAttachments(spawnedRef, spawnedNpc, store, snapshot);
        progressionRestoreService.applyProgression(spawnedRef, store, snapshot);
        applyRespawnTimestampSensitiveState(spawnedRef, store, snapshot);
        progressionRestoreService.applyRecovery(spawnedRef, store, snapshot);
        CompanionProgressionBootstrapService.ensureProgressionComponents(spawnedRef, store, roleId);
        applyRestoredName(spawnedRef, store, ownerId, snapshot);
        applyRespawnFollowBootstrap(spawnedRef, spawnedNpc, playerRef, store);
    }

    private void applyRestoredName(
            Ref<EntityStore> spawnedRef,
            Store<EntityStore> store,
            UUID ownerId,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (snapshot.customName() == null || snapshot.customName().isBlank()) {
            return;
        }
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
        if (nameType != null) {
            store.putComponent(
                    spawnedRef,
                    nameType,
                    new TameworkNpcNameComponent(
                            snapshot.customName(),
                            ownerId,
                            System.currentTimeMillis(),
                            TameworkNpcNameComponent.NameSource.System
                    )
            );
        }
        EntitySupport.setDisplayName(spawnedRef, snapshot.customName(), store);
    }

    private ItemStack buildUpdatedLinkItem(
            Player player,
            Store<EntityStore> store,
            String toolId,
            ItemStack stack,
            LinkedNpcRecord record,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
            Vector3d destination,
            @Nullable Vector3d homePosition,
            Ref<EntityStore> spawnedRef,
            NPCEntity spawnedNpc) {
        ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updated = linkMutationService.upsertLinkedNpcRecord(
                updated,
                spawnedNpc.getUuid(),
                destination,
                linkMutationService.resolveWorldName(store, player.getWorld()),
                homePosition,
                npcNameResolver.resolveNpcDisplayNameFromComponents(spawnedRef, store),
                npcNameResolver.resolveNpcNameKey(spawnedNpc),
                npcNameResolver.resolveNpcRoleId(spawnedNpc)
        );
        return linkMutationService.setLinkedNpcBreedingEnabled(
                updated,
                spawnedNpc.getUuid(),
                snapshot.breedingEnabled()
        );
    }

    @Nullable
    private ItemStack recordRespawnFailure(@Nonnull String reason,
                                           @Nullable CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
                                           @Nullable String roleId,
                                           @Nullable String branch,
                                           @Nullable String toolId) {
        String resolvedRoleId = roleId != null && !roleId.isBlank()
                ? roleId
                : snapshot != null && snapshot.roleId() != null && !snapshot.roleId().isBlank()
                ? snapshot.roleId()
                : "unknown";
        String[] toolIds = snapshot != null ? snapshot.toolIds() : null;
        TelemetryEventContext context = TameworkTelemetryContext.linkedCompanion(
                        "linked_respawn",
                        "respawn",
                        "Linked companion respawn failed."
                )
                .detail("reason", TameworkTelemetryContext.normalizeReason(reason))
                .detail("roleId", TameworkTelemetryContext.bounded(resolvedRoleId, 160))
                .detail("branch", TameworkTelemetryContext.normalizeToken(branch))
                .detail("snapshotPresent", snapshot != null)
                .detail("ownerState", TameworkTelemetryContext.idState(snapshot != null ? snapshot.ownerId() : null))
                .detail("toolState", TameworkTelemetryContext.idState(toolId))
                .detail("snapshotToolCountBucket", TameworkTelemetryContext.countBucket(toolIds == null ? 0 : toolIds.length))
                .build();
        TameworkTelemetryEvents.recordErrorIfAvailable(
                "linked_respawn_failed",
                null,
                context
        );
        return null;
    }

    static TameworkNeedsComponent createRespawnNeedsComponent(TwNeedsConfig needsConfig, long nowMs) {
        return CommandRespawnProgressionRestoreService.createNeeds(needsConfig, nowMs);
    }

    static Map<String, String> resolveRespawnAttachmentSelections(
            @Nullable TwAttachmentMigrationConfig migrationConfig,
            @Nullable Map<String, String> snapshotSelections,
            @Nullable Map<String, Set<String>> attachmentOptions) {
        return CommandRespawnProgressionRestoreService.resolveAttachments(
                migrationConfig, snapshotSelections, attachmentOptions
        );
    }

    /** Reapplies world-time state using the signed timestamp contract retained by v5. */
    private void applyRespawnTimestampSensitiveState(
            Ref<EntityStore> spawnedRef,
            Store<EntityStore> store,
            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (spawnedRef == null || !spawnedRef.isValid() || store == null || snapshot == null) {
            return;
        }
        applyRespawnHappinessState(spawnedRef, store, snapshot);
        applyRespawnBreedingState(spawnedRef, store, snapshot);
        applyRespawnLifeStageState(spawnedRef, store, snapshot);
    }

    private void applyRespawnHappinessState(Ref<EntityStore> spawnedRef,
                                            Store<EntityStore> store,
                                            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return;
        }
        boolean hasHappinessData = (snapshot.happinessConfigId() != null && !snapshot.happinessConfigId().isBlank())
                || snapshot.happinessValue() != null
                || HappinessTimestampPolicy.isValid(snapshot.happinessLastUpdateMs())
                || snapshot.breedingHappiness() != null;
        if (!hasHappinessData) {
            return;
        }
        double value = snapshot.happinessValue() != null
                ? snapshot.happinessValue()
                : snapshot.breedingHappiness() != null
                ? snapshot.breedingHappiness()
                : 0.0;
        long lastUpdateMs = HappinessTimestampPolicy.isValid(snapshot.happinessLastUpdateMs())
                ? snapshot.happinessLastUpdateMs()
                : System.currentTimeMillis();
        TameworkHappinessComponent component = new TameworkHappinessComponent(
                snapshot.happinessConfigId(),
                value,
                lastUpdateMs
        );
        store.putComponent(spawnedRef, happinessType, component);
    }

    private void applyRespawnBreedingState(Ref<EntityStore> spawnedRef,
                                           Store<EntityStore> store,
                                           CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType == null) {
            return;
        }
        boolean hasBreedingData = (snapshot.breedingConfigId() != null && !snapshot.breedingConfigId().isBlank())
                || snapshot.breedingHappiness() != null
                || snapshot.breedingEnabled()
                || snapshot.breedingCooldownUntilMs() != 0L
                || snapshot.breedingLastPartnerUuid() != null;
        if (!hasBreedingData) {
            return;
        }
        Double restoredHappiness = resolveRestoredHappiness(spawnedRef, store);
        double happiness = restoredHappiness != null
                ? restoredHappiness
                : snapshot.breedingHappiness() != null
                ? snapshot.breedingHappiness()
                : 0.0;
        long lastHappinessUpdateMs = resolveRestoredHappinessTimestamp(spawnedRef, store);
        long cooldownUntilMs = snapshot.breedingCooldownUntilMs();
        long now = BreedingTimeService.resolveCurrentTimeMs(store);
        BreedingTimeService.CooldownTiming timing =
                resolveRespawnCooldownTiming(cooldownUntilMs, now);
        boolean ready = false;
        String configId = snapshot.breedingConfigId();
        if (snapshot.breedingEnabled()
                && configId != null
                && !configId.isBlank()
                && !BreedingTimeService.isDeadlineActive(cooldownUntilMs, now)) {
            ready = resolveBreedingReadiness(configId, happiness, spawnedRef, store);
        }
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                configId,
                happiness,
                HappinessTimestampPolicy.orNow(lastHappinessUpdateMs),
                ready,
                snapshot.breedingEnabled(),
                cooldownUntilMs,
                snapshot.breedingLastPartnerUuid(),
                timing.startedAtMs(),
                timing.durationMs()
        );
        store.putComponent(spawnedRef, breedingType, component);
    }

    static BreedingTimeService.CooldownTiming resolveRespawnCooldownTiming(long cooldownUntilMs, long nowMs) {
        return BreedingTimeService.reconstructCooldownTiming(cooldownUntilMs, nowMs);
    }

    private void applyRespawnLifeStageState(Ref<EntityStore> spawnedRef,
                                            Store<EntityStore> store,
                                            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        boolean hasLifeStageData = (snapshot.lifeStage() != null && !snapshot.lifeStage().isBlank())
                || snapshot.lifeStageBornAtMs() != 0L
                || snapshot.lifeStageAdolescentAtMs() != 0L
                || snapshot.lifeStageAdultAtMs() != 0L
                || snapshot.lifeStageFullyGrownAtMs() != 0L;
        if (!hasLifeStageData) {
            CompanionLifeStageService.ensureLifeStageComponent(spawnedRef, store);
            applyRespawnGender(spawnedRef, store, type, snapshot.lifeStageGender());
            CompanionLifeStageService.refreshLifeStage(
                    spawnedRef,
                    store.getComponent(spawnedRef, NPCEntity.getComponentType()),
                    store
            );
            return;
        }
        TameworkLifeStageComponent component = new TameworkLifeStageComponent(
                snapshot.lifeStage(),
                snapshot.lifeStageBornAtMs(),
                snapshot.lifeStageAdolescentAtMs(),
                snapshot.lifeStageAdultAtMs(),
                snapshot.lifeStageFullyGrownAtMs(),
                snapshot.lifeStageBabyScale(),
                snapshot.lifeStageAdolescentScale(),
                snapshot.lifeStageAdolescentSwitchScale(),
                snapshot.lifeStageAdultStartScale(),
                snapshot.lifeStageAdultSwitchScale(),
                snapshot.lifeStageAdultScale(),
                snapshot.lifeStageGrowthScalingEnabled()
        );
        component.setGender(snapshot.lifeStageGender());
        store.putComponent(spawnedRef, type, component);
        CompanionLifeStageService.refreshLifeStage(
                spawnedRef,
                store.getComponent(spawnedRef, NPCEntity.getComponentType()),
                store
        );
    }

    private void applyRespawnGender(Ref<EntityStore> spawnedRef,
                                    Store<EntityStore> store,
                                    ComponentType<EntityStore, TameworkLifeStageComponent> type,
                                    @Nullable String gender) {
        if (gender == null || gender.isBlank()) {
            return;
        }
        TameworkLifeStageComponent component = store.getComponent(spawnedRef, type);
        if (component != null) {
            component.setGender(gender);
            store.putComponent(spawnedRef, type, component);
        }
    }

    private boolean resolveBreedingReadiness(String configId,
                                             double happiness,
                                             @Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store) {
        if (configId == null || configId.isBlank()) {
            return false;
        }
        TwBreedingConfig config = TwBreedingConfig.resolveById(configId);
        if (config == null) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        return happiness >= TameworkRuntimeSettings.breedingHappinessThreshold(
                config.resolveHappiness(roleId).getThreshold(),
                TwHappinessConfig.isEnabledForRole(roleId)
        );
    }

    @Nullable
    private Double resolveRestoredHappiness(Ref<EntityStore> spawnedRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        if (type == null) {
            return null;
        }
        TameworkHappinessComponent happiness = store.getComponent(spawnedRef, type);
        if (happiness == null || !Double.isFinite(happiness.getValue())) {
            return null;
        }
        return happiness.getValue();
    }

    private long resolveRestoredHappinessTimestamp(Ref<EntityStore> spawnedRef, Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkHappinessComponent> type = TameworkHappinessComponent.getComponentType();
        if (type == null) {
            return 0L;
        }
        TameworkHappinessComponent happiness = store.getComponent(spawnedRef, type);
        return happiness != null ? happiness.getLastUpdateMs() : 0L;
    }

    private void applyRespawnFollowBootstrap(Ref<EntityStore> npcRef,
                                             NPCEntity npc,
                                             Ref<EntityStore> playerRef,
                                             Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || npc == null || store == null) {
            return;
        }
        Role role = npc.getRole();
        if (role != null && role.getMarkedEntitySupport() != null) {
            role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
            if (playerRef != null && playerRef.isValid()) {
                role.getMarkedEntitySupport().setMarkedEntity(MASTER_TARGET_SLOT, playerRef);
            }
        }
        // Match default Follow behavior after respawn.
        if (!stepExecutionService.applyState(npcRef, npc, store, "Follow", null)) {
            stepExecutionService.applyState(npcRef, npc, store, "Idle", null);
        }
    }

    private void scheduleRespawnFollowRetry(World world,
                                             UUID npcUuid,
                                             UUID playerUuid,
                                             long delayMs) {
        if (world == null || npcUuid == null || playerUuid == null) {
            return;
        }
        long safeDelayMs = Math.max(0L, delayMs);
        CompletableFuture.runAsync(
                () -> world.execute(() -> {
                    Ref<EntityStore> npcRef = world.getEntityRef(npcUuid);
                    if (npcRef == null || !npcRef.isValid()) {
                        return;
                    }
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    if (store == null) {
                        return;
                    }
                    Ref<EntityStore> playerRef = world.getEntityRef(playerUuid);
                    NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
                    if (npc == null) {
                        return;
                    }
                    applyRespawnFollowBootstrap(npcRef, npc, playerRef, store);
                }),
                CompletableFuture.delayedExecutor(safeDelayMs, TimeUnit.MILLISECONDS)
        );
    }

    private Rotation3f resolveRespawnRotation(Store<EntityStore> store,
                                              Ref<EntityStore> playerRef,
                                              Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Rotation3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Rotation3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.lengthSquared() > 0.0001) {
                return Rotation3f.lookAt(relative);
            }
        }
        return new Rotation3f(transform.getRotation());
    }

    interface Completion {
        boolean onApplied(@Nonnull AppliedRespawn result);

        void onDenied(@Nonnull String reason);

        default void onDurabilityDegraded(@Nonnull String reason) {
        }
    }

    record AppliedRespawn(@Nonnull ItemStack updatedStack,
                          @Nonnull UUID replacementNpcUuid) {
    }
}
