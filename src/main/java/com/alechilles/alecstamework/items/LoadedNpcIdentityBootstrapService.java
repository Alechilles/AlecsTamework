package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bootstraps the loaded-NPC identity index from every active entity store on its world thread.
 *
 * <p>Only immutable location metadata is retained between tasks. Production scan targets capture a
 * world/store only until their scheduled task finishes, and probes never scan {@link Universe}.
 */
public final class LoadedNpcIdentityBootstrapService {
    private static final String WARNING_PREFIX =
            "Loaded NPC identity bootstrap is incomplete; absence checks remain UNKNOWN. ";

    private final LoadedNpcIdentityIndex identityIndex;
    private final TargetSource targetSource;
    private final WarningSink warningSink;
    private final Object stateLock = new Object();
    private final Set<LoadedNpcIdentityIndex.Location> pendingLocations = ConcurrentHashMap.newKeySet();
    private final Set<LoadedNpcIdentityIndex.Location> failedLocations = ConcurrentHashMap.newKeySet();

    private long attemptGeneration;
    private boolean unresolvedGlobalFailure;
    private boolean warningLogged;

    public LoadedNpcIdentityBootstrapService(@Nonnull LoadedNpcIdentityIndex identityIndex,
                                             @Nonnull HytaleLogger logger) {
        this(identityIndex, new ProductionTargetSource(), loggerWarningSink(logger));
    }

    LoadedNpcIdentityBootstrapService(@Nonnull LoadedNpcIdentityIndex identityIndex,
                                      @Nonnull TargetSource targetSource,
                                      @Nonnull WarningSink warningSink) {
        this.identityIndex = Objects.requireNonNull(identityIndex, "identityIndex");
        this.targetSource = Objects.requireNonNull(targetSource, "targetSource");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Snapshots all current universe worlds, then schedules one world-thread scan per entity store. */
    public void bootstrapUniverse() {
        List<ScanTarget> targets;
        long generation;
        Throwable snapshotFailure = null;
        synchronized (stateLock) {
            generation = beginReplacementAttemptLocked();
            try {
                targets = deduplicateTargets(targetSource.snapshotUniverseTargets());
                unresolvedGlobalFailure = false;
                for (ScanTarget target : targets) {
                    pendingLocations.add(target.location());
                }
                completeIfReadyLocked(generation);
            } catch (Throwable error) {
                targets = List.of();
                unresolvedGlobalFailure = true;
                snapshotFailure = error;
                warningLogged = true;
            }
        }
        if (snapshotFailure != null) {
            logFailure("Retry the bootstrap after the universe/world snapshot error is resolved.", snapshotFailure);
            return;
        }
        for (ScanTarget target : targets) {
            scheduleTarget(generation, target);
        }
    }

    /** Marks coverage incomplete and schedules the newly started world's entity store for scanning. */
    public void onStartWorld(@Nonnull StartWorldEvent event) {
        Objects.requireNonNull(event, "event");
        ScanTarget target;
        Throwable resolutionFailure = null;
        synchronized (stateLock) {
            beginAdditionalAttemptIfIdleLocked();
            identityIndex.markInitializationIncomplete();
            try {
                target = Objects.requireNonNull(
                        targetSource.targetForWorld(event.getWorld()),
                        "Started-world scan target"
                );
            } catch (Throwable error) {
                target = null;
                unresolvedGlobalFailure = true;
                resolutionFailure = error;
            }
        }
        if (resolutionFailure != null) {
            warnOnce("Retry after the started world's entity store becomes available.", resolutionFailure);
            return;
        }
        scheduleStartedTarget(target);
    }

    /** Explicit ECS removal bridge; ordinary entity remove events remain the source of truth. */
    public void recordRemoved(@Nullable UUID npcUuid,
                              @Nullable LoadedNpcIdentityIndex.Location location) {
        identityIndex.recordRemoved(npcUuid, location);
    }

    /**
     * Clears an explicitly retired store location without trusting cancellable world-removal events.
     */
    public void clearLocation(@Nullable LoadedNpcIdentityIndex.Location location) {
        if (location == null) {
            return;
        }
        identityIndex.clearLocation(location);
        synchronized (stateLock) {
            failedLocations.remove(location);
            completeIfReadyLocked(attemptGeneration);
        }
    }

    public int pendingLocationCount() {
        return pendingLocations.size();
    }

    @Nonnull
    public Set<LoadedNpcIdentityIndex.Location> pendingLocationSnapshot() {
        return Set.copyOf(pendingLocations);
    }

    void scheduleStartedTarget(@Nonnull ScanTarget target) {
        Objects.requireNonNull(target, "target");
        long generation;
        synchronized (stateLock) {
            beginAdditionalAttemptIfIdleLocked();
            identityIndex.markInitializationIncomplete();
            generation = attemptGeneration;
            if (!pendingLocations.add(target.location())) {
                return;
            }
            failedLocations.remove(target.location());
        }
        scheduleTarget(generation, target);
    }

    private long beginReplacementAttemptLocked() {
        attemptGeneration++;
        pendingLocations.clear();
        failedLocations.clear();
        unresolvedGlobalFailure = false;
        warningLogged = false;
        identityIndex.markInitializationIncomplete();
        return attemptGeneration;
    }

    private void beginAdditionalAttemptIfIdleLocked() {
        if (!pendingLocations.isEmpty()) {
            return;
        }
        attemptGeneration++;
        warningLogged = false;
    }

    private void scheduleTarget(long generation, @Nonnull ScanTarget target) {
        synchronized (stateLock) {
            if (generation != attemptGeneration || !pendingLocations.contains(target.location())) {
                return;
            }
        }
        try {
            target.scheduler().schedule(() -> runScan(generation, target));
        } catch (Throwable error) {
            finishFailure(generation, target.location(), "Retry after world task scheduling is available.", error);
        }
    }

    private void runScan(long generation, @Nonnull ScanTarget target) {
        if (!beginCurrentScan(generation, target.location())) {
            return;
        }
        Set<UUID> scannedUuids = new LinkedHashSet<>();
        try {
            target.scanner().scan((componentUuid, legacyNpcUuid) -> {
                if (componentUuid != null) {
                    scannedUuids.add(componentUuid);
                }
                if (legacyNpcUuid != null && !legacyNpcUuid.equals(componentUuid)) {
                    scannedUuids.add(legacyNpcUuid);
                }
            });
            commitSuccessfulScan(generation, target.location(), scannedUuids);
        } catch (Throwable error) {
            finishFailure(generation, target.location(), "Retry after the entity-store scan error is resolved.", error);
        }
    }

    private boolean beginCurrentScan(long generation, @Nonnull LoadedNpcIdentityIndex.Location location) {
        synchronized (stateLock) {
            if (generation != attemptGeneration || !pendingLocations.contains(location)) {
                return false;
            }
            identityIndex.markInitializationIncomplete();
            identityIndex.clearLocation(location);
            return true;
        }
    }

    private void commitSuccessfulScan(long generation,
                                      @Nonnull LoadedNpcIdentityIndex.Location location,
                                      @Nonnull Collection<UUID> scannedUuids) {
        synchronized (stateLock) {
            if (generation != attemptGeneration || !pendingLocations.contains(location)) {
                return;
            }
            identityIndex.replaceLocation(location, scannedUuids);
            pendingLocations.remove(location);
            completeIfReadyLocked(generation);
        }
    }

    private void finishFailure(long generation,
                               @Nonnull LoadedNpcIdentityIndex.Location location,
                               @Nonnull String action,
                               @Nonnull Throwable error) {
        boolean shouldWarn;
        synchronized (stateLock) {
            if (generation != attemptGeneration) {
                return;
            }
            pendingLocations.remove(location);
            failedLocations.add(location);
            identityIndex.markInitializationIncomplete();
            shouldWarn = !warningLogged;
            warningLogged = true;
        }
        if (shouldWarn) {
            logFailure(action + " Location=" + location.displayName() + ".", error);
        }
    }

    private void completeIfReadyLocked(long generation) {
        if (generation <= 0
                || generation != attemptGeneration
                || !pendingLocations.isEmpty()
                || !failedLocations.isEmpty()
                || unresolvedGlobalFailure) {
            return;
        }
        identityIndex.markInitializationComplete();
    }

    private void warnOnce(@Nonnull String action, @Nonnull Throwable error) {
        boolean shouldWarn;
        synchronized (stateLock) {
            shouldWarn = !warningLogged;
            warningLogged = true;
        }
        if (shouldWarn) {
            logFailure(action, error);
        }
    }

    private void logFailure(@Nonnull String action, @Nonnull Throwable error) {
        warningSink.warn(WARNING_PREFIX + action, error);
    }

    @Nonnull
    private static WarningSink loggerWarningSink(@Nonnull HytaleLogger logger) {
        HytaleLogger requiredLogger = Objects.requireNonNull(logger, "logger");
        return (message, error) -> requiredLogger.at(Level.WARNING).withCause(error).log(message);
    }

    @Nonnull
    private static List<ScanTarget> deduplicateTargets(@Nullable Collection<ScanTarget> targets) {
        if (targets == null) {
            throw new IllegalStateException("Universe world snapshot returned null scan targets.");
        }
        Map<LoadedNpcIdentityIndex.Location, ScanTarget> unique = new LinkedHashMap<>();
        for (ScanTarget target : targets) {
            if (target == null) {
                throw new IllegalStateException("Universe world snapshot contained a null scan target.");
            }
            unique.putIfAbsent(target.location(), target);
        }
        return List.copyOf(unique.values());
    }

    private static final class ProductionTargetSource implements TargetSource {
        @Nonnull
        @Override
        public List<ScanTarget> snapshotUniverseTargets() {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                throw new IllegalStateException("Universe worlds are unavailable.");
            }
            List<World> worlds = new ArrayList<>(universe.getWorlds().values());
            List<ScanTarget> targets = new ArrayList<>(worlds.size());
            for (World world : worlds) {
                targets.add(targetForWorld(world));
            }
            return targets;
        }

        @Nonnull
        @Override
        public ScanTarget targetForWorld(@Nullable World world) {
            if (world == null || world.getEntityStore() == null || world.getEntityStore().getStore() == null) {
                throw new IllegalStateException("A loaded world has no entity store.");
            }
            Store<EntityStore> store = world.getEntityStore().getStore();
            LoadedNpcIdentityIndex.Location location = LoadedNpcLocationResolver.resolve(store);
            return new ScanTarget(
                    location,
                    world::execute,
                    recorder -> scanStore(store, recorder)
            );
        }
    }

    private static void scanStore(@Nonnull Store<EntityStore> store,
                                  @Nonnull IdentityRecorder recorder) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (npcType == null || uuidType == null) {
            throw new IllegalStateException("NPCEntity or UUIDComponent type is unavailable.");
        }
        store.forEachChunk(
                Query.and(npcType, uuidType),
                (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
                    for (int index = 0; index < chunk.size(); index++) {
                        NPCEntity npc = chunk.getComponent(index, npcType);
                        UUIDComponent uuidComponent = chunk.getComponent(index, uuidType);
                        if (npc == null || uuidComponent == null) {
                            continue;
                        }
                        recorder.record(uuidComponent.getUuid(), npc.getUuid());
                    }
                }
        );
    }

    interface TargetSource {
        @Nonnull
        List<ScanTarget> snapshotUniverseTargets();

        @Nonnull
        ScanTarget targetForWorld(@Nullable World world);
    }

    record ScanTarget(@Nonnull LoadedNpcIdentityIndex.Location location,
                      @Nonnull TaskScheduler scheduler,
                      @Nonnull IdentityScanner scanner) {
        ScanTarget {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(scanner, "scanner");
        }
    }

    @FunctionalInterface
    interface TaskScheduler {
        void schedule(@Nonnull Runnable task);
    }

    @FunctionalInterface
    interface IdentityScanner {
        void scan(@Nonnull IdentityRecorder recorder);
    }

    @FunctionalInterface
    interface IdentityRecorder {
        void record(@Nullable UUID componentUuid, @Nullable UUID legacyNpcUuid);
    }

    @FunctionalInterface
    interface WarningSink {
        void warn(@Nonnull String message, @Nonnull Throwable error);
    }
}
