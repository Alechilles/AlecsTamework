package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwAttachmentMigrationConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.metrics.TameworkTelemetryContext;
import com.alechilles.alecstamework.metrics.TameworkTelemetryEvents;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.BreedingTimeService;
import com.alechilles.alecstamework.npc.progression.CompanionAttachmentMigrationService;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionLevelingService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TalentIdCodec;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
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
import it.unimi.dsi.fastutil.Pair;
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
    }

    ItemStack respawnDeadLinkedNpc(Player player,
                                   Ref<EntityStore> playerRef,
                                   Store<EntityStore> store,
                                   String toolId,
                                   ItemStack stack,
                                   LinkedNpcRecord record,
                                   CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot,
                                   double safeSpawnDistance,
                                   long followRetryDelayMs) {
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
                "dead_respawn"
        );
    }

    ItemStack respawnDeadLinkedNpc(Player player,
                                   Ref<EntityStore> playerRef,
                                   Store<EntityStore> store,
                                   String toolId,
                                   ItemStack stack,
                                   LinkedNpcRecord record,
                                   CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot deadSnapshot,
                                   double safeSpawnDistance,
                                   long followRetryDelayMs,
                                   String traceBranch) {
        if (player == null || playerRef == null || !playerRef.isValid() || store == null || stack == null
                || stack.isEmpty() || record == null || deadSnapshot == null) {
            return recordRespawnFailure("invalid_input", deadSnapshot, null, traceBranch, toolId);
        }
        String roleId = deadSnapshot.roleId();
        if (roleId == null || roleId.isBlank()) {
            return recordRespawnFailure("missing_role_id", deadSnapshot, null, traceBranch, toolId);
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return recordRespawnFailure("npc_plugin_unavailable", deadSnapshot, roleId, traceBranch, toolId);
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return recordRespawnFailure("unknown_role_id", deadSnapshot, roleId, traceBranch, toolId);
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
            return recordRespawnFailure("safe_position_not_found", deadSnapshot, roleId, traceBranch, toolId);
        }
        Rotation3f rotation = resolveRespawnRotation(store, playerRef, destination);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, destination, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            RespawnTraceLogSupport.warn(respawnTrace, "failed stage=spawn reason=spawn_entity_failed destination=" + destination);
            return recordRespawnFailure("spawn_entity_failed", deadSnapshot, roleId, traceBranch, toolId);
        }
        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
        String physicsReset = CommandCompanionSpawnPhysicsResetService.resetSpawnedCompanionPhysics(
                spawnedRef,
                spawnedNpc,
                store
        );
        respawnTrace = RespawnTraceLogSupport.recordReplacement(respawnTrace, spawnedNpc.getUuid());
        RespawnTraceLogSupport.log(respawnTrace, "spawn_physics_reset " + physicsReset);
        RespawnTraceLogSupport.log(
                respawnTrace,
                "spawned destination=" + destination + " " + RespawnTraceLogSupport.describeNpcState(spawnedRef, store)
        );
        UUID ownerId = deadSnapshot.ownerId() != null ? deadSnapshot.ownerId() : player.getUuid();
        Vector3d homePosition = record.homePosition != null ? record.homePosition : deadSnapshot.homePosition();
        String[] toolIds = linkPolicyService.mergeToolIds(deadSnapshot.toolIds(), toolId);
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType = TameworkCommandLinksComponent.getComponentType();
        if (linksType != null) {
            store.putComponent(
                    spawnedRef,
                    linksType,
                    new TameworkCommandLinksComponent(ownerId, toolIds, homePosition)
            );
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            store.putComponent(
                    spawnedRef,
                    ownerType,
                    new TameworkOwnerComponent(ownerId, deadSnapshot.ownerName())
            );
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            store.putComponent(
                    spawnedRef,
                    tamedType,
                    new TameworkTamedComponent(deadSnapshot.tamed())
            );
        }
        applyRespawnAttachmentState(spawnedRef, spawnedNpc, store, deadSnapshot);
        applyRespawnProgressionState(spawnedRef, store, deadSnapshot);
        applyRespawnRecoveryState(spawnedRef, store, deadSnapshot);
        RespawnTraceLogSupport.log(
                respawnTrace,
                "post_restore recoveryStateApplied=true " + RespawnTraceLogSupport.describeNpcState(spawnedRef, store)
        );
        if (deadSnapshot.customName() != null && !deadSnapshot.customName().isBlank()) {
            ComponentType<EntityStore, TameworkNpcNameComponent> nameType = TameworkNpcNameComponent.getComponentType();
            if (nameType != null) {
                store.putComponent(
                        spawnedRef,
                        nameType,
                        new TameworkNpcNameComponent(
                                deadSnapshot.customName(),
                                ownerId,
                                System.currentTimeMillis(),
                                TameworkNpcNameComponent.NameSource.System
                        )
                );
            }
            EntitySupport.setDisplayName(spawnedRef, deadSnapshot.customName(), store);
        }
        applyRespawnFollowBootstrap(spawnedRef, spawnedNpc, playerRef, store);
        scheduleRespawnFollowRetry(player.getWorld(), spawnedNpc.getUuid(), playerRef, followRetryDelayMs);
        RespawnTraceLogSupport.scheduleProbe(player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 250L, "after_250ms");
        RespawnTraceLogSupport.scheduleProbe(player.getWorld(), spawnedNpc.getUuid(), respawnTrace, 1000L, "after_1000ms");
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
        updated = linkMutationService.setLinkedNpcBreedingEnabled(
                updated,
                spawnedNpc.getUuid(),
                deadSnapshot.breedingEnabled()
        );
        if (deathService != null) {
            deathService.clearDeadSnapshot(deadSnapshot.npcUuid());
        }
        RespawnTraceLogSupport.log(respawnTrace, "linked_record_updated oldNpc=" + record.npcUuid
                + " newNpc=" + spawnedNpc.getUuid());
        return updated;
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

    private void applyRespawnProgressionState(Ref<EntityStore> spawnedRef,
                                              Store<EntityStore> store,
                                              CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (spawnedRef == null || !spawnedRef.isValid() || store == null || snapshot == null) {
            return;
        }
        applyRespawnHappinessState(spawnedRef, store, snapshot);
        applyRespawnBreedingState(spawnedRef, store, snapshot);
        applyRespawnLevelingState(spawnedRef, store, snapshot);
        applyRespawnTraitsState(spawnedRef, store, snapshot);
        applyRespawnTalentsState(spawnedRef, store, snapshot);
        applyRespawnLifeStageState(spawnedRef, store, snapshot);
        CompanionStatModifierService.applyTraitModifiers(spawnedRef, store);
    }

    private void applyRespawnRecoveryState(Ref<EntityStore> spawnedRef,
                                           Store<EntityStore> store,
                                           CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (spawnedRef == null || !spawnedRef.isValid() || store == null) {
            return;
        }
        CompanionHealthStateService.applyStoredHealthPercent(spawnedRef, store, 100.0);

        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return;
        }
        String roleId = snapshot != null && snapshot.roleId() != null && !snapshot.roleId().isBlank()
                ? snapshot.roleId()
                : CompanionRoleIdResolver.resolveRoleId(spawnedRef, store);
        TwNeedsConfig needsConfig = TwNeedsConfig.resolveForRole(roleId);
        if (needsConfig == null || !TameworkRuntimeSettings.needsEnabled(needsConfig.isEnabled())) {
            return;
        }
        TameworkNeedsComponent component = createRespawnNeedsComponent(needsConfig, CompanionRuntimeClock.nowMs());
        store.putComponent(spawnedRef, needsType, component);
    }

    static TameworkNeedsComponent createRespawnNeedsComponent(TwNeedsConfig needsConfig, long nowMs) {
        TwNeedsConfig.ValueSettings values = needsConfig.getValues();
        TameworkNeedsComponent component = new TameworkNeedsComponent(
                needsConfig.getId(),
                values.getHungerDefault(),
                values.getThirstDefault(),
                0.0,
                0.0,
                nowMs,
                nowMs
        );
        component.setRegenSuppressionBaselineHealth(-1.0);
        component.setRegenSuppressionAllowedHeal(0.0);
        component.setLastManagedHealth(-1.0);
        return component;
    }

    private void applyRespawnAttachmentState(Ref<EntityStore> spawnedRef,
                                             NPCEntity spawnedNpc,
                                             Store<EntityStore> store,
                                             CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (spawnedRef == null || !spawnedRef.isValid() || store == null || snapshot == null) {
            return;
        }
        Map<String, String> attachmentSelections =
                CommandLinkedNpcDeathService.decodeAttachmentSelections(snapshot.attachmentsValues());
        if (attachmentSelections.isEmpty()) {
            return;
        }
        Map<String, Set<String>> attachmentOptions = CompanionModelAttachmentService.resolveAttachmentOptionIds(
                CompanionModelAttachmentService.resolveModelAsset(spawnedRef, store)
        );
        String roleId = firstNonBlank(snapshot.roleId(), CompanionRoleIdResolver.resolveRoleId(spawnedRef, store));
        TwAttachmentMigrationConfig migrationConfig = TwAttachmentMigrationConfig.resolveForRole(roleId);
        attachmentSelections = resolveRespawnAttachmentSelections(migrationConfig, attachmentSelections, attachmentOptions);
        if (!attachmentSelections.isEmpty()) {
            CompanionModelAttachmentService.applyAttachments(spawnedRef, spawnedNpc, store, attachmentSelections);
        }
        ComponentType<EntityStore, TameworkAttachmentsComponent> attachmentsType =
                TameworkAttachmentsComponent.getComponentType();
        if (attachmentsType == null || attachmentSelections.isEmpty()) {
            return;
        }
        store.putComponent(
                spawnedRef,
                attachmentsType,
                new TameworkAttachmentsComponent(snapshot.attachmentsConfigId(), attachmentSelections)
        );
    }

    static Map<String, String> resolveRespawnAttachmentSelections(
            @Nullable TwAttachmentMigrationConfig migrationConfig,
            @Nullable Map<String, String> snapshotSelections,
            @Nullable Map<String, Set<String>> attachmentOptions) {
        Map<String, String> filtered = CompanionModelAttachmentService.filterAttachmentSelections(
                snapshotSelections,
                attachmentOptions
        );
        if (filtered.isEmpty()) {
            return Map.of();
        }
        Map<String, String> migrated = CompanionAttachmentMigrationService.applyConfiguredMigrations(
                migrationConfig,
                filtered,
                attachmentOptions
        );
        return CompanionModelAttachmentService.filterAttachmentSelections(migrated, attachmentOptions);
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
                || snapshot.happinessLastUpdateMs() > 0L
                || snapshot.breedingHappiness() != null;
        if (!hasHappinessData) {
            return;
        }
        double value = snapshot.happinessValue() != null
                ? snapshot.happinessValue()
                : snapshot.breedingHappiness() != null
                ? snapshot.breedingHappiness()
                : 0.0;
        long lastUpdateMs = snapshot.happinessLastUpdateMs() > 0L
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
                || snapshot.breedingCooldownUntilMs() > 0L
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
        boolean ready = false;
        String configId = snapshot.breedingConfigId();
        if (snapshot.breedingEnabled() && configId != null && !configId.isBlank()) {
            ready = resolveBreedingReadiness(configId, happiness, spawnedRef, store);
        }
        long lastHappinessUpdateMs = resolveRestoredHappinessTimestamp(spawnedRef, store);
        long cooldownUntilMs = snapshot.breedingCooldownUntilMs();
        long cooldownDurationMs = 0L;
        long cooldownStartedAtMs = 0L;
        if (cooldownUntilMs > 0L) {
            long now = BreedingTimeService.resolveCurrentTimeMs(store);
            cooldownDurationMs = Math.max(0L, cooldownUntilMs - now);
            cooldownStartedAtMs = cooldownDurationMs > 0L ? now : 0L;
        }
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                configId,
                happiness,
                lastHappinessUpdateMs > 0L ? lastHappinessUpdateMs : System.currentTimeMillis(),
                ready,
                snapshot.breedingEnabled(),
                cooldownUntilMs,
                snapshot.breedingLastPartnerUuid(),
                cooldownStartedAtMs,
                cooldownDurationMs
        );
        store.putComponent(spawnedRef, breedingType, component);
    }

    private void applyRespawnTraitsState(Ref<EntityStore> spawnedRef,
                                         Store<EntityStore> store,
                                         CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkTraitsComponent> traitsType = TameworkTraitsComponent.getComponentType();
        if (traitsType == null) {
            return;
        }
        boolean hasTraitsData = (snapshot.traitsConfigId() != null && !snapshot.traitsConfigId().isBlank())
                || snapshot.traitsRollSeed() != 0L
                || (snapshot.traitsValues() != null && !snapshot.traitsValues().isBlank());
        if (!hasTraitsData) {
            return;
        }
        TameworkTraitsComponent component = new TameworkTraitsComponent(
                snapshot.traitsConfigId(),
                snapshot.traitsRollSeed(),
                TraitValueCodec.decode(snapshot.traitsValues())
        );
        store.putComponent(spawnedRef, traitsType, component);
    }

    private void applyRespawnLevelingState(Ref<EntityStore> spawnedRef,
                                           Store<EntityStore> store,
                                           CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkLevelingComponent> type = TameworkLevelingComponent.getComponentType();
        if (type == null) {
            return;
        }
        boolean hasLevelingData = (snapshot.levelingConfigId() != null && !snapshot.levelingConfigId().isBlank())
                || snapshot.levelingLevel() > 1
                || snapshot.levelingTotalXp() > 0.0;
        if (hasLevelingData) {
            store.putComponent(
                    spawnedRef,
                    type,
                    new TameworkLevelingComponent(
                            snapshot.levelingConfigId(),
                            snapshot.levelingLevel(),
                            0.0,
                            snapshot.levelingTotalXp()
                    )
            );
        }
        CompanionLevelingService.ensureLevelingComponent(spawnedRef, store);
    }

    private void applyRespawnTalentsState(Ref<EntityStore> spawnedRef,
                                          Store<EntityStore> store,
                                          CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkTalentsComponent> type = TameworkTalentsComponent.getComponentType();
        if (type == null) {
            return;
        }
        boolean hasTalentData = (snapshot.talentsConfigId() != null && !snapshot.talentsConfigId().isBlank())
                || snapshot.talentsSpentPoints() > 0
                || (snapshot.purchasedTalentIds() != null && !snapshot.purchasedTalentIds().isBlank());
        if (!hasTalentData) {
            return;
        }
        store.putComponent(
                spawnedRef,
                type,
                new TameworkTalentsComponent(
                        snapshot.talentsConfigId(),
                        snapshot.talentsSpentPoints(),
                        TalentIdCodec.decode(snapshot.purchasedTalentIds())
                )
        );
    }

    private void applyRespawnLifeStageState(Ref<EntityStore> spawnedRef,
                                            Store<EntityStore> store,
                                            CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        if (type == null) {
            return;
        }
        boolean hasLifeStageData = (snapshot.lifeStage() != null && !snapshot.lifeStage().isBlank())
                || snapshot.lifeStageBornAtMs() > 0L
                || snapshot.lifeStageAdolescentAtMs() > 0L
                || snapshot.lifeStageAdultAtMs() > 0L
                || snapshot.lifeStageFullyGrownAtMs() > 0L;
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

    @Nullable
    private String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
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
                                            Ref<EntityStore> playerRef,
                                            long delayMs) {
        if (world == null || npcUuid == null) {
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
}
