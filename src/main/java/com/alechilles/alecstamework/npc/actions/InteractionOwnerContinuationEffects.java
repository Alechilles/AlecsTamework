package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.Effects;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.SetOwnerEffect;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Coordinates live-context continuations after owner mutations complete. */
final class InteractionOwnerContinuationEffects {
    private final ActionTameworkInteract owner;
    private final InteractionStateEffects stateEffects;

    InteractionOwnerContinuationEffects(@Nonnull ActionTameworkInteract owner,
                                        @Nonnull InteractionStateEffects stateEffects) {
        this.owner = owner;
        this.stateEffects = stateEffects;
    }

    @Nonnull
    OwnerEffectAttempt applyOwnerEffect(@Nullable Effects effects,
                                        @Nonnull Ref<EntityStore> npcRef,
                                        @Nonnull Store<EntityStore> store,
                                        @Nullable Player player,
                                        @Nullable Role fallbackRole,
                                        @Nonnull LiveEffectContinuation continuation) {
        SetOwnerEffect setOwner = effects == null ? null : effects.getSetOwner();
        if (setOwner == null) {
            return OwnerEffectAttempt.absent();
        }
        boolean applied = stateEffects.applySetOwner(
                setOwner,
                npcRef,
                store,
                player,
                (liveNpcRef, liveStore, livePlayer) -> {
                    Role liveRole = resolveLiveRole(liveNpcRef, liveStore, fallbackRole);
                    continuation.apply(
                            liveNpcRef,
                            liveStore,
                            livePlayer,
                            liveRole,
                            refreshContext(livePlayer, liveRole)
                    );
                }
        );
        return OwnerEffectAttempt.present(applied);
    }

    boolean applyStartTaming(@Nonnull Ref<EntityStore> npcRef,
                             @Nonnull Store<EntityStore> store,
                             @Nullable Player player,
                             @Nullable InteractionStateEffects.OwnerAppliedContinuation continuation) {
        return stateEffects.applyStartTaming(npcRef, store, player, continuation);
    }

    @Nullable
    Role resolveLiveRole(@Nonnull Ref<EntityStore> npcRef,
                         @Nonnull Store<EntityStore> store,
                         @Nullable Role fallback) {
        NPCEntity npc = NPCEntity.getComponentType() == null
                ? null
                : store.getComponent(npcRef, NPCEntity.getComponentType());
        return npc != null && npc.getRole() != null ? npc.getRole() : fallback;
    }

    @Nonnull
    InteractionContextSnapshot refreshContext(@Nullable Player player, @Nullable Role role) {
        Ref<EntityStore> playerRef = player == null || player.getUuid() == null || player.getWorld() == null
                ? null
                : player.getWorld().getEntityRef(player.getUuid());
        return owner.buildContextSnapshot(player, playerRef, role);
    }

    @FunctionalInterface
    interface LiveEffectContinuation {
        void apply(@Nonnull Ref<EntityStore> npcRef,
                   @Nonnull Store<EntityStore> store,
                   @Nullable Player player,
                   @Nullable Role role,
                   @Nonnull InteractionContextSnapshot context);
    }

    record OwnerEffectAttempt(boolean present, boolean applied) {
        private static OwnerEffectAttempt absent() {
            return new OwnerEffectAttempt(false, false);
        }

        private static OwnerEffectAttempt present(boolean applied) {
            return new OwnerEffectAttempt(true, applied);
        }
    }
}
