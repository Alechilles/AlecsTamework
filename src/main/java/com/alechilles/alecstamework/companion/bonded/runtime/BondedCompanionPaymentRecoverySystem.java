package com.alechilles.alecstamework.companion.bonded.runtime;

import com.alechilles.alecstamework.TameworkBondedCompanionComposition;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Starts payment repair only after the destination Player is live in its store. */
public final class BondedCompanionPaymentRecoverySystem
        extends RefSystem<EntityStore> {
    private final TameworkBondedCompanionComposition composition;

    public BondedCompanionPaymentRecoverySystem(
            @Nonnull TameworkBondedCompanionComposition composition) {
        this.composition = Objects.requireNonNull(composition, "composition");
    }

    @Override
    public void onEntityAdded(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull AddReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        Player player = store.getComponent(
                reference, Player.getComponentType());
        World world = store.getExternalData() == null
                ? null : store.getExternalData().getWorld();
        if (player != null && player.getUuid() != null && world != null) {
            composition.onPlayerPaymentReady(world, player.getUuid());
        }
    }

    @Override
    public void onEntityRemove(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull RemoveReason reason,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        // A later live-player add will retry any retained payment evidence.
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }
}
