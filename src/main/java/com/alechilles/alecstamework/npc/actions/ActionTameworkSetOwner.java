package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationPreparationResult;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerMutationContext;
import com.alechilles.alecstamework.ownership.OwnerMutationScheduler;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.persistence.health.PersistencePlayerFeedback;
import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Sets owner component data based on the interacting player.
 */
public final class ActionTameworkSetOwner extends TameworkActionBase {
    private final SetOwnerAppliedEffects appliedEffects;

    public ActionTameworkSetOwner(BuilderActionTameworkSetOwner builder, BuilderSupport support) {
        super(builder);
        this.appliedEffects = new SetOwnerAppliedEffects(builder, support);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid() && resolveInteractionPlayer(role, infoProvider, store) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return false;
        }
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return false;
        }
        TameworkOwnerComponent existingOwner = store.getComponent(npcRef, type);
        Tamework plugin = Tamework.getInstance();
        OwnerMutationScheduler scheduler = plugin == null ? null : plugin.getOwnerMutationScheduler();
        if (scheduler == null) {
            sendUnavailable(player, "owner-mutation-service-unavailable");
            return false;
        }

        UUID oldOwnerId = existingOwner == null ? null : existingOwner.getOwnerId();
        String ownerName = OwnerNameUtil.resolve(player);
        String expectedHeldItemId = appliedEffects.captureHeldItemId(player);
        return scheduler.schedule(
                npcRef,
                store,
                playerId,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                resolveOperation(oldOwnerId, playerId),
                false,
                "npc-setowner-action:" + UUID.randomUUID(),
                new OwnerMutationScheduler.MutationCallbacks() {
                    private boolean claimDenialSent;

                    @Override
                    public void onPopulationDenied(CompanionPopulationPreparationResult result) {
                        claimDenialSent = sendClaimDenial(store, playerId, result);
                    }

                    @Override
                    public void onDenied(String reason, OwnerPopulationDecision decision) {
                        if (!claimDenialSent) {
                            sendDenial(store, playerId, reason, decision);
                        }
                    }

                    @Override
                    public void onApplied(OwnerPopulationDecision decision,
                                          String profileId,
                                          OwnerMutationContext context) {
                        appliedEffects.apply(context, playerId, expectedHeldItemId);
                    }
                }
        );
    }

    static OwnerPopulationOperation resolveOperation(UUID oldOwnerId, UUID newOwnerId) {
        if (oldOwnerId == null) {
            return OwnerPopulationOperation.NEW_OWNERSHIP;
        }
        return oldOwnerId.equals(newOwnerId)
                ? OwnerPopulationOperation.LIFECYCLE_CHANGE
                : OwnerPopulationOperation.OWNER_TRANSFER;
    }

    private static void sendDenial(Store<EntityStore> store,
                                   UUID playerId,
                                   String reason,
                                   OwnerPopulationDecision decision) {
        Player livePlayer = resolvePlayer(store, playerId);
        if (livePlayer == null) {
            return;
        }
        if ("claim-cap-reached".equals(reason)) {
            sendUnavailable(livePlayer, "the claim population cap has been reached");
            return;
        }
        if (decision != null && decision.limit() > 0 && "owner-cap-reached".equals(reason)) {
            long observed = decision.committedCount() + decision.pendingCount();
            OwnerMessageUtil.sendPopulationCapReached(
                    livePlayer,
                    observed >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) observed,
                    decision.limit(),
                    resolveConfigScope()
            );
            return;
        }
        if (livePlayer.getPlayerRef() != null && decision != null
                && decision.persistenceAvailability() != null
                && !decision.persistenceAvailability().allowed()) {
            livePlayer.getPlayerRef().sendMessage(Message.raw(PersistencePlayerFeedback.resolve(
                    livePlayer,
                    PersistenceDomain.TAMING_OWNERSHIP,
                    decision.persistenceAvailability()
            )));
            return;
        }
        sendUnavailable(livePlayer, reason);
    }

    private static boolean sendClaimDenial(Store<EntityStore> store,
                                           UUID playerId,
                                           CompanionPopulationPreparationResult result) {
        if (result == null
                || result.claimDecision() == null
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

    private static TwGlobalConfig.PerPlayerLimitScope resolveConfigScope() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig.PerPlayerLimitScope configured = config == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : config.getPopulationPerPlayerLimitScope();
        return TameworkRuntimeSettings.populationPerPlayerLimitScope(configured);
    }

    private static void sendUnavailable(Player player, String reason) {
        if (player != null && player.getPlayerRef() != null) {
            player.getPlayerRef().sendMessage(Message.raw(
                    "Ownership could not be assigned right now (" + reason + ")."
            ));
        }
    }

    private static Player resolvePlayer(Store<EntityStore> store, UUID playerId) {
        if (store == null || playerId == null || store.getExternalData() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Ref<EntityStore> playerRef = world.getEntityRef(playerId);
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        return playerRef == null || !playerRef.isValid() || playerType == null
                ? null
                : world.getEntityStore().getStore().getComponent(playerRef, playerType);
    }

}
