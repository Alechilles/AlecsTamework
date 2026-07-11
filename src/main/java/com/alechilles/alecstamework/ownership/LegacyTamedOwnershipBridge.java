package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bridges legacy vanilla-tamed NPCs into Tamework ownership when mods are added mid-playthrough.
 *
 * <p>Adoption is asynchronous and never exposes the requested owner as committed until the
 * scheduler's world-thread apply callback fires. Callers that depend on ownership must continue
 * their work through {@link ClaimContinuation}.
 */
public final class LegacyTamedOwnershipBridge {
    private static final ConcurrentHashMap<UUID, PendingClaim> PENDING = new ConcurrentHashMap<>();

    private LegacyTamedOwnershipBridge() {
    }

    /**
     * Claims ownership without a dependent continuation.
     */
    public static ClaimResult claimForPlayerIfEligible(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        Player player) {
        return claimForPlayerIfEligible(npcRef, store, player, null);
    }

    /**
     * Schedules legacy adoption and invokes the continuation only after the owner component applies.
     */
    public static ClaimResult claimForPlayerIfEligible(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        Player player,
                                                        @Nullable ClaimContinuation continuation) {
        if (npcRef == null || store == null || !npcRef.isValid() || player == null) {
            return ClaimResult.none();
        }
        UUID playerId = player.getUuid();
        UUID npcUuid = resolveNpcUuid(npcRef, store);
        if (playerId == null || npcUuid == null) {
            return ClaimResult.none();
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return ClaimResult.none();
        }
        TameworkOwnerComponent existingOwner = store.getComponent(npcRef, ownerType);
        UUID existingOwnerId = existingOwner == null ? null : existingOwner.getOwnerId();
        String existingOwnerName = existingOwner == null ? null : existingOwner.getOwnerName();
        if (existingOwnerId != null) {
            return ClaimResult.resolved(existingOwnerId, existingOwnerName);
        }
        if (!TamedStateResolver.isTamed(npcRef, store)) {
            return ClaimResult.none();
        }

        PendingClaim pending = PENDING.get(npcUuid);
        if (pending != null) {
            if (pending.ownerId().equals(playerId)) {
                pending.add(continuation);
                return ClaimResult.scheduled();
            }
            sendUnavailable(player, "legacy-adoption-pending-for-another-player");
            return ClaimResult.denied("legacy-adoption-pending-for-another-player");
        }

        Tamework plugin = Tamework.getInstance();
        OwnerMutationScheduler scheduler = plugin == null ? null : plugin.getOwnerMutationScheduler();
        if (scheduler == null) {
            sendUnavailable(player, "owner-mutation-service-unavailable");
            return ClaimResult.denied("owner-mutation-service-unavailable");
        }

        PendingClaim candidate = new PendingClaim(playerId);
        candidate.add(continuation);
        PendingClaim prior = PENDING.putIfAbsent(npcUuid, candidate);
        if (prior != null) {
            if (prior.ownerId().equals(playerId)) {
                prior.add(continuation);
                return ClaimResult.scheduled();
            }
            sendUnavailable(player, "legacy-adoption-pending-for-another-player");
            return ClaimResult.denied("legacy-adoption-pending-for-another-player");
        }
        CompletableFuture.delayedExecutor(30L, TimeUnit.SECONDS).execute(
                () -> PENDING.remove(npcUuid, candidate)
        );

        String ownerName = OwnerNameUtil.resolve(player);
        boolean scheduled = scheduler.schedule(
                npcRef,
                store,
                playerId,
                ownerName,
                CompanionLifecycleState.ACTIVE,
                OwnerPopulationOperation.LEGACY_ADOPTION,
                false,
                "legacy-tamed-adoption:" + npcUuid + ":" + UUID.randomUUID(),
                new OwnerMutationScheduler.MutationCallbacks() {
                    private boolean claimDenialSent;

                    @Override
                    public void onPopulationDenied(CompanionPopulationPreparationResult result) {
                        claimDenialSent = sendClaimDenial(store, playerId, result);
                    }

                    @Override
                    public void onDenied(String reason, OwnerPopulationDecision decision) {
                        PENDING.remove(npcUuid, candidate);
                        if (!claimDenialSent) {
                            sendDenial(store, playerId, reason, decision);
                        }
                    }

                    @Override
                    public void onApplied(OwnerPopulationDecision decision) {
                        PendingClaim completed = PENDING.remove(npcUuid);
                        LiveClaimContext live = resolveLiveContext(store, npcUuid, playerId);
                        if (live == null) {
                            return;
                        }
                        ensureTamedComponent(live.store(), live.npcRef());
                        ClaimResult result = ClaimResult.claimed(playerId, ownerName);
                        PendingClaim callbacks = completed == null ? candidate : completed;
                        callbacks.complete(new ClaimContext(
                                live.npcRef(),
                                live.store(),
                                live.player(),
                                result
                        ));
                    }
                }
        );
        if (!scheduled) {
            PENDING.remove(npcUuid, candidate);
            return ClaimResult.denied("legacy-adoption-schedule-failed");
        }
        return ClaimResult.scheduled();
    }

    /** Resolves owner metadata without mutating NPC state. */
    public static ClaimResult resolveOwner(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return ClaimResult.none();
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType == null) {
            return ClaimResult.none();
        }
        TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);
        return owner == null
                ? ClaimResult.none()
                : ClaimResult.resolved(owner.getOwnerId(), owner.getOwnerName());
    }

    @Nullable
    private static UUID resolveNpcUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        ComponentType<EntityStore, UUIDComponent> type = UUIDComponent.getComponentType();
        UUIDComponent uuid = type == null ? null : store.getComponent(npcRef, type);
        return uuid == null ? null : uuid.getUuid();
    }

    @Nullable
    private static LiveClaimContext resolveLiveContext(Store<EntityStore> originalStore,
                                                       UUID npcUuid,
                                                       UUID playerId) {
        if (originalStore == null || originalStore.getExternalData() == null) {
            return null;
        }
        World world = originalStore.getExternalData().getWorld();
        if (world == null || world.getEntityStore() == null) {
            return null;
        }
        Store<EntityStore> liveStore = world.getEntityStore().getStore();
        Ref<EntityStore> liveNpcRef = world.getEntityRef(npcUuid);
        if (liveStore == null || liveNpcRef == null || !liveNpcRef.isValid()) {
            return null;
        }
        Ref<EntityStore> livePlayerRef = world.getEntityRef(playerId);
        ComponentType<EntityStore, Player> playerType = Player.getComponentType();
        Player livePlayer = livePlayerRef == null || !livePlayerRef.isValid() || playerType == null
                ? null
                : liveStore.getComponent(livePlayerRef, playerType);
        return new LiveClaimContext(liveNpcRef, liveStore, livePlayer);
    }

    private static void ensureTamedComponent(Store<EntityStore> store, Ref<EntityStore> npcRef) {
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType == null) {
            return;
        }
        TameworkTamedComponent tamed = store.getComponent(npcRef, tamedType);
        if (tamed == null || !tamed.isTamed()) {
            store.putComponent(npcRef, tamedType, new TameworkTamedComponent(true));
        }
    }

    private static void sendDenial(Store<EntityStore> store,
                                   UUID playerId,
                                   String reason,
                                   @Nullable OwnerPopulationDecision decision) {
        Player player = resolvePlayer(store, playerId);
        if (player == null) {
            return;
        }
        if ("claim-cap-reached".equals(reason)) {
            sendUnavailable(player, "the claim population cap has been reached");
            return;
        }
        if (decision != null && "owner-cap-reached".equals(reason)) {
            long observed = decision.committedCount() + decision.pendingCount();
            OwnerMessageUtil.sendPopulationCapReached(
                    player,
                    observed >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) observed,
                    decision.limit(),
                    resolveLimitScope()
            );
            return;
        }
        sendUnavailable(player, reason);
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

    @Nullable
    private static Player resolvePlayer(Store<EntityStore> store, UUID playerId) {
        if (store == null || store.getExternalData() == null) {
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

    private static TwGlobalConfig.PerPlayerLimitScope resolveLimitScope() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        TwGlobalConfig.PerPlayerLimitScope configured = config == null
                ? TwGlobalConfig.PerPlayerLimitScope.PER_WORLD
                : config.getPopulationPerPlayerLimitScope();
        return TameworkRuntimeSettings.populationPerPlayerLimitScope(configured);
    }

    private static void sendUnavailable(@Nullable Player player, String reason) {
        if (player != null && player.getPlayerRef() != null) {
            player.getPlayerRef().sendMessage(Message.raw(
                    "Ownership could not be assigned right now (" + reason + ")."
            ));
        }
    }

    /**
     * Continuation invoked on the owning world thread after ownership applies.
     * Callers must capture stable IDs/config only and use the supplied live context.
     */
    @FunctionalInterface
    public interface ClaimContinuation {
        void onApplied(@Nonnull ClaimContext context);
    }

    /** Live world-thread context for ownership-dependent work. */
    public record ClaimContext(@Nonnull Ref<EntityStore> npcRef,
                               @Nonnull Store<EntityStore> store,
                               @Nullable Player player,
                               @Nonnull ClaimResult result) {
    }

    /** Owner resolution/adoption state. */
    public static final class ClaimResult {
        private final UUID ownerId;
        private final String ownerName;
        private final Status status;
        private final String reason;

        private ClaimResult(UUID ownerId, String ownerName, Status status, String reason) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.status = status;
            this.reason = reason;
        }

        public UUID getOwnerId() {
            return ownerId;
        }

        public String getOwnerName() {
            return ownerName;
        }

        public boolean isClaimed() {
            return status == Status.CLAIMED;
        }

        public boolean isScheduled() {
            return status == Status.SCHEDULED;
        }

        public boolean isDenied() {
            return status == Status.DENIED;
        }

        public String getReason() {
            return reason;
        }

        public static ClaimResult none() {
            return new ClaimResult(null, null, Status.NONE, "legacy-adoption-not-applicable");
        }

        static ClaimResult resolved(UUID ownerId, String ownerName) {
            return new ClaimResult(ownerId, ownerName, Status.RESOLVED, "legacy-owner-resolved");
        }

        static ClaimResult scheduled() {
            return new ClaimResult(null, null, Status.SCHEDULED, "legacy-adoption-scheduled");
        }

        static ClaimResult claimed(UUID ownerId, String ownerName) {
            return new ClaimResult(ownerId, ownerName, Status.CLAIMED, "legacy-adoption-applied");
        }

        static ClaimResult denied(String reason) {
            return new ClaimResult(null, null, Status.DENIED, reason);
        }

        private enum Status {
            NONE,
            RESOLVED,
            SCHEDULED,
            CLAIMED,
            DENIED
        }
    }

    private static final class PendingClaim {
        private final UUID ownerId;
        private final CopyOnWriteArrayList<ClaimContinuation> continuations = new CopyOnWriteArrayList<>();

        private PendingClaim(UUID ownerId) {
            this.ownerId = ownerId;
        }

        private UUID ownerId() {
            return ownerId;
        }

        private void add(@Nullable ClaimContinuation continuation) {
            if (continuation != null) {
                continuations.add(continuation);
            }
        }

        private void complete(ClaimContext context) {
            for (ClaimContinuation continuation : continuations) {
                try {
                    continuation.onApplied(context);
                } catch (RuntimeException | LinkageError ignored) {
                    // One optional continuation must not suppress the remaining ownership consumers.
                }
            }
        }
    }

    private record LiveClaimContext(Ref<EntityStore> npcRef,
                                    Store<EntityStore> store,
                                    @Nullable Player player) {
    }
}
