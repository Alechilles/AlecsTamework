package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/**
 * Marks loaded companion bodies dirty when their Tamework components change, so Hytale saves
 * them (spec 6.4). Discrete changes are detected within about two seconds and drift within
 * about a minute. Per tick it does one clock comparison per companion; hashing runs only when
 * a check is due. Writes only through the command buffer (tracker attach) and Dirty.
 */
public final class CompanionChangeDetectorSystem extends EntityTickingSystem<EntityStore> {
    private final Query<EntityStore> query;
    private final ComponentType<EntityStore, CompanionChangeTracker> trackerType;
    private final CompanionFingerprints fingerprints;
    private final LongSupplier clock;
    private final long discreteIntervalMs;
    private final long driftIntervalMs;

    public CompanionChangeDetectorSystem(@Nonnull ComponentType<EntityStore, NPCEntity> npcType,
                                         @Nonnull ComponentType<EntityStore, TameworkOwnerComponent> ownerType,
                                         @Nonnull ComponentType<EntityStore, CompanionChangeTracker> trackerType,
                                         @Nonnull CompanionFingerprints fingerprints,
                                         @Nonnull LongSupplier clock,
                                         long discreteIntervalMs,
                                         long driftIntervalMs) {
        this.query = Query.and(Objects.requireNonNull(npcType, "npcType"), Objects.requireNonNull(ownerType, "ownerType"));
        this.trackerType = Objects.requireNonNull(trackerType, "trackerType");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.discreteIntervalMs = discreteIntervalMs;
        this.driftIntervalMs = driftIntervalMs;
    }

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        long now = clock.getAsLong();
        CompanionChangeTracker tracker = chunk.getComponent(index, trackerType);
        if (tracker == null) {
            long stagger = Math.floorMod(ref.hashCode(), (int) Math.max(1L, discreteIntervalMs));
            CompanionChangeTracker created = new CompanionChangeTracker(discreteIntervalMs, driftIntervalMs, stagger);
            if (created.observe(now, fingerprints.discrete(chunk, index), fingerprints.drift(chunk, index))) {
                CompanionSaves.markChanged(commandBuffer, ref);
            }
            commandBuffer.putComponent(ref, trackerType, created);
            return;
        }
        if (!tracker.isDue(now)) {
            return;
        }
        if (tracker.observe(now, fingerprints.discrete(chunk, index), fingerprints.drift(chunk, index))) {
            CompanionSaves.markChanged(commandBuffer, ref);
        }
    }
}
