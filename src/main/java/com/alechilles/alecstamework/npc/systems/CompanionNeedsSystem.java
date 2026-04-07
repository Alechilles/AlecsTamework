package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRuntimeClock;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.ui.TameworkUiMessageService;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.dependency.SystemTypeDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsSystems;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs periodic hunger/thirst progression updates for tamed companions.
 */
public final class CompanionNeedsSystem extends TickingSystem<EntityStore> {
    private static final long SYSTEM_SWEEP_INTERVAL_MS = 2_000L;
    private static final long MALNOURISHMENT_WARNING_THROTTLE_MS = 60L * 1000L;
    private static final long MALNOURISHMENT_WARNING_PRUNE_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final String MALNOURISHMENT_WARNING_SUFFIX = " linked NPCs dying from malnourishment";

    private long nextSweepAtMs;
    private final TameworkUiMessageService uiMessageService = new TameworkUiMessageService();
    private final Map<UUID, Long> lastMalnourishmentWarningByOwner = new HashMap<>();

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Set.of(
                new SystemTypeDependency<>(Order.BEFORE, EntityStatsModule.get().getStatModifyingSystemType()),
                new SystemDependency<>(Order.BEFORE, EntityStatsSystems.Changes.class)
        );
    }

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        CompanionRuntimeClock.advanceByDeltaSeconds(dt);
        long nowMs = System.currentTimeMillis();
        boolean runNeedsSweep = nowMs >= nextSweepAtMs;
        if (runNeedsSweep) {
            nextSweepAtMs = nowMs + SYSTEM_SWEEP_INTERVAL_MS;
        }

        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (npcType == null || tamedType == null) {
            return;
        }
        List<NeedsCandidate> candidates = new ArrayList<>();
        Map<UUID, Integer> starvingLinkedByOwner = runNeedsSweep ? new HashMap<>() : null;
        ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksType =
                TameworkCommandLinksComponent.getComponentType();
        store.forEachChunk(
                Query.and(npcType, tamedType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> commandBuffer) -> {
                    int size = chunk.size();
                    for (int i = 0; i < size; i++) {
                        Ref<EntityStore> ref = chunk.getReferenceTo(i);
                        TameworkTamedComponent tamed = chunk.getComponent(i, tamedType);
                        if (ref == null || !ref.isValid() || tamed == null || !tamed.isTamed()) {
                            continue;
                        }
                        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
                        UUID linkedOwnerId = null;
                        if (commandLinksType != null) {
                            TameworkCommandLinksComponent links = chunk.getComponent(i, commandLinksType);
                            if (links != null
                                    && links.getOwnerId() != null
                                    && links.getToolIds() != null
                                    && links.getToolIds().length > 0) {
                                linkedOwnerId = links.getOwnerId();
                            }
                        }
                        candidates.add(new NeedsCandidate(ref, roleId, linkedOwnerId));
                    }
                }
        );
        for (NeedsCandidate candidate : candidates) {
            if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
                continue;
            }
            if (runNeedsSweep) {
                CompanionNeedsService.tickNeeds(candidate.ref, store, candidate.roleId);
                if (candidate.linkedOwnerId != null
                        && starvingLinkedByOwner != null
                        && CompanionNeedsService.isNeedsDamageActive(candidate.ref, store, candidate.roleId)) {
                    starvingLinkedByOwner.merge(candidate.linkedOwnerId, 1, Integer::sum);
                }
            } else {
                CompanionNeedsService.tickNaturalRegenSuppressionOnly(candidate.ref, store, candidate.roleId);
            }
        }
        if (runNeedsSweep && starvingLinkedByOwner != null && !starvingLinkedByOwner.isEmpty()) {
            notifyOwnersOfMalnourishedLinkedNpcs(store, starvingLinkedByOwner, nowMs);
        }
        if (runNeedsSweep) {
            pruneWarningThrottleEntries(nowMs);
        }
    }

    private void notifyOwnersOfMalnourishedLinkedNpcs(@Nonnull Store<EntityStore> store,
                                                      @Nonnull Map<UUID, Integer> starvingLinkedByOwner,
                                                      long nowMs) {
        for (Map.Entry<UUID, Integer> entry : starvingLinkedByOwner.entrySet()) {
            UUID ownerId = entry.getKey();
            int count = entry.getValue() != null ? entry.getValue() : 0;
            if (ownerId == null || count <= 0 || !shouldSendMalnourishmentWarning(ownerId, nowMs)) {
                continue;
            }
            Player player = resolveOnlinePlayer(store, ownerId);
            if (player == null) {
                continue;
            }
            String message = count + MALNOURISHMENT_WARNING_SUFFIX;
            if (uiMessageService.show(player, message, NotificationStyle.Danger)) {
                lastMalnourishmentWarningByOwner.put(ownerId, nowMs);
            }
        }
    }

    private boolean shouldSendMalnourishmentWarning(@Nonnull UUID ownerId, long nowMs) {
        Long lastSentMs = lastMalnourishmentWarningByOwner.get(ownerId);
        return lastSentMs == null || nowMs - lastSentMs >= MALNOURISHMENT_WARNING_THROTTLE_MS;
    }

    private void pruneWarningThrottleEntries(long nowMs) {
        if (lastMalnourishmentWarningByOwner.isEmpty()) {
            return;
        }
        lastMalnourishmentWarningByOwner.entrySet().removeIf(entry -> {
            Long value = entry.getValue();
            if (value == null) {
                return true;
            }
            return nowMs - value > MALNOURISHMENT_WARNING_PRUNE_WINDOW_MS;
        });
    }

    @Nullable
    private static Player resolveOnlinePlayer(@Nonnull Store<EntityStore> store, @Nullable UUID ownerId) {
        if (ownerId == null) {
            return null;
        }
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        if (world == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(ownerId);
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        try {
            return store.getComponent(playerRef, Player.getComponentType());
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static final class NeedsCandidate {
        private final Ref<EntityStore> ref;
        private final String roleId;
        private final UUID linkedOwnerId;

        private NeedsCandidate(Ref<EntityStore> ref, String roleId, UUID linkedOwnerId) {
            this.ref = ref;
            this.roleId = roleId;
            this.linkedOwnerId = linkedOwnerId;
        }
    }
}
