package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.BondedCompanionApi;
import com.alechilles.alecstamework.api.BondedCompanionChangedEvent;
import com.alechilles.alecstamework.api.BondedCompanionCaptureResolvedEvent;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionExpirySystem;
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
        .BondedCompanionPersistenceRuntime;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceReadiness;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPaymentRecoveryService;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionSchemaManager;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import com.alechilles.alecstamework.items.BondedCompanionCaptureAuthor;
import com.alechilles.alecstamework.items.BondedCompanionCaptureFeedbackDispatcher;
import com.alechilles.alecstamework.items.BondedCompanionCaptureIntent;
import com.alechilles.alecstamework.items
        .HytaleBondedCompanionPaymentRecovery;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns the isolated bonded policy, persistence, projection, API, and
 * diagnostics runtime.
 */
public final class TameworkBondedCompanionComposition implements AutoCloseable {
    private final BondedCompanionPersistenceRuntime persistence;
    private final BondedCompanionApiFacade api;
    private final BondedCompanionChangePublisher changes;
    private final BondedCompanionDiagnosticContributor diagnostics;
    private final BondedCompanionTransitionService transitions;
    private final BondedCompanionProjectionService projections;
    private final BondedCompanionWorldLifecycleObserver observer;
    private final BondedCompanionExpirySystem expiry;
    private final BondedCompanionProjectionCleanupService cleanup;
    private final SqliteBondedCompanionProjectionDurability durability;
    private final HytaleBondedCompanionWorldGateway world;
    private final com.alechilles.alecstamework.persistence.bonded
            .BondedCompanionStore store;
    private final LongSupplier clock;
    private final BondedCompanionCaptureAuthor captureAuthor;
    @Nullable
    private final BondedCompanionCaptureEventPublisher captureEvents;
    private final HytaleBondedCompanionPaymentRecovery paymentRecovery;
    private final Map<UUID, String> ownerWorlds = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private TameworkBondedCompanionComposition(
            BondedCompanionPersistenceRuntime persistence,
            BondedCompanionApiFacade api,
            BondedCompanionChangePublisher changes,
            BondedCompanionDiagnosticContributor diagnostics,
            BondedCompanionTransitionService transitions,
            BondedCompanionProjectionService projections,
            BondedCompanionWorldLifecycleObserver observer,
            BondedCompanionExpirySystem expiry,
            BondedCompanionProjectionCleanupService cleanup,
            SqliteBondedCompanionProjectionDurability durability,
            HytaleBondedCompanionWorldGateway world,
            com.alechilles.alecstamework.persistence.bonded
                    .BondedCompanionStore store,
            LongSupplier clock,
            BondedCompanionCaptureAuthor captureAuthor,
            BondedCompanionCaptureEventPublisher captureEvents,
            HytaleBondedCompanionPaymentRecovery paymentRecovery
    ) {
        this.persistence = persistence;
        this.api = api;
        this.changes = changes;
        this.diagnostics = diagnostics;
        this.transitions = transitions;
        this.projections = projections;
        this.observer = observer;
        this.expiry = expiry;
        this.cleanup = cleanup;
        this.durability = durability;
        this.world = world;
        this.store = store;
        this.clock = clock;
        this.captureAuthor = captureAuthor;
        this.captureEvents = captureEvents;
        this.paymentRecovery = paymentRecovery;
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
        Objects.requireNonNull(commonDataDirectory, "commonDataDirectory");
        Objects.requireNonNull(rosters, "rosters");
        Objects.requireNonNull(clock, "clock");
        Path databasePath = commonDataDirectory.toAbsolutePath().normalize()
                .resolve(BondedCompanionDataPath.FILE_NAME);
        BondedCompanionPersistenceRuntime runtime =
                new BondedCompanionPersistenceRuntime(
                        new BondedCompanionSchemaManager(databasePath, clock)
                );
        SqliteBondedCompanionDatabase store =
                new SqliteBondedCompanionDatabase(databasePath);
        BondedCompanionCaptureEventPublisher captureEvents =
                captureEventSink == null ? null
                        : new BondedCompanionCaptureEventPublisher(
                                store, captureEventSink, clock);
        BondedCompanionDiagnosticContributor diagnostics =
                new BondedCompanionDiagnosticContributor(
                        runtime::readiness,
                        store::diagnostics,
                        BondedCompanionSchemaManager.VERSION
                );
        BondedCompanionChangePublisher changes =
                new BondedCompanionChangePublisher(logger);
        BondedCompanionPersistenceReadiness started = runtime.start();
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
                new SqliteBondedCompanionProjectionDurability(databasePath);
        BondedCompanionProjectionService projections =
                new BondedCompanionProjectionService(
                        durability, world, cleanup,
                        () -> UUID.randomUUID().toString(), UUID::randomUUID
                );
        BondedCompanionWorldLifecycleObserver observer =
                new BondedCompanionWorldLifecycleObserver(
                        projections,
                        () -> durability.activeLeases(256).stream()
                                .map(world::readExact)
                                .filter(Objects::nonNull)
                                .toList(),
                        (lease, cause, result) -> publishLifecycleChange(
                                store, changes, lease, result
                        )
                );
        BondedCompanionExpirySystem expiry = new BondedCompanionExpirySystem(
                observer, durability::findExpired, 64
        );
        BondedCompanionCoreApiOperations operations =
                new BondedCompanionCoreApiOperations(
                        store, rosters, policies, transitions, projections,
                        changes, diagnostics, clock
                );
        BondedCompanionApiFacade api = new BondedCompanionApiFacade(
                runtime::readiness,
                store, changes, diagnostics, operations
        );
        HytaleBondedCompanionPaymentRecovery paymentRecovery =
                new HytaleBondedCompanionPaymentRecovery(
                        new BondedCompanionPaymentRecoveryService(
                                store, clock,
                                profile -> changes.publishCommitted(
                                        new BondedCompanionChangedEvent(
                                                profile.profileId(),
                                                profile.ownerUuid(),
                                                profile.rosterId(),
                                                BondedCompanionStateView.DEAD,
                                                BondedCompanionStateView.STORED,
                                                profile.revision(), "revived"),
                                        BondedCompanionChangePublisher
                                                .WorldEffectOutcome
                                                .CONFIRMED)));
        SqliteBondedCompanionCapturePersistenceAdapter capturePersistence =
                new SqliteBondedCompanionCapturePersistenceAdapter(
                        rosters, transitions, store, store, durability, cleanup,
                        captureEvents
                );
        BondedCompanionCaptureAuthor captureAuthor =
                new BondedCompanionCaptureAuthor(
                        capturePersistence::validate,
                        capturePersistence::store,
                        capturePersistence::cleanup,
                        BondedCompanionCaptureFeedbackDispatcher.production(
                                logger),
                        (intent, failure) -> logCapturePolicyFailure(
                                logger, intent, failure)
                );
        if (started.availability().available()) {
            long startupTime = clock.getAsLong();
            publishPendingCaptureEvents(captureEvents, diagnostics, 128);
            durability.replayPendingCleanup(
                    cleanup, startupTime, 128
            );
            observer.onStartup(
                    durability.activeLeases(256).stream()
                            .filter(lease -> lease.phase()
                                    == BondedCompanionProjectionValidator
                                    .LeasePhase.PENDING)
                            .toList(),
                    startupTime
            );
        }
        return new TameworkBondedCompanionComposition(
                runtime, api, changes, diagnostics,
                transitions, projections, observer, expiry,
                cleanup, durability, world, store, clock, captureAuthor,
                captureEvents, paymentRecovery
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

    private static void logCapturePolicyFailure(
            HytaleLogger logger,
            BondedCompanionCaptureIntent intent,
            RuntimeException failure
    ) {
        if (logger == null) return;
        var entry = logger.at(Level.WARNING);
        if (failure != null) entry = entry.withCause(failure);
        entry.log("Bonded capture policy unavailable (roster="
                + (intent == null ? null : intent.rosterId()) + ", role="
                + (intent == null ? null : intent.roleId()) + ").");
    }

    @Nonnull
    public BondedCompanionDiagnosticContributor diagnostics() {
        return diagnostics;
    }

    /** Reconciles one started world using only exact persisted leases. */
    public void onWorldLoad(@Nonnull String worldKey) {
        if (!operational()) return;
        observer.onWorldLoad(worldKey, durability.activeLeases(256),
                clock.getAsLong());
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
        long now = clock.getAsLong();
        if (previous == null || previous.equals(worldKey)) {
            observer.onPlayerJoin(ownerUuid, durability.activeLeases(256), now);
        } else {
            observer.onPlayerWorldTransfer(ownerUuid, previous, worldKey,
                    durability.activeLeases(256), now);
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

    /** Defers retained-payment repair until a Player is live in its store. */
    public void onPlayerPaymentReady(
            @Nonnull com.hypixel.hytale.server.core.universe.world.World world,
            @Nonnull UUID ownerUuid
    ) {
        if (operational()) paymentRecovery.onPlayerAdded(world, ownerUuid);
    }

    /** Stores exact active projections when their owner disconnects. */
    public void onPlayerLogout(@Nonnull UUID ownerUuid) {
        if (!operational()) return;
        ownerWorlds.remove(ownerUuid);
        observer.onPlayerLogout(ownerUuid, durability.activeLeases(256),
                clock.getAsLong());
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
    public void onConfirmedDeath(@Nonnull String worldKey, @Nonnull UUID npcUuid) {
        if (!operational()) return;
        for (var lease : durability.activeLeases(256)) {
            if (!worldKey.equals(lease.worldKey())
                    || !npcUuid.equals(lease.liveNpcUuid())) continue;
            var projection = world.readExact(lease);
            if (projection != null) {
                observer.onConfirmedDeath(lease, projection, clock.getAsLong());
            }
            return;
        }
    }

    /** Drives bounded cleanup, operation pruning, and lease expiry. */
    public void maintenanceTick() {
        if (!operational()) return;
        long now = clock.getAsLong();
        durability.replayPendingCleanup(cleanup, now, 64);
        publishPendingCaptureEvents(captureEvents, diagnostics, 64);
        store.pruneOperations(now, 64);
        expiry.tick(now);
    }

    private boolean operational() {
        return !closed.get() && persistence.readiness()
                .availability().available();
    }

    private static void publishPendingCaptureEvents(
            BondedCompanionCaptureEventPublisher captureEvents,
            BondedCompanionDiagnosticContributor diagnostics,
            int limit
    ) {
        if (captureEvents == null) return;
        try {
            captureEvents.publishPending(limit);
        } catch (RuntimeException | LinkageError failure) {
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
