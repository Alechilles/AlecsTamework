package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionRespawn;
import com.alechilles.alecstamework.companion.live.CompanionSaves;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
import java.util.function.Consumer;
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
 * the task could not be queued, so a completed false is final. If the world stops after the task
 * was queued: a task offered after the world's last queue drain may never run, so the future
 * stays pending and no body is added; a task drained after the chunk store shut down can add a
 * body that is never saved (a later Recover still restores it).
 */
public final class HytaleCompanionSpawner implements RestoreFlow.Spawner {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final long WARN_INTERVAL_MS = 10_000L;
    private static final AtomicLong LAST_WARN_MS = new AtomicLong(Long.MIN_VALUE);

    private final CompanionRespawn respawn;
    private final Function<UUID, CompanionRecord> currentRecord;
    private final CompanionSnapshots snapshots = CompanionSnapshots.production();
    private final Consumer<SnapshotEnvelope> queueSnapshot;
    private final Consumer<UUID> deleteSnapshot;
    private final ComponentType<EntityStore, TameworkCompanionComponent> stampType;

    /**
     * @param stampType     the registered companion stamp type
     * @param currentRecord reads the index's current record for a profile id (null when absent);
     *                      the world task re-checks the committed revision with it
     * @param queueSnapshot queues the snapshot taken of each restored body to the writer
     * @param deleteSnapshot queues the deletion of a profile's snapshot; used after an unowned spawn,
     *                      whose body is untracked and has no snapshot to keep
     */
    public HytaleCompanionSpawner(@Nonnull ComponentType<EntityStore, TameworkCompanionComponent> stampType,
                                  @Nonnull Function<UUID, CompanionRecord> currentRecord,
                                  @Nonnull Consumer<SnapshotEnvelope> queueSnapshot,
                                  @Nonnull Consumer<UUID> deleteSnapshot) {
        this.stampType = Objects.requireNonNull(stampType, "stampType");
        this.respawn = new CompanionRespawn(CompanionRespawn.Types.production(stampType));
        this.currentRecord = Objects.requireNonNull(currentRecord, "currentRecord");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.deleteSnapshot = Objects.requireNonNull(deleteSnapshot, "deleteSnapshot");
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

    /**
     * Spawns an unowned coop resident from its inline entity document (spec 8.9): no stamp, owner,
     * command-link owner or record, the stored tamed state kept, a fresh NPC UUID, added with
     * {@code AddReason.LOAD}. Alarms are not re-based: the document carries no game time, and a
     * coop releases into its own world, whose clock kept running. Safe to call from any thread;
     * the work runs on the destination world thread. Completes true exactly when the body is in
     * the store, false (never exceptionally) when none was added.
     */
    @Nonnull
    public CompletableFuture<Boolean> spawnUnowned(@Nonnull BsonDocument entity,
                                                   @Nonnull RestoreFlow.Destination destination) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        UUID npcUuid = UUID.randomUUID();
        World world = Universe.get().getWorld(destination.world());
        if (world == null) {
            warn(npcUuid, "unowned resident: destination world " + destination.world() + " is not loaded", null);
            done.complete(false);
            return done;
        }
        try {
            world.execute(() -> done.complete(spawnUnownedOnWorldThread(world, entity, destination, npcUuid)));
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            warn(npcUuid, "unowned resident: world " + destination.world() + " is not accepting tasks", notAccepting);
            done.complete(false);
        }
        return done;
    }

    /** As {@link #spawnOnWorldThread}, for an unowned body: true exactly when it is in the store. */
    private boolean spawnUnownedOnWorldThread(World world, BsonDocument entity, RestoreFlow.Destination destination,
                                              UUID npcUuid) {
        Ref<EntityStore> ref = null;
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            Holder<EntityStore> holder = EntityStore.REGISTRY.deserialize(CompanionRespawn.stripDocument(entity));
            if (holder == null) {
                warn(npcUuid, "unowned resident did not deserialize", null);
                return false;
            }
            Vector3d position = new Vector3d(destination.x(), destination.y(), destination.z());
            // prepare() stamps the body; stripOwnership() removes the stamp again.
            respawn.prepare(holder, position, new Rotation3f(destination.pitch(), destination.yaw(), 0.0f),
                    npcUuid, npcUuid, 0L);
            repointAvatarFlightOrigin(holder, destination);
            stripOwnership(holder, stampType);
            ref = new Ref<>(store);
            if (store.addEntity(holder, ref, AddReason.LOAD) == null) {
                warn(npcUuid, "the world rejected the unowned resident", null);
                return false;
            }
        } catch (RuntimeException | LinkageError failure) {
            boolean added = ref != null && ref.isValid();
            warn(npcUuid, added ? "an on-add step failed after the unowned resident was added"
                    : "unowned resident spawn failed", failure);
            if (!added) {
                return false;
            }
        }
        try {
            CompanionSaves.markChanged(ref.getStore(), ref);
        } catch (RuntimeException | LinkageError failure) {
            warn(npcUuid, "a step after the unowned resident was added failed", failure);
        }
        return true;
    }

    /**
     * Returns true exactly when the new body is in the store. The ref is allocated before the add
     * because {@code Store#addEntity} inserts the entity before it runs the on-add systems and
     * consumes their buffer; a throw from those leaves a live body, which must report true.
     */
    private boolean spawnOnWorldThread(World world, CompanionRecord committed, SnapshotEnvelope snapshot,
                                       RestoreFlow.Destination destination, RestoreRules.Reason reason) {
        UUID profileId = committed.profileId();
        boolean unowned = committed.location().kind() == LocationKind.RELEASED;
        Ref<EntityStore> ref = null;
        long worldGameTimeMs = 0L;
        try {
            CompanionRecord now = currentRecord.apply(profileId);
            if (now == null || now.revision() != committed.revision()) {
                // A newer change to the record won after the commit; it owns the outcome.
                return false;
            }
            // An unowned tombstone carries no NPC UUID: the body is untracked, so it gets a fresh one.
            UUID npcUuid = unowned ? UUID.randomUUID() : committed.currentNpcUuid();
            if (npcUuid == null) {
                warn(profileId, "committed record has no NPC UUID", null);
                return false;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            BsonDocument doc = CompanionRespawn.stripDocument(CompanionSnapshots.entity(snapshot));
            worldGameTimeMs = CompanionWorldTime.gameTimeMs(store);
            doc = SnapshotPatch.rebaseAlarms(doc, gameTimeDelta(worldGameTimeMs, CompanionSnapshots.gameTimeMs(snapshot)));
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
            applyOwnership(holder, committed, unowned, stampType);
            ref = new Ref<>(store);
            if (store.addEntity(holder, ref, AddReason.LOAD) == null) {
                warn(profileId, "the world rejected the respawned body", null);
                return false;
            }
        } catch (RuntimeException | LinkageError failure) {
            boolean added = ref != null && ref.isValid();
            warn(profileId, added ? "an on-add step failed after the body was added" : "respawn failed", failure);
            if (!added) {
                return false;
            }
        }
        finishAddedBody(ref, ref.getStore(), committed, world.getName(), worldGameTimeMs, reason, snapshots,
                queueSnapshot, !unowned);
        if (unowned) {
            // The body is untracked: no later recall or Recover may restore this profile from a snapshot.
            try {
                deleteSnapshot.accept(profileId);
            } catch (RuntimeException failure) {
                warn(profileId, "the snapshot of an unowned spawn could not be queued for deletion", failure);
            }
        }
        return true;
    }

    /**
     * Makes the decoded body match the committed record before it is added. An owned record
     * writes its owner, tamed and command-link owner into the body, which is a no-op for recall
     * and revive where they already match. An unowned release spawns without the owner component
     * and stamp and with no command-link owner or tools; clearing the owner does not untame, so the
     * snapshot's tamed flag is kept.
     */
    private static void applyOwnership(Holder<EntityStore> holder, CompanionRecord committed, boolean unowned,
                                       ComponentType<EntityStore, TameworkCompanionComponent> stampType) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null ? null : holder.getComponent(linksType);
        UUID owner = committed.ownerUuid();
        if (unowned) {
            stripOwnership(holder, stampType);
            return;
        }
        if (owner == null) {
            return;
        }
        if (ownerType != null) {
            holder.putComponent(ownerType, new TameworkOwnerComponent(owner, committed.ownerName()));
        }
        ComponentType<EntityStore, TameworkTamedComponent> tamedType = TameworkTamedComponent.getComponentType();
        if (tamedType != null) {
            holder.putComponent(tamedType, new TameworkTamedComponent(true));
        }
        if (links != null) {
            holder.putComponent(linksType, new TameworkCommandLinksComponent(owner, links.getToolIds(),
                    links.getHomePosition()));
        }
    }

    /** Removes the owner and stamp and clears the command-link owner and tools; the tamed flag stays. */
    private static void stripOwnership(Holder<EntityStore> holder,
                                       ComponentType<EntityStore, TameworkCompanionComponent> stampType) {
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        if (ownerType != null) {
            holder.tryRemoveComponent(ownerType);
        }
        holder.tryRemoveComponent(stampType);
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        TameworkCommandLinksComponent links = linksType == null ? null : holder.getComponent(linksType);
        if (links != null) {
            holder.putComponent(linksType, new TameworkCommandLinksComponent(null, new String[0],
                    links.getHomePosition()));
        }
    }

    /**
     * Steps after the add, on the body's world thread. A failure here is logged; the body is live,
     * so the spawn still counts.
     */
    static void finishAddedBody(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull CompanionRecord committed, @Nonnull String worldName,
                                long worldGameTimeMs, @Nonnull RestoreRules.Reason reason, @Nonnull CompanionSnapshots snapshots,
                                @Nonnull Consumer<SnapshotEnvelope> queueSnapshot) {
        finishAddedBody(ref, store, committed, worldName, worldGameTimeMs, reason, snapshots, queueSnapshot, true);
    }

    /** As above; {@code snapshotBody} is false for an unowned spawn, whose body is not tracked. */
    static void finishAddedBody(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull CompanionRecord committed, @Nonnull String worldName,
                                long worldGameTimeMs, @Nonnull RestoreRules.Reason reason, @Nonnull CompanionSnapshots snapshots,
                                @Nonnull Consumer<SnapshotEnvelope> queueSnapshot, boolean snapshotBody) {
        UUID profileId = committed.profileId();
        try {
            CompanionSaves.markChanged(store, ref);
            if (reason == RestoreRules.Reason.REVIVE) {
                // Trait modifiers first, so full health uses the modified maximum.
                CompanionStatModifierService.applyTraitModifiers(ref, store);
                CompanionHealthStateService.applyStoredHealthPercent(ref, store, 100.0);
            }
        } catch (RuntimeException | LinkageError failure) {
            warn(profileId, "a step after the body was added failed", failure);
        }
        if (!snapshotBody) {
            return;
        }
        // Snapshot the new body at once: after a revive the stored snapshot is the death one,
        // which no later recall or Recover may use. Runs after the revive reset so it is captured.
        try {
            SnapshotEnvelope fresh = snapshots.capture(ref, store, profileId, committed.generation(), worldName,
                    worldGameTimeMs);
            if (fresh == null) {
                warn(profileId, "the restored body could not be snapshotted", null);
            } else {
                queueSnapshot.accept(fresh);
            }
        } catch (RuntimeException | LinkageError failure) {
            warn(profileId, "the restored body could not be snapshotted", failure);
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
