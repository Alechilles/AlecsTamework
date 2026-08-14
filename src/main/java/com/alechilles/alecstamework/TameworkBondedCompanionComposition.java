package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionLocalProjectionLifecycle;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionCleanupService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionService;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionProjectionValidator;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionTransitionService;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionWorldLifecycleObserver;
import com.alechilles.alecstamework.companion.bonded.runtime
        .HytaleBondedCompanionWorldGateway;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionCapturePersistenceAdapter;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionApiFacade;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionChangePublisher;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCaptureEventPublisher;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionCoreApiOperations;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionDataPath;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorageFailureEvidence;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorageFailureMonitor;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceRuntime;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceReadiness;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionStorePlanner;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionSchemaManager;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import com.alechilles.alecstamework.items.BondedCompanionCaptureAuthor;
import com.alechilles.alecstamework.items.BondedCompanionCaptureFeedbackDispatcher;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the isolated bonded policy, persistence, projection, API, and
 * diagnostics runtime.
 */
public final class TameworkBondedCompanionComposition implements AutoCloseable {
    private static final long DATABASE_MAINTENANCE_INTERVAL_MS = 5_000L;
    private final BondedCompanionPersistenceRuntime persistence;
    private final BondedCompanionApiFacade api;
    private final BondedCompanionChangePublisher changes;
    private final BondedCompanionDiagnosticContributor diagnostics;
    private final BondedCompanionTransitionService transitions;
    private final BondedCompanionProjectionService projections;
    private final BondedCompanionWorldLifecycleObserver observer;
    private final BondedCompanionLocalProjectionLifecycle localLifecycle;
    private final BondedCompanionProjectionCleanupService cleanup;
    private final SqliteBondedCompanionProjectionDurability durability;
    private final HytaleBondedCompanionWorldGateway world;
    private final com.alechilles.alecstamework.persistence.bonded
            .BondedCompanionStore store;
    private final BondedCompanionRosterRegistry rosters;
    private final LongSupplier clock;
    private final BondedCompanionCaptureAuthor captureAuthor;
    private final BondedCompanionStorageFailureMonitor storageFailures;
    private final BiConsumer<String, Throwable> storageFailureHandler;
    @Nullable
    private final HytaleLogger logger;
    @Nullable
    private final BondedCompanionCaptureEventPublisher captureEvents;
    private final Map<UUID, String> ownerWorlds = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Long> nextDatabaseMaintenanceAtMs =
            new AtomicReference<>();

    private TameworkBondedCompanionComposition(
            BondedCompanionPersistenceRuntime persistence,
            BondedCompanionApiFacade api,
            BondedCompanionChangePublisher changes,
            BondedCompanionDiagnosticContributor diagnostics,
            BondedCompanionTransitionService transitions,
            BondedCompanionProjectionService projections,
            BondedCompanionWorldLifecycleObserver observer,
            BondedCompanionLocalProjectionLifecycle localLifecycle,
            BondedCompanionProjectionCleanupService cleanup,
            SqliteBondedCompanionProjectionDurability durability,
            HytaleBondedCompanionWorldGateway world,
            com.alechilles.alecstamework.persistence.bonded
                    .BondedCompanionStore store,
            BondedCompanionRosterRegistry rosters,
            LongSupplier clock,
            BondedCompanionCaptureAuthor captureAuthor,
            BondedCompanionCaptureEventPublisher captureEvents,
            BondedCompanionStorageFailureMonitor storageFailures,
            BiConsumer<String, Throwable> storageFailureHandler,
            HytaleLogger logger
    ) {
        this.persistence = persistence;
        this.api = api;
        this.changes = changes;
        this.diagnostics = diagnostics;
        this.transitions = transitions;
        this.projections = projections;
        this.observer = observer;
        this.localLifecycle = localLifecycle;
        this.cleanup = cleanup;
        this.durability = durability;
        this.world = world;
        this.store = store;
        this.rosters = rosters;
        this.clock = clock;
        this.captureAuthor = captureAuthor;
        this.captureEvents = captureEvents;
        this.storageFailures = storageFailures;
        this.storageFailureHandler = storageFailureHandler;
        this.logger = logger;
    }

    /** Opens the bonded authority without accepting generic persistence state. */
    @Nonnull
    public static TameworkBondedCompanionComposition open(
            @Nonnull Path commonDataDirectory,
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nullable HytaleLogger logger,
            @Nonnull LongSupplier clock
    ) {
        return open(commonDataDirectory, rosters, logger, clock, null);
    }

    /** Opens the bonded authority with an optional public capture-event sink. */
    @Nonnull
    public static TameworkBondedCompanionComposition open(
            @Nonnull Path commonDataDirectory,
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nullable HytaleLogger logger,
            @Nonnull LongSupplier clock,
            @Nullable Consumer<BondedCompanionCaptureResolvedEvent>
                    captureEventSink
    ) {
        return open(commonDataDirectory, rosters, logger, clock,
                captureEventSink, null);
    }

    /** Opens the bonded authority with optional capture and storage-failure sinks. */
    @Nonnull
    public static TameworkBondedCompanionComposition open(
            @Nonnull Path commonDataDirectory,
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nullable HytaleLogger logger,
            @Nonnull LongSupplier clock,
            @Nullable Consumer<BondedCompanionCaptureResolvedEvent>
                    captureEventSink,
            @Nullable Consumer<BondedCompanionStorageFailureEvidence>
                    storageFailureSink
    ) {
        Objects.requireNonNull(commonDataDirectory, "commonDataDirectory");
        Objects.requireNonNull(rosters, "rosters");
        Objects.requireNonNull(clock, "clock");
        Path databasePath = commonDataDirectory.toAbsolutePath().normalize()
                .resolve(BondedCompanionDataPath.FILE_NAME);
        BondedCompanionSchemaManager schemas =
                new BondedCompanionSchemaManager(databasePath, clock);
        BondedCompanionPersistenceRuntime runtime =
                new BondedCompanionPersistenceRuntime(schemas);
        BondedCompanionPersistenceReadiness started = runtime.start();
        BondedCompanionStorageFailureMonitor storageFailures =
                new BondedCompanionStorageFailureMonitor(
                        databasePath, schemas, storageFailureSink);
        BiConsumer<String, Throwable> storageFailureHandler =
                (operation, failure) -> containStorageFailure(
                        runtime, storageFailures, logger, operation, failure);
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(
                        databasePath, storageFailureHandler);
        BondedCompanionDiagnosticContributor diagnostics =
                new BondedCompanionDiagnosticContributor(
                        runtime::readiness,
                        store::diagnostics,
                        BondedCompanionSchemaManager.VERSION
                );
        BondedCompanionChangePublisher changes =
                new BondedCompanionChangePublisher(logger);
        BondedCompanionCaptureEventPublisher captureEvents =
                captureEventSink == null ? null
                        : new BondedCompanionCaptureEventPublisher(
                                store, captureEventSink, clock);
        if (!started.availability().available()) {
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STARTUP
            );
        }
        HytaleBondedCompanionWorldGateway world =
                new HytaleBondedCompanionWorldGateway();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        BondedCompanionTransitionService transitions =
                new BondedCompanionTransitionService(policies);
        BondedCompanionProjectionCleanupService cleanup =
                new BondedCompanionProjectionCleanupService(world);
        SqliteBondedCompanionProjectionDurability durability =
                new SqliteBondedCompanionProjectionDurability(
                        databasePath, storageFailureHandler);
        BondedCompanionProjectionService projections =
                new BondedCompanionProjectionService(
                        new BondedCompanionStorePlanner(store, rosters),
                        durability, world, cleanup,
                        () -> UUID.randomUUID().toString(), UUID::randomUUID
                );
        BondedCompanionWorldLifecycleObserver observer =
                new BondedCompanionWorldLifecycleObserver(
                        projections,
                        java.util.List::of,
                        (lease, cause, result) -> publishLifecycleChange(
                                store, changes, lease, result
                        )
                );
        BondedCompanionLocalProjectionLifecycle localLifecycle =
                new BondedCompanionLocalProjectionLifecycle(
                        observer, durability, world, 64, 128,
                        storageFailureHandler);
        BondedCompanionCoreApiOperations operations =
                new BondedCompanionCoreApiOperations(
                        store, rosters, policies, transitions, projections,
                        changes, diagnostics, clock
                );
        BondedCompanionApiFacade api = new BondedCompanionApiFacade(
                runtime::readiness,
                store, changes, diagnostics, operations, store,
                storageFailureHandler
        );
        SqliteBondedCompanionCapturePersistenceAdapter capturePersistence =
                new SqliteBondedCompanionCapturePersistenceAdapter(
                        rosters, transitions, store, store, durability, cleanup,
                        captureEvents, storageFailureHandler
                );
        BondedCompanionCaptureAuthor captureAuthor =
                new BondedCompanionCaptureAuthor(
                        capturePersistence,
                        capturePersistence::validate,
                        capturePersistence::store,
                        capturePersistence::cleanup,
                        BondedCompanionCaptureFeedbackDispatcher.production(
                                logger),
                        (intent, failure) -> BondedCompanionCapturePolicyFailureLogger.log(
                                logger, intent, failure),
                        storageFailureHandler
                );
        if (started.availability().available()) {
            publishPendingCaptureEvents(
                    captureEvents, diagnostics, storageFailureHandler, 128);
            try {
                durability.settleResidualLeases(clock.getAsLong());
            } catch (RuntimeException failure) {
                runtime.fail("bonded-startup-residual-settlement-failed");
                diagnostics.recordFailure(
                        BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE);
            }
        }
        return new TameworkBondedCompanionComposition(
                runtime, api, changes, diagnostics,
                transitions, projections, observer, localLifecycle,
                cleanup, durability, world, store, rosters, clock, captureAuthor,
                captureEvents, storageFailures, storageFailureHandler, logger
        );
    }

    @Nonnull
    public BondedCompanionApi api() {
        return api;
    }

    /** Returns the isolated explicit-disposition capture author. */
    @Nonnull
    public BondedCompanionCaptureAuthor captureAuthor() {
        return captureAuthor;
    }

    @Nonnull
    public BondedCompanionDiagnosticContributor diagnostics() {
        return diagnostics;
    }

    /** Reconciles one started world using only exact persisted leases. */
    public void onWorldLoad(@Nonnull String worldKey) {
        if (!operational()) return;
        runStorageGuarded("world_load", () -> {
            long now = clock.getAsLong();
            durability.replayPendingCleanupForWorld(
                    cleanup, worldKey, now, 128);
            localLifecycle.reconcileCurrentWorld(
                    worldKey,
                    BondedCompanionProjectionService.RecoveryCause.WORLD_LOAD,
                    now);
        });
    }

    /** Hytale event adapter retaining no world object after this call. */
    public void onWorldLoad(
            @Nonnull com.hypixel.hytale.server.core.universe.world.events
                    .StartWorldEvent event
    ) {
        if (event.getWorld() != null) onWorldLoad(event.getWorld().getName());
    }

    /** Routes first arrival as join and later world changes as transfer. */
    public void onPlayerAdded(@Nonnull UUID ownerUuid, @Nonnull String worldKey) {
        if (!operational()) return;
        String previous = ownerWorlds.put(ownerUuid, worldKey);
        if (previous != null && !previous.equals(worldKey)) {
            runStorageGuarded("player_transfer", () ->
                    localLifecycle.storeOwnerInWorld(
                            ownerUuid, previous,
                            BondedCompanionProjectionService.RecoveryCause.WORLD_TRANSFER,
                            clock.getAsLong()));
        }
    }

    /** Hytale event adapter retaining only stable owner and world identifiers. */
    public void onPlayerAdded(
            @Nonnull com.hypixel.hytale.server.core.event.events.player
                    .AddPlayerToWorldEvent event
    ) {
        if (event.getWorld() == null || event.getHolder() == null) return;
        com.hypixel.hytale.server.core.universe.PlayerRef player =
                event.getHolder().getComponent(
                        com.hypixel.hytale.server.core.universe.PlayerRef
                                .getComponentType()
                );
        if (player != null && player.getUuid() != null) {
            onPlayerAdded(player.getUuid(), event.getWorld().getName());
        }
    }

    /** Stores exact active projections when their owner disconnects. */
    public void onPlayerLogout(@Nonnull UUID ownerUuid) {
        if (!operational()) return;
        String recordedWorld = ownerWorlds.remove(ownerUuid);
        runStorageGuarded("player_logout", () -> {
            if (recordedWorld == null) {
                localLifecycle.storeOwner(
                        ownerUuid,
                        BondedCompanionProjectionService.RecoveryCause.LOGOUT,
                        clock.getAsLong());
            } else {
                localLifecycle.storeOwnerInWorld(
                        ownerUuid, recordedWorld,
                        BondedCompanionProjectionService.RecoveryCause.LOGOUT,
                        clock.getAsLong());
            }
        });
    }

    /** Hytale disconnect adapter retaining no Player component. */
    public void onPlayerLogout(
            @Nonnull com.hypixel.hytale.server.core.event.events.player
                    .PlayerDisconnectEvent event
    ) {
        if (event.getPlayerRef() != null
                && event.getPlayerRef().getUuid() != null) {
            onPlayerLogout(event.getPlayerRef().getUuid());
        }
    }

    /** Converts only an exact marked projection death to durable DEAD. */
    public void onConfirmedDeath(
            @Nonnull String worldKey,
            @Nonnull UUID npcUuid,
            @Nonnull com.alechilles.alecstamework.npc.components
                    .TameworkProjectionIdentityComponent marker,
            @Nonnull com.hypixel.hytale.component.Ref<
                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore>
                    reference,
            @Nonnull com.hypixel.hytale.component.Store<
                    com.hypixel.hytale.server.core.universe.world.storage.EntityStore> entityStore
    ) {
        if (!operational()) return;
        runStorageGuarded("confirmed_death", () -> {
            var projection = world.readCurrent(
                    reference, entityStore, worldKey, npcUuid, marker);
            if (projection != null) {
                localLifecycle.onConfirmedDeath(projection, clock.getAsLong());
            }
        });
    }

    /** Drives exact cleanup and recovery for only the current ticking world. */
    public void maintenanceTick(@Nonnull String worldKey) {
        if (!operational()) return;
        runStorageGuarded("maintenance", () -> {
            long now = clock.getAsLong();
            durability.replayPendingCleanupForWorld(
                    cleanup, worldKey, now, 64);
            localLifecycle.reconcileCurrentWorld(
                    worldKey,
                    BondedCompanionProjectionService.RecoveryCause.MISSING_SCAN,
                    now);
            databaseMaintenance(now);
        });
    }

    /** Returns the bounded set of active leases currently recorded in one world. */
    @Nonnull
    public List<BondedCompanionProjectionValidator.LeaseExpectation>
    activeLeasesInWorld(@Nonnull String worldKey, int maximumResults) {
        if (!operational() || maximumResults < 1) return List.of();
        return supplyStorageGuarded("active_lease_read", () ->
                durability.inWorldAfter(worldKey, null, maximumResults), List.of());
    }

    /** Resolves the durable profile name without trusting a disposable projection. */
    @Nullable
    public String displayNameFor(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        if (!operational()) return null;
        return supplyStorageGuarded("display_name_read", () ->
                store.findProfile(lease.ownerUuid(), lease.rosterId(),
                        lease.profileId()).map(profile -> profile.displayName())
                        .orElse(null), null);
    }

    /** Resolves the current roster's optional 30-second expiry presentation. */
    @Nullable
    public String expiryWarningEffectIdFor(
            @Nonnull BondedCompanionProjectionValidator.LeaseExpectation lease
    ) {
        if (!operational()) return null;
        return supplyStorageGuarded("expiry_effect_read", () ->
                store.findProfile(lease.ownerUuid(), lease.rosterId(),
                        lease.profileId()).flatMap(profile -> rosters.resolve(
                                lease.rosterId(), profile.familyId()))
                        .map(BondedCompanionRosterRegistry.RosterDefinition
                                ::expiryWarningEffectId)
                        .orElse(null), null);
    }

    /** Runs bounded database retention work without consulting any world. */
    public void maintenanceTick() {
        if (!operational()) return;
        runStorageGuarded("maintenance", () ->
                databaseMaintenance(clock.getAsLong()));
    }

    private void databaseMaintenance(long now) {
        Long next = nextDatabaseMaintenanceAtMs.get();
        if (next != null && now < next) return;
        long following = safeAdd(now, DATABASE_MAINTENANCE_INTERVAL_MS);
        if (!nextDatabaseMaintenanceAtMs.compareAndSet(next, following)) return;
        publishPendingCaptureEvents(
                captureEvents, diagnostics, storageFailureHandler, 64);
        store.pruneCleanup(now, 64);
        store.pruneOperations(now, 64);
    }

    private static long safeAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private boolean operational() {
        return !closed.get() && persistence.readiness()
                .availability().available();
    }

    private void runStorageGuarded(String operation, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            if (!containStorageFailure(operation, failure)) throw failure;
        }
    }

    private <T> T supplyStorageGuarded(
            String operation,
            Supplier<T> work,
            T fallback
    ) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            if (!containStorageFailure(operation, failure)) throw failure;
            return fallback;
        }
    }

    private boolean containStorageFailure(
            String operation,
            RuntimeException failure
    ) {
        boolean contained = containStorageFailure(
                persistence, storageFailures, logger, operation, failure);
        if (contained) {
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE);
        }
        return contained;
    }

    private static boolean containStorageFailure(
            BondedCompanionPersistenceRuntime persistence,
            BondedCompanionStorageFailureMonitor storageFailures,
            @Nullable HytaleLogger logger,
            String operation,
            Throwable failure
    ) {
        if (!storageFailures.isStorageFailure(failure)) return false;
        persistence.fail("bonded-runtime-storage-failed");
        BondedCompanionStorageFailureEvidence evidence =
                storageFailures.captureOnce(operation, failure);
        if (evidence != null && logger != null) {
            logger.at(Level.WARNING).withCause(failure).log(
                    "Bonded companion persistence failed during " + operation
                            + "; bonded features are disabled for this session "
                            + "to prevent world crashes."
            );
        }
        return true;
    }

    private static void publishPendingCaptureEvents(
            BondedCompanionCaptureEventPublisher captureEvents,
            BondedCompanionDiagnosticContributor diagnostics,
            BiConsumer<String, Throwable> storageFailures,
            int limit
    ) {
        if (captureEvents == null) return;
        try {
            captureEvents.publishPending(limit);
        } catch (RuntimeException | LinkageError failure) {
            storageFailures.accept("capture_event_publish", failure);
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STORAGE
            );
        }
    }

    private static void publishLifecycleChange(
            com.alechilles.alecstamework.persistence.bonded.BondedCompanionStore store,
            BondedCompanionChangePublisher changes,
            BondedCompanionProjectionValidator.LeaseExpectation lease,
            BondedCompanionProjectionService.ReconcileResult result
    ) {
        if (result.status() != BondedCompanionProjectionService.ReconcileStatus.STORED
                && result.status() != BondedCompanionProjectionService.ReconcileStatus.DEAD) {
            return;
        }
        store.findProfile(lease.ownerUuid(), lease.rosterId(), lease.profileId())
                .ifPresent(profile -> changes.publishCommitted(
                        new BondedCompanionChangedEvent(
                                profile.profileId(), profile.ownerUuid(),
                                profile.rosterId(),
                                BondedCompanionStateView.ACTIVE,
                                BondedCompanionStateView.valueOf(
                                        profile.state().name()),
                                profile.revision(),
                                result.status().name().toLowerCase(
                                        java.util.Locale.ROOT)
                        ),
                        result.status()
                                == BondedCompanionProjectionService.ReconcileStatus.DEAD
                                ? BondedCompanionChangePublisher.WorldEffectOutcome
                                .CONFIRMED
                                : result.cleanups().isEmpty()
                                ? BondedCompanionChangePublisher.WorldEffectOutcome
                                .NOT_REQUIRED
                                : BondedCompanionChangePublisher.WorldEffectOutcome
                                .DEFERRED
                ));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            api.close();
            ownerWorlds.clear();
            changes.close();
            persistence.close();
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.CLOSED
            );
        }
    }
}
