package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.avatarflight.AvatarFlightSourceComponent;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.live.CompanionRespawn;
import com.alechilles.alecstamework.companion.live.CompanionSaves;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.TameworkCompanionComponent;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.alechilles.alecstamework.items.CoopResidentStateRestorer;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.compat.NpcDisplayNameAccess;
import com.alechilles.alecstamework.npc.progression.CompanionHealthStateService;
import com.alechilles.alecstamework.npc.progression.CompanionModelAttachmentService;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionBootstrapService;
import com.alechilles.alecstamework.npc.spawning.CompanionSpawnAuthorityService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.progression.CompanionStatModifierService;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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
 * Puts a committed companion back into its destination world from its snapshot (spec 6.5). A
 * provisioned bonded companion that never had a snapshot written is handed none: its body is
 * built from the record's role, stamped before it is added, and snapshotted once it is in the
 * store (plan 6 R16). A companion imported from 3.x or 4.x arrives with a format 0 snapshot (plan 7
 * R4): its body is built from the role in the same way, with the imported state written into it
 * before it is added, and the snapshot taken afterwards replaces the format 0 one.
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
    private static final CoopResidentStateRestorer IMPORTED_STATE_RESTORER = new CoopResidentStateRestorer();

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
    public CompletableFuture<Boolean> spawn(@Nonnull CompanionRecord committed, @Nullable SnapshotEnvelope snapshot,
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
            world.execute(() -> done.complete(
                    snapshot == null || snapshot.format() == SnapshotEnvelope.FORMAT_IMPORTED_STATE
                            ? spawnFromRoleOnWorldThread(world, committed, snapshot, destination, reason)
                            : spawnOnWorldThread(world, committed, snapshot, destination, reason)));
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
     * the work runs on the destination world thread. {@code onAdded} runs in that same task right
     * after the body is in the store (a coop clears the resident's slot there, so the body and its
     * inline copy never both persist); its failure is logged and does not undo the spawn.
     * Completes true exactly when the body is in the store, false (never exceptionally) when none
     * was added.
     */
    @Nonnull
    public CompletableFuture<Boolean> spawnUnowned(@Nonnull BsonDocument entity,
                                                   @Nonnull RestoreFlow.Destination destination,
                                                   @Nonnull Runnable onAdded) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        UUID npcUuid = UUID.randomUUID();
        World world = Universe.get().getWorld(destination.world());
        if (world == null) {
            warn(npcUuid, "unowned resident: destination world " + destination.world() + " is not loaded", null);
            done.complete(false);
            return done;
        }
        try {
            world.execute(() -> done.complete(spawnUnownedOnWorldThread(world, entity, destination, npcUuid,
                    onAdded)));
        } catch (RuntimeException notAccepting) {
            // World#execute throws when the world no longer accepts tasks; the task was not queued.
            warn(npcUuid, "unowned resident: world " + destination.world() + " is not accepting tasks", notAccepting);
            done.complete(false);
        }
        return done;
    }

    /** As {@link #spawnOnWorldThread}, for an unowned body: true exactly when it is in the store. */
    private boolean spawnUnownedOnWorldThread(World world, BsonDocument entity, RestoreFlow.Destination destination,
                                              UUID npcUuid, Runnable onAdded) {
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
        try {
            onAdded.run();
        } catch (RuntimeException | LinkageError failure) {
            warn(npcUuid, "the step after the unowned resident was added failed", failure);
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
            if (!RestoreFlow.sameHolder(committed, now)) {
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
            deleteUnownedSnapshot(profileId);
        }
        return true;
    }

    /**
     * Builds a body from the committed record's role: the first body of a provisioned bonded
     * companion ({@code imported} null), or the first 5.0 body of an imported companion, whose
     * format 0 state is written into the holder (plan 7 R4). The NPC UUID, the imported state,
     * the stamp, the owner and the tamed flag are written in the engine's pre-add callback, in
     * that order, so the body is never in the store without them, the record's owner wins over
     * the imported one, and the tame and adoption systems see a stamped body. Returns true
     * exactly when that prepared body is in the store. A body that reached the store before the
     * callback finished is removed again, so an unreadable state or a failed apply leaves nothing
     * behind and {@link RestoreFlow} puts the record back with its format 0 snapshot untouched.
     *
     * <p>An unowned release (a {@code RELEASED} tombstone) gets a fresh NPC UUID, no stamp and no
     * owner, keeps the imported tamed flag, and is not snapshotted, as for format 1.
     *
     * <p>Imported alarm and breeding deadlines are world time and are kept as stored: the state
     * records no game time to re-base them from.
     */
    private boolean spawnFromRoleOnWorldThread(World world, CompanionRecord committed,
                                               @Nullable SnapshotEnvelope imported,
                                               RestoreFlow.Destination destination, RestoreRules.Reason reason) {
        UUID profileId = committed.profileId();
        boolean unowned = committed.location().kind() == LocationKind.RELEASED;
        // An unowned tombstone carries no NPC UUID: the body is untracked, so it gets a fresh one.
        UUID npcUuid = unowned ? UUID.randomUUID() : committed.currentNpcUuid();
        Ref<EntityStore> ref = null;
        long worldGameTimeMs = 0L;
        boolean[] prepared = new boolean[1];
        CoopResidentStateRestorer.PostAddWork[] importedWork = new CoopResidentStateRestorer.PostAddWork[1];
        try {
            CompanionRecord now = currentRecord.apply(profileId);
            if (!RestoreFlow.sameHolder(committed, now)) {
                // A newer change to the record won after the commit; it owns the outcome.
                return false;
            }
            // A revive comes back without the needs that killed the companion.
            CoopResidentStateSnapshot state = imported == null ? null
                    : RestoreRules.importedStateFor(imported, reason);
            if (imported != null && state == null) {
                warn(profileId, "the imported state snapshot is unreadable", null);
                return false;
            }
            NPCPlugin plugin = NPCPlugin.get();
            int roleIndex = plugin == null ? -1 : plugin.getIndex(committed.roleId());
            if (npcUuid == null || !unowned && committed.ownerUuid() == null || roleIndex < 0) {
                warn(profileId, "a spawn from the role needs an owner, an NPC UUID and a loaded role ("
                        + committed.roleId() + ")", null);
                return false;
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            worldGameTimeMs = CompanionWorldTime.gameTimeMs(store);
            Vector3d position = new Vector3d(destination.x(), destination.y(), destination.z());
            Rotation3f rotation = new Rotation3f(destination.pitch(), destination.yaw(), 0.0f);
            var spawned = plugin.spawnEntity(store, roleIndex, position, rotation, null, (npc, holder, into) -> {
                holder.putComponent(UUIDComponent.getComponentType(), new UUIDComponent(npcUuid));
                npc.setLegacyUUID(npcUuid);
                if (state != null) {
                    importedWork[0] = IMPORTED_STATE_RESTORER.restoreToHolder(holder, state, null);
                }
                if (unowned) {
                    stripOwnership(holder, stampType);
                } else {
                    holder.putComponent(stampType, new TameworkCompanionComponent(profileId, committed.generation()));
                    applyOwnership(holder, committed, false, stampType);
                }
                prepared[0] = true;
            }, null);
            ref = spawned == null ? null : spawned.first();
        } catch (RuntimeException | LinkageError failure) {
            // The engine adds the entity before its on-add systems run, so a throw from those can
            // leave the body in the store; it is found by the NPC UUID written before the add.
            ref = npcUuid == null ? null : world.getEntityRef(npcUuid);
            boolean added = ref != null && ref.isValid();
            warn(profileId, added ? "an on-add step failed after the body from the role was added"
                    : "spawn from role " + committed.roleId() + " failed", failure);
        }
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        if (!prepared[0]) {
            removeUnprepared(ref, store, profileId);
            return false;
        }
        try {
            CompanionSpawnAuthorityService.detach(ref, store);
            if (!unowned) {
                CompanionProgressionBootstrapService.ensureProgressionComponents(ref, store, committed.roleId());
            }
        } catch (RuntimeException | LinkageError failure) {
            warn(profileId, "a step after the body from the role was added failed", failure);
        }
        if (importedWork[0] != null) {
            try {
                applyImportedPostAddWork(ref, store, importedWork[0], reason);
            } catch (RuntimeException | LinkageError failure) {
                warn(profileId, "the imported name, health or attachments could not be applied", failure);
            }
        }
        // The snapshot taken here is the one every later restore uses; it replaces a format 0 one.
        finishAddedBody(ref, store, committed, world.getName(), worldGameTimeMs, reason, snapshots, queueSnapshot,
                !unowned);
        if (unowned) {
            deleteUnownedSnapshot(profileId);
        }
        return true;
    }

    /**
     * The parts of an imported state that need the live body: the display name, health and model
     * attachments. Stored health is applied only where {@link RestoreRules#appliesImportedHealth}
     * allows it; after a revive {@link #finishAddedBody} fills health to its maximum, as for format 1.
     */
    private static void applyImportedPostAddWork(Ref<EntityStore> ref, Store<EntityStore> store,
                                                 CoopResidentStateRestorer.PostAddWork work,
                                                 RestoreRules.Reason reason) {
        if (work.hasDisplayNameWork()) {
            NpcDisplayNameAccess.set(ref, work.displayName(), store);
        }
        if (RestoreRules.appliesImportedHealth(reason, work.currentHealth(), work.healthPercent())) {
            // Trait modifiers first, so the stored value meets the modified maximum.
            CompanionStatModifierService.applyTraitModifiers(ref, store);
            CompanionHealthStateService.applyStoredHealth(ref, store, work.currentHealth(), work.maximumHealth(),
                    work.healthPercent());
        }
        if (work.hasAttachmentWork()) {
            CompanionModelAttachmentService.applyAttachments(ref, store.getComponent(ref, NPCEntity.getComponentType()),
                    store, work.attachments().getAttachmentIds());
        }
    }

    /** An unowned body is untracked: no later recall or Recover may restore its profile from a snapshot. */
    private void deleteUnownedSnapshot(UUID profileId) {
        try {
            deleteSnapshot.accept(profileId);
        } catch (RuntimeException failure) {
            warn(profileId, "the snapshot of an unowned spawn could not be queued for deletion", failure);
        }
    }

    /** World thread, between ticks: takes out a body from the role whose pre-add callback did not finish. */
    private static void removeUnprepared(Ref<EntityStore> ref, Store<EntityStore> store, UUID profileId) {
        try {
            store.removeEntity(ref, RemoveReason.REMOVE);
        } catch (RuntimeException | LinkageError failure) {
            warn(profileId, "an unprepared body from the role could not be removed", failure);
        }
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
     * so the spawn still counts. {@code snapshotBody} is false for an unowned spawn, whose body is
     * not tracked.
     */
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
