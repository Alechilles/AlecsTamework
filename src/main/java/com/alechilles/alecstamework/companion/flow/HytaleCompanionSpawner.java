package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.live.CompanionRespawn;
import com.alechilles.alecstamework.companion.live.CompanionSaves;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * Puts a committed companion back into its destination world from its snapshot (spec 6.5).
 *
 * <p>{@link #spawn} only resolves the world and queues one task, so it is safe to call from the
 * companion writer thread mid-flush. The entity work runs inside that task on the destination
 * world thread, between ticks, as {@code Store#addEntity} requires.
 *
 * <p>Contract with {@link RestoreFlow}: the future completes false (never exceptionally) when no
 * body was added, and then none is added later. It completes only from the queued task or when
 * the task could not be queued, so a completed false is final. A world that stops after the task
 * was queued still runs it: World shutdown drains its task queue before it stops accepting tasks.
 */
public final class HytaleCompanionSpawner implements RestoreFlow.Spawner {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static final AtomicLong LAST_WARN_MS = new AtomicLong(Long.MIN_VALUE);

    private final CompanionRespawn respawn;
    private final Function<UUID, CompanionRecord> currentRecord;

    /**
     * @param stampType     the registered companion stamp type
     * @param currentRecord reads the index's current record for a profile id (null when absent);
     *                      the world task re-checks the committed revision with it
     */
    public HytaleCompanionSpawner(@Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
                                  @Nonnull Function<UUID, CompanionRecord> currentRecord) {
        this.respawn = new CompanionRespawn(CompanionRespawn.Types.production(Objects.requireNonNull(stampType, "stampType")));
        this.currentRecord = Objects.requireNonNull(currentRecord, "currentRecord");
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> spawn(@Nonnull CompanionRecord committed, @Nonnull SnapshotEnvelope snapshot,
                                            @Nonnull RestoreFlow.Destination destination,
                                            @Nonnull RestoreRules.Reason reason) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        World world = Universe.get().getWorld(destination.world());
        if (world == null) {
            warn(committed.profileId(), "destination world " + destination.world() + " is not loaded", null);
            done.complete(false);
            return done;
        }
        try {
            world.execute(() -> done.complete(spawnOnWorldThread(world, committed, snapshot, destination, reason)));
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            warn(committed.profileId(), "world " + destination.world() + " is not accepting tasks", notAccepting);
            done.complete(false);
        }
        return done;
    }

    private boolean spawnOnWorldThread(World world, CompanionRecord committed, SnapshotEnvelope snapshot,
                                       RestoreFlow.Destination destination, RestoreRules.Reason reason) {
        UUID profileId = committed.profileId();
        try {
            CompanionRecord now = currentRecord.apply(profileId);
            if (now == null || now.revision() != committed.revision()) {
                // A newer change to the record won after the commit; it owns the outcome.
                return false;
            }
            UUID npcUuid = committed.currentNpcUuid();
            if (npcUuid == null) {
                warn(profileId, "committed record has no NPC UUID", null);
                return false;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            BsonDocument doc = CompanionRespawn.stripDocument(CompanionSnapshots.entity(snapshot));
            doc = SnapshotPatch.rebaseAlarms(doc, gameTimeDelta(CompanionWorldTime.gameTimeMs(store),
                    CompanionSnapshots.gameTimeMs(snapshot)));
            if (reason == RestoreRules.Reason.REVIVE) {
                doc = SnapshotPatch.forRevive(doc);
            }
            Holder<EntityStore> holder = EntityStore.REGISTRY.deserialize(doc);
            if (holder == null) {
                warn(profileId, "snapshot did not deserialize", null);
                return false;
            }
            Vector3d position = new Vector3d(destination.x(), destination.y(), destination.z());
            respawn.prepare(holder, position, new Rotation3f(destination.pitch(), destination.yaw(), 0.0f),
                    npcUuid, profileId, committed.generation());
            repointAvatarFlightOrigin(holder, destination);
            Ref<EntityStore> ref = store.addEntity(holder, AddReason.LOAD);
            if (ref == null) {
                warn(profileId, "the world rejected the respawned body", null);
                return false;
            }
            CompanionSaves.markChanged(store, ref);
            if (reason == RestoreRules.Reason.REVIVE) {
                // Trait modifiers first, so full health uses the modified maximum.
                CompanionStatModifierService.applyTraitModifiers(ref, store);
                CompanionHealthStateService.applyStoredHealthPercent(ref, store, 100.0);
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            warn(profileId, "respawn failed", failure);
            return false;
        }
    }

    /**
     * Destination game time minus snapshot game time. {@link CompanionWorldTime#gameTimeMs} returns
     * 0 when a world has no time resource; then the offset is unknown and alarms stay as they are.
     */
    private static long gameTimeDelta(long destinationGameTimeMs, long snapshotGameTimeMs) {
        if (destinationGameTimeMs == 0L || snapshotGameTimeMs == 0L) {
            return 0L;
        }
        return destinationGameTimeMs - snapshotGameTimeMs;
    }

    /**
     * A body snapshotted while parked by avatar flight keeps its source component, whose recovery
     * restores the role, visibility and collision once the old session is gone. That recovery
     * also moves the body to the recorded origin, so the origin must be the destination.
     */
    private static void repointAvatarFlightOrigin(Holder<EntityStore> holder, RestoreFlow.Destination destination) {
        ComponentType<EntityStore, AvatarFlightSourceComponent> type = AvatarFlightSourceComponent.getComponentType();
        AvatarFlightSourceComponent source = type == null ? null : holder.getComponent(type);
        if (source != null) {
            source.captureOrigin(destination.x(), destination.y(), destination.z(),
                    destination.yaw(), destination.pitch(), 0.0f);
        }
    }

    private static void warn(UUID profileId, String what, @Nullable Throwable cause) {
        long now = System.currentTimeMillis();
        long last = LAST_WARN_MS.get();
        if (last != Long.MIN_VALUE && now - last < WARN_INTERVAL_MS) {
            return;
        }
        if (!LAST_WARN_MS.compareAndSet(last, now)) {
            return;
        }
        HytaleLogger.Api log = LOGGER.at(Level.WARNING);
        if (cause != null) {
            log = log.withCause(cause);
        }
        log.log("Companion respawn did not complete for profile %s: %s", profileId, what);
    }
}
