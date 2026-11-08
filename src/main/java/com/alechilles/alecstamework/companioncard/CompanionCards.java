package com.alechilles.alecstamework.companioncard;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Connects the interaction runtime, which decides when a card opens and computes its interaction snapshot, to the
 * page service that shows it. Tamework installs the sink at startup and removes it on shutdown.
 */
public final class CompanionCards {
    private static volatile Sink sink;

    private CompanionCards() {
    }

    /** Receives card events on the companion's world thread, outside any ECS system iteration. */
    public interface Sink {
        /** Opens the card for {@code playerRef} on the companion {@code npcRef}. */
        void open(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> npcRef,
                  @Nonnull Store<EntityStore> store, @Nonnull CompanionCardInteractions interactions);

        /** Updates an open card after a request ran; ignored when the viewer has no card open on that companion. */
        void update(@Nonnull Player player, @Nonnull Ref<EntityStore> playerRef, @Nonnull Ref<EntityStore> npcRef,
                    @Nonnull Store<EntityStore> store, @Nonnull CompanionCardInteractions interactions);
    }

    public static void install(@Nullable Sink value) {
        sink = value;
    }

    @Nullable
    public static Sink sink() {
        return sink;
    }
}
