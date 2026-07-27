package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.runtime.player
        .HytalePlayerDurabilityBarrier;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Production escrow durability backed by the exact player's save barrier. */
final class HytaleBondedCompanionEscrowDurability
        implements BondedCompanionEscrowDurability {
    private final HytalePlayerDurabilityBarrier delegate;

    HytaleBondedCompanionEscrowDurability(
            World world, Store<EntityStore> store,
            String worldKey, UUID ownerUuid) {
        delegate = new HytalePlayerDurabilityBarrier(
                world, store, worldKey, ownerUuid);
    }

    @Override
    public CompletionStage<HytalePlayerDurabilityBarrier.SaveResult>
            saveActor() {
        return delegate.saveActor();
    }

    @Override
    public <T> CompletionStage<T> resumeOnWorldThread(
            Supplier<CompletionStage<T>> continuation,
            Supplier<T> unavailable) {
        return delegate.resumeOnWorldThread(continuation, unavailable);
    }
}
