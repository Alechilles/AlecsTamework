package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.OwnerSource;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Schedules interaction-driven owner mutations and resolves feedback/live continuations after the
 * durable population transaction commits.
 */
final class InteractionOwnerAdmissionService {

    boolean scheduleStartTaming(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                Player player,
                                InteractionStateEffects.OwnerAppliedContinuation continuation) {
        if (npcRef == null || !npcRef.isValid() || store == null || player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        UUID ownerId = player.getUuid();
        if (ownerType == null || ownerId == null) {
            return false;
        }
        OwnerMutationScheduler scheduler = scheduler();
        if (scheduler == null) {
            sendOwnerUnavailable(player, "owner-mutation-service-unavailable");
            return false;
        }
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (npcUuid == null) {
            return false;
        }
        TameworkOwnerComponent existing = store.getComponent(npcRef, ownerType);
        UUID oldOwnerId = existing == null ? null : existing.getOwnerId();
        PlayerRef playerRef = player.getPlayerRef();
        String ownerName = playerRef == null ? null : playerRef.getUsername();
        return scheduler.schedule(
                npcRef,
                store,
                ownerId,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                resolveOwnerOperation(oldOwnerId, ownerId),
                false,
                "interaction-start-taming:" + UUID.randomUUID(),
                callbacks(store, npcUuid, ownerId, continuation)
        );
    }

    boolean scheduleSetOwner(SetOwnerEffect effect,
                             Ref<EntityStore> npcRef,
                             Store<EntityStore> store,
                             @Nullable Player player,
                             InteractionStateEffects.OwnerAppliedContinuation continuation) {
        if (effect == null || npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        ScheduledOwner owner = resolveOwner(effect, player);
        if (ownerType == null || owner == null) {
            return false;
        }
        OwnerMutationScheduler scheduler = scheduler();
        if (scheduler == null) {
            sendOwnerUnavailable(player, "owner-mutation-service-unavailable");
            return false;
        }
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (npcUuid == null) {
            return false;
        }
        TameworkOwnerComponent existing = store.getComponent(npcRef, ownerType);
        UUID oldOwnerId = existing == null ? null : existing.getOwnerId();
        UUID feedbackPlayerId = player == null ? null : player.getUuid();
        return scheduler.schedule(
                npcRef,
                store,
                owner.id(),
                owner.name(),
                CompanionLifecycleState.ACTIVE,
                resolveOwnerOperation(oldOwnerId, owner.id()),
                false,
                "interaction-set-owner:" + UUID.randomUUID(),
                callbacks(store, npcUuid, feedbackPlayerId, continuation)
        );
    }

    private static OwnerMutationScheduler.MutationCallbacks callbacks(
            Store<EntityStore> originalStore,
            UUID npcUuid,
            @Nullable UUID feedbackPlayerId,
            InteractionStateEffects.OwnerAppliedContinuation continuation
    ) {
        return new OwnerMutationScheduler.MutationCallbacks() {
            private boolean claimDenialSent;

            @Override
            public void onPopulationDenied(CompanionPopulationPreparationResult result) {
                if (feedbackPlayerId != null) {
                    claimDenialSent = sendClaimDenial(originalStore, feedbackPlayerId, result);
                }
            }

            @Override
            public void onDenied(String reason, OwnerPopulationDecision decision) {
                if (feedbackPlayerId != null && !claimDenialSent) {
                    sendOwnerDenial(originalStore, feedbackPlayerId, reason, decision);
                }
            }

            @Override
            public void onApplied(OwnerPopulationDecision decision) {
                LiveTarget target = resolveTarget(originalStore, npcUuid);
                if (target == null) {
                    return;
                }
                Player resolvedPlayer = feedbackPlayerId == null
                        ? null : resolvePlayer(target.store(), feedbackPlayerId);
                continuation.onApplied(target.ref(), target.store(), resolvedPlayer);
            }
        };
    }

    @Nullable
    private static ScheduledOwner resolveOwner(SetOwnerEffect effect, @Nullable Player player) {
        OwnerSource source = effect.getSource();
        if (source == null) {
            return null;
        }
        return switch (source) {
            case Player -> playerOwner(player);
            case None -> new ScheduledOwner(null, null);
            case Custom -> customOwner(effect);
        };
    }

    @Nullable
    private static ScheduledOwner playerOwner(@Nullable Player player) {
        UUID ownerId = player == null ? null : player.getUuid();
        if (ownerId == null) {
            return null;
        }
        PlayerRef ref = player.getPlayerRef();
        return new ScheduledOwner(ownerId, ref == null ? null : ref.getUsername());
    }

    @Nullable
    private static ScheduledOwner customOwner(SetOwnerEffect effect) {
        UUID ownerId = parseCustomOwnerUuid(effect.getUuid());
        if (ownerId == null) {
            return null;
        }
        String ownerName = effect.getName();
        return new ScheduledOwner(ownerId, ownerName);
    }

    @Nullable
    static UUID parseCustomOwnerUuid(@Nullable String uuidText) {
        if (uuidText == null || uuidText.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuidText.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private static OwnerMutationScheduler scheduler() {
        Tamework plugin = Tamework.getInstance();
        return plugin == null ? null : plugin.getOwnerMutationScheduler();
    }

    private static OwnerPopulationOperation resolveOwnerOperation(@Nullable UUID oldOwnerId,
                                                                  @Nullable UUID newOwnerId) {
        if (newOwnerId == null) {
            return oldOwnerId == null
                    ? OwnerPopulationOperation.LIFECYCLE_CHANGE
                    : OwnerPopulationOperation.OWNER_CLEAR;
        }
        if (oldOwnerId == null) {
            return OwnerPopulationOperation.NEW_OWNERSHIP;
        }
        return oldOwnerId.equals(newOwnerId)
                ? OwnerPopulationOperation.LIFECYCLE_CHANGE
                : OwnerPopulationOperation.OWNER_TRANSFER;
    }

    private static void sendOwnerDenial(Store<EntityStore> store,
                                        UUID playerId,
                                        String reason,
                                        @Nullable OwnerPopulationDecision decision) {
        Player player = resolvePlayer(store, playerId);
        if (player == null) {
            return;
        }
        if ("claim-cap-reached".equals(reason)) {
            sendOwnerUnavailable(player, "the claim population cap has been reached");
            return;
        }
        if (decision != null && decision.limit() > 0 && "owner-cap-reached".equals(reason)) {
            long observed = decision.committedCount() + decision.pendingCount();
            OwnerMessageUtil.sendPopulationCapReached(
                    player, saturatingInt(observed), decision.limit(), resolveLimitScope()
            );
            return;
        }
        sendOwnerUnavailable(player, reason);
    }

    private static boolean sendClaimDenial(Store<EntityStore> store,
                                           UUID playerId,
                                           CompanionPopulationPreparationResult result) {
        if (result == null || result.claimDecision() == null
                || !"claim-cap-reached".equals(result.claimDecision().reason())) {
            return false;
        }
        Player player = resolvePlayer(store, playerId);
        if (player == null) {
            return false;
        }
        long current = Math.max(
                0L,
                result.claimDecision().committedPopulation()
                        - result.claimDecision().creditedDepartures()
                        + result.claimDecision().pendingPopulation()
        );
        OwnerMessageUtil.sendClaimPopulationCapReached(
                player,
                saturatingInt(current),
                saturatingInt(result.claimDecision().effectiveCapacity())
        );
        return true;
    }

    private static int saturatingInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static TwGlobalConfig.PerPlayerLimitScope resolveLimitScope() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig.PerPlayerLimitScope configured = config == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : config.getPopulationPerPlayerLimitScope();
        return TameworkRuntimeSettings.populationPerPlayerLimitScope(configured);
    }

    private static void sendOwnerUnavailable(@Nullable Player player, String reason) {
        if (player != null && player.getPlayerRef() != null) {
            player.getPlayerRef().sendMessage(Message.raw(
                    "Ownership could not be changed right now (" + reason + ")."
            ));
        }
    }

    @Nullable
    private static UUID resolveNpcUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        UUIDComponent uuid = uuidType == null ? null : store.getComponent(npcRef, uuidType);
        return uuid == null ? null : uuid.getUuid();
    }

    @Nullable
    private static LiveTarget resolveTarget(Store<EntityStore> originalStore, UUID npcUuid) {
        if (originalStore == null || originalStore.getExternalData() == null || npcUuid == null) {
            return null;
        }
        World world = originalStore.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> liveStore = world.getEntityStore().getStore();
        Ref<EntityStore> liveRef = world.getEntityRef(npcUuid);
        return liveStore == null || liveRef == null || !liveRef.isValid()
                ? null : new LiveTarget(liveRef, liveStore);
    }

    @Nullable
    private static Player resolvePlayer(Store<EntityStore> store, UUID playerId) {
        if (store == null || store.getExternalData() == null || playerId == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerId);
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        return playerRef == null || !playerRef.isValid() || playerType == null
                ? null : world.getEntityStore().getStore().getComponent(playerRef, playerType);
    }

    private record ScheduledOwner(@Nullable UUID id, @Nullable String name) {
    }

    private record LiveTarget(Ref<EntityStore> ref, Store<EntityStore> store) {
    }
}
