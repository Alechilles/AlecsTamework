package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.alechilles.alecstamework.npc.progression.TraitValueCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        if (player == null || playerRef == null || !playerRef.isValid() || store == null || stack == null
                || stack.isEmpty() || record == null || deadSnapshot == null) {
            return null;
        }
        String roleId = deadSnapshot.roleId();
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return null;
        }
        int roleIndex = npcPlugin.getIndex(roleId);
        if (roleIndex < 0) {
            return null;
        }
        Vector3d sourceHint = record.lastKnownPosition != null ? record.lastKnownPosition : deadSnapshot.lastKnownPosition();
        Vector3d destination = companionPlacementService.computeSafeRespawnPosition(
                playerRef,
                store,
                safeSpawnDistance,
                sourceHint
        );
        if (destination == null) {
            return null;
        }
        Vector3f rotation = resolveRespawnRotation(store, playerRef, destination);
        Pair<Ref<EntityStore>, NPCEntity> spawned = npcPlugin.spawnEntity(store, roleIndex, destination, rotation, null, null);
        if (spawned == null || spawned.first() == null || spawned.second() == null) {
            return null;
        }
        Ref<EntityStore> spawnedRef = spawned.first();
        NPCEntity spawnedNpc = spawned.second();
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
        applyRespawnProgressionState(spawnedRef, store, deadSnapshot);
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
        ItemStack updated = linkMutationService.removeLinkedNpcRecord(stack, record.npcUuid);
        updated = linkMutationService.upsertLinkedNpcRecord(
                updated,
                spawnedNpc.getUuid(),
                destination,
                homePosition,
                npcNameResolver.resolveNpcDisplayNameFromComponents(spawnedRef, store),
                npcNameResolver.resolveNpcNameKey(spawnedNpc),
                npcNameResolver.resolveNpcRoleId(spawnedNpc)
        );
        if (deathService != null) {
            deathService.clearDeadSnapshot(deadSnapshot.npcUuid());
        }
        return updated;
    }

    private void applyRespawnProgressionState(Ref<EntityStore> spawnedRef,
                                              Store<EntityStore> store,
                                              CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        if (spawnedRef == null || !spawnedRef.isValid() || store == null || snapshot == null) {
            return;
        }
        applyRespawnHappinessState(spawnedRef, store, snapshot);
        applyRespawnBreedingState(spawnedRef, store, snapshot);
        applyRespawnTraitsState(spawnedRef, store, snapshot);
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
        if (configId != null && !configId.isBlank()) {
            ready = resolveBreedingReadiness(configId, happiness);
        }
        long lastHappinessUpdateMs = resolveRestoredHappinessTimestamp(spawnedRef, store);
        TameworkBreedingComponent component = new TameworkBreedingComponent(
                configId,
                happiness,
                lastHappinessUpdateMs > 0L ? lastHappinessUpdateMs : System.currentTimeMillis(),
                ready,
                snapshot.breedingCooldownUntilMs(),
                snapshot.breedingLastPartnerUuid()
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
        CompanionStatModifierService.applyTraitModifiers(spawnedRef, store);
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
                || snapshot.lifeStageAdultAtMs() > 0L;
        if (!hasLifeStageData) {
            CompanionLifeStageService.ensureLifeStageComponent(spawnedRef, store);
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
                snapshot.lifeStageBabyScale(),
                snapshot.lifeStageAdolescentScale(),
                snapshot.lifeStageAdultScale(),
                snapshot.lifeStageGrowthScalingEnabled()
        );
        store.putComponent(spawnedRef, type, component);
        CompanionLifeStageService.refreshLifeStage(
                spawnedRef,
                store.getComponent(spawnedRef, NPCEntity.getComponentType()),
                store
        );
    }

    private boolean resolveBreedingReadiness(String configId, double happiness) {
        if (configId == null || configId.isBlank()) {
            return false;
        }
        TwBreedingConfig config = TwBreedingConfig.resolveById(configId);
        if (config == null) {
            return false;
        }
        return happiness >= config.getHappiness().getThreshold();
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

    private Vector3f resolveRespawnRotation(Store<EntityStore> store,
                                            Ref<EntityStore> playerRef,
                                            Vector3d spawnPosition) {
        if (store == null || playerRef == null || !playerRef.isValid()) {
            return new Vector3f();
        }
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null) {
            return new Vector3f();
        }
        Vector3d playerPos = new Vector3d(transform.getPosition());
        if (spawnPosition != null) {
            Vector3d relative = new Vector3d(
                    playerPos.x - spawnPosition.x,
                    0.0,
                    playerPos.z - spawnPosition.z
            );
            if (relative.squaredLength() > 0.0001) {
                return Vector3f.lookAt(relative);
            }
        }
        return new Vector3f(transform.getRotation());
    }
}
