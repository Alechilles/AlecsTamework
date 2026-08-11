package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.ownership.LegacyTamedOwnershipBridge;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Resolves and resumes interaction work after asynchronous legacy-owner adoption. */
final class InteractionLegacyAdoptionService {
    private final Consumer<String> debugLogger;

    InteractionLegacyAdoptionService(@Nonnull Consumer<String> debugLogger) {
        this.debugLogger = debugLogger;
    }

    @Nonnull
    Attempt attempt(@Nonnull Ref<EntityStore> npcRef,
                    @Nonnull Store<EntityStore> store,
                    @Nonnull Player player,
                    @Nonnull Role fallbackRole,
                    @Nonnull LiveContinuation continuation) {
        LegacyTamedOwnershipBridge.ClaimResult result =
                LegacyTamedOwnershipBridge.claimForPlayerIfEligible(
                        npcRef,
                        store,
                        player,
                        context -> resume(context, fallbackRole, continuation)
                );
        if (result.isScheduled()) {
            debugLogger.accept("TameworkInteract: legacy ownership adoption scheduled.");
            return Attempt.pending();
        }
        return result.isDenied() ? Attempt.denied() : Attempt.continueNow();
    }

    private void resume(LegacyTamedOwnershipBridge.ClaimContext context,
                        Role fallbackRole,
                        LiveContinuation continuation) {
        if (context == null || context.player() == null || context.player().getUuid() == null) {
            return;
        }
        Player livePlayer = context.player();
        Ref<EntityStore> livePlayerRef = livePlayer.getWorld() == null
                ? null
                : livePlayer.getWorld().getEntityRef(livePlayer.getUuid());
        if (livePlayerRef == null || !livePlayerRef.isValid()) {
            return;
        }
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        NPCEntity npc = npcType == null ? null : context.store().getComponent(context.npcRef(), npcType);
        Role liveRole = npc != null && npc.getRole() != null ? npc.getRole() : fallbackRole;
        if (liveRole == null || NpcSupportAccess.state(liveRole, context.npcRef(), context.store()) == null) {
            return;
        }
        debugLogger.accept("TameworkInteract: applied legacy ownership; resuming interaction.");
        continuation.resume(new LiveContext(
                context.npcRef(), context.store(), livePlayerRef, livePlayer, liveRole
        ));
    }

    @FunctionalInterface
    interface LiveContinuation {
        void resume(@Nonnull LiveContext context);
    }

    record LiveContext(@Nonnull Ref<EntityStore> npcRef,
                       @Nonnull Store<EntityStore> store,
                       @Nonnull Ref<EntityStore> playerRef,
                       @Nonnull Player player,
                       @Nonnull Role role) {
    }

    record Attempt(boolean handled, boolean succeeded) {
        private static Attempt continueNow() {
            return new Attempt(false, false);
        }

        private static Attempt pending() {
            return new Attempt(true, true);
        }

        private static Attempt denied() {
            return new Attempt(true, false);
        }
    }
}
