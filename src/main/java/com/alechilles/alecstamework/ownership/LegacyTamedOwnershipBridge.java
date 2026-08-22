package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bridges legacy vanilla-tamed NPCs into Tamework ownership when mods are added mid-playthrough.
 *
 * <p>Callers invoke this bridge on the owning world thread. Adoption applies the released live
 * cap and owner component directly; no durable reservation or cross-world lookup participates.
 */
public final class LegacyTamedOwnershipBridge {
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
     * Applies eligible legacy adoption and then invokes the optional continuation synchronously.
     */
    public static ClaimResult claimForPlayerIfEligible(Ref<EntityStore> npcRef,
                                                        Store<EntityStore> store,
                                                        Player player,
                                                        @Nullable ClaimContinuation continuation) {
        if (npcRef == null || store == null || !npcRef.isValid() || player == null) {
            return ClaimResult.none();
        }
        UUID playerId = player.getUuid();
        if (playerId == null || resolveNpcUuid(npcRef, store) == null) {
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
        OwnerPopulationCapService.Decision cap =
                OwnerPopulationCapService.evaluateAcquisition(store, playerId);
        if (!cap.allowed()) {
            sendCapDenial(player, cap);
            return ClaimResult.denied(cap.reason());
        }
        String ownerName = OwnerNameUtil.resolve(player);
        store.putComponent(
                npcRef,
                ownerType,
                new TameworkOwnerComponent(playerId, ownerName)
        );
        ensureTamedComponent(store, npcRef);
        ClaimResult result = ClaimResult.claimed(playerId, ownerName);
        publishClaimedTame(npcRef, store, playerId);
        invokeContinuation(continuation, npcRef, store, player, result);
        return result;
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

    private static void publishClaimedTame(
            Ref<EntityStore> npcRef,
            Store<EntityStore> store,
            UUID ownerId
    ) {
        NPCEntity npc = NPCEntity.getComponentType() == null
                ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        publishClaimedTame(
                ownerId,
                resolveNpcUuid(npcRef, store),
                resolveRoleId(npc)
        );
    }

    @Nullable
    private static String resolveRoleId(@Nullable NPCEntity npc) {
        if (npc == null) {
            return null;
        }
        String roleId = npc.getRoleName();
        if (roleId != null && !roleId.isBlank()) {
            return roleId;
        }
        return npc.getRole() == null ? null : npc.getRole().getRoleName();
    }

    static void publishClaimedTame(
            UUID ownerId,
            UUID companionId,
            String roleId
    ) {
        ActivityRuntime.publishTame(
                UUID.randomUUID(), roleId, ownerId, companionId);
    }

    private static void sendCapDenial(
            @Nonnull Player player,
            @Nonnull OwnerPopulationCapService.Decision decision
    ) {
        if ("owner-cap-reached".equals(decision.reason())) {
            OwnerMessageUtil.sendPopulationCapReached(
                    player,
                    decision.currentCount(),
                    decision.limit(),
                    decision.scope()
            );
            return;
        }
        sendUnavailable(player, decision.reason());
    }

    private static void sendUnavailable(@Nullable Player player, String reason) {
        if (player != null && player.getPlayerRef() != null) {
            player.getPlayerRef().sendMessage(Message.raw(
                    "Ownership could not be assigned right now (" + reason + ")."
            ));
        }
    }

    private static void invokeContinuation(
            @Nullable ClaimContinuation continuation,
            @Nonnull Ref<EntityStore> npcRef,
            @Nonnull Store<EntityStore> store,
            @Nonnull Player player,
            @Nonnull ClaimResult result
    ) {
        if (continuation == null) {
            return;
        }
        try {
            continuation.onApplied(new ClaimContext(npcRef, store, player, result));
        } catch (RuntimeException | LinkageError ignored) {
            // Optional consumers cannot roll back a completed owner assignment.
        }
    }

    /** Continuation invoked on the owning world thread after ownership applies. */
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

}
