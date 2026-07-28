package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
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
import java.util.concurrent.CompletableFuture;
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
    private static final int MAX_CONCURRENT_MUTATION_RETRIES = 1;
    private static final String WARNING_PREFIX =
            "Loaded NPC identity bootstrap is incomplete; absence checks remain UNKNOWN. ";

    private final LoadedNpcIdentityIndex identityIndex;
    private final TargetSource targetSource;
    private final WarningSink warningSink;
    private final Object stateLock = new Object();
    private final Set<LoadedNpcIdentityIndex.Location> pendingLocations = ConcurrentHashMap.newKeySet();
    private final Set<LoadedNpcIdentityIndex.Location> failedLocations = ConcurrentHashMap.newKeySet();
    private final Map<LoadedNpcIdentityIndex.Location, Integer> mutationRetries = new LinkedHashMap<>();
    private CompletableFuture<LoadedNpcIdentitySnapshot> bootstrapReady = new CompletableFuture<>();

    private long attemptGeneration;
    private boolean hasScannedLocation;
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
            markCoverageIncompleteLocked();
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
            mutationRetries.remove(location);
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

    /** Returns a non-blocking future for the next complete, authoritative loaded-identity snapshot. */
    @Nonnull
    public CompletableFuture<LoadedNpcIdentitySnapshot> awaitCurrentBootstrap() {
        synchronized (stateLock) {
            if (bootstrapReady.isDone()) {
                LoadedNpcIdentitySnapshot current = identityIndex.snapshot();
                if (current.initializationComplete()) {
                    return CompletableFuture.completedFuture(current);
                }
                bootstrapReady = new CompletableFuture<>();
            }
            return bootstrapReady.copy();
        }
    }

    void scheduleStartedTarget(@Nonnull ScanTarget target) {
        Objects.requireNonNull(target, "target");
        if (hasUnresolvedBootstrapFailure()) {
            bootstrapUniverse();
        }
        long generation;
        synchronized (stateLock) {
            beginAdditionalAttemptIfIdleLocked();
            markCoverageIncompleteLocked();
            generation = attemptGeneration;
            if (!pendingLocations.add(target.location())) {
                return;
            }
            failedLocations.remove(target.location());
            mutationRetries.remove(target.location());
        }
        scheduleTarget(generation, target);
    }

    private boolean hasUnresolvedBootstrapFailure() {
        synchronized (stateLock) {
            return unresolvedGlobalFailure || !failedLocations.isEmpty();
        }
    }

    private long beginReplacementAttemptLocked() {
        attemptGeneration++;
        pendingLocations.clear();
        failedLocations.clear();
        mutationRetries.clear();
        hasScannedLocation = false;
        unresolvedGlobalFailure = false;
        warningLogged = false;
        markCoverageIncompleteLocked();
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
        Long scanRevision = beginCurrentScan(generation, target.location());
        if (scanRevision == null) {
            return;
        }
        Set<LoadedNpcIdentityIndex.LoadedNpcObservation> observations = new LinkedHashSet<>();
        try {
            target.scanner().scan((componentUuid, legacyNpcUuid, projectionKey) -> {
                if (componentUuid != null || legacyNpcUuid != null) {
                    observations.add(new LoadedNpcIdentityIndex.LoadedNpcObservation(
                            componentUuid,
                            legacyNpcUuid,
                            target.location(),
                            projectionKey
                    ));
                }
            });
            ScanCommitOutcome outcome = commitSuccessfulScan(
                    generation, target.location(), observations, scanRevision
            );
            if (outcome == ScanCommitOutcome.RETRY) {
                scheduleTarget(generation, target);
            } else if (outcome == ScanCommitOutcome.FAILED) {
                finishFailure(
                        generation,
                        target.location(),
                        "Retry after concurrent entity lifecycle mutations settle.",
                        new IllegalStateException("Entity lifecycle changed during identity scan.")
                );
            }
        } catch (Throwable error) {
            finishFailure(generation, target.location(), "Retry after the entity-store scan error is resolved.", error);
        }
    }

    @Nullable
    private Long beginCurrentScan(long generation,
                                  @Nonnull LoadedNpcIdentityIndex.Location location) {
        synchronized (stateLock) {
            if (generation != attemptGeneration || !pendingLocations.contains(location)) {
                return null;
            }
            markCoverageIncompleteLocked();
            return identityIndex.locationMutationRevision(location);
        }
    }

    private ScanCommitOutcome commitSuccessfulScan(
            long generation,
            @Nonnull LoadedNpcIdentityIndex.Location location,
            @Nonnull Collection<LoadedNpcIdentityIndex.LoadedNpcObservation> observations,
            long expectedRevision) {
        synchronized (stateLock) {
            if (generation != attemptGeneration || !pendingLocations.contains(location)) {
                return ScanCommitOutcome.SUPERSEDED;
            }
            if (!identityIndex.replaceLocationObservationsIfUnchanged(
                    location, observations, expectedRevision
            )) {
                int retries = mutationRetries.merge(location, 1, Integer::sum);
                return retries <= MAX_CONCURRENT_MUTATION_RETRIES
                        ? ScanCommitOutcome.RETRY : ScanCommitOutcome.FAILED;
            }
            pendingLocations.remove(location);
            mutationRetries.remove(location);
            hasScannedLocation = true;
            completeIfReadyLocked(generation);
            return ScanCommitOutcome.COMMITTED;
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
            mutationRetries.remove(location);
            markCoverageIncompleteLocked();
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
                || !hasScannedLocation
                || !pendingLocations.isEmpty()
                || !failedLocations.isEmpty()
                || unresolvedGlobalFailure) {
            return;
        }
        identityIndex.markInitializationComplete();
        LoadedNpcIdentitySnapshot snapshot = identityIndex.snapshot();
        if (snapshot.initializationComplete()) {
            bootstrapReady.complete(snapshot);
        }
    }

    private void markCoverageIncompleteLocked() {
        if (bootstrapReady.isDone()) {
            bootstrapReady = new CompletableFuture<>();
        }
        identityIndex.markInitializationIncomplete();
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
        ComponentType<EntityStore, TameworkProjectionIdentityComponent> markerType =
                TameworkProjectionIdentityComponent.getComponentType();
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
                        Ref<EntityStore> reference = chunk.getReferenceTo(index);
                        TameworkProjectionIdentityComponent marker = markerType != null
                                && reference != null
                                ? store.getComponent(reference, markerType) : null;
                        recorder.record(
                                uuidComponent.getUuid(),
                                npc.getUuid(),
                                CommandLinkedNpcStateSnapshotService.projectionKey(marker)
                        );
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

    interface IdentityRecorder {
        void record(@Nullable UUID componentUuid,
                    @Nullable UUID legacyNpcUuid,
                    @Nullable LoadedNpcIdentityIndex.ProjectionKey projectionKey);

        default void record(@Nullable UUID componentUuid, @Nullable UUID legacyNpcUuid) {
            record(componentUuid, legacyNpcUuid, null);
        }
    }

    private enum ScanCommitOutcome {
        COMMITTED,
        SUPERSEDED,
        RETRY,
        FAILED
    }

    @FunctionalInterface
    interface WarningSink {
        void warn(@Nonnull String message, @Nonnull Throwable error);
    }
}
