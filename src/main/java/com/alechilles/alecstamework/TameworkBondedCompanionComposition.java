package com.alechilles.alecstamework;

import com.alechilles.alecstamework.api.BondedCompanionApi;
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
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionWorldLifecycleObserver;
import com.alechilles.alecstamework.companion.bonded.runtime
        .HytaleBondedCompanionWorldGateway;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionProjectionDurability;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionApiFacade;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionChangePublisher;
import com.alechilles.alecstamework.persistence.bonded.BondedCompanionDataPath;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceRuntime;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionPersistenceReadiness;
import com.alechilles.alecstamework.persistence.bonded
        .BondedCompanionSchemaManager;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticContributor;
import com.alechilles.alecstamework.persistence.diagnostics
        .BondedCompanionDiagnosticSnapshot;
import com.hypixel.hytale.logger.HytaleLogger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
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
    private final AtomicBoolean closed = new AtomicBoolean();

    private TameworkBondedCompanionComposition(
            BondedCompanionPersistenceRuntime persistence,
            BondedCompanionApiFacade api,
            BondedCompanionChangePublisher changes,
            BondedCompanionDiagnosticContributor diagnostics,
            BondedCompanionTransitionService transitions,
            BondedCompanionProjectionService projections,
            BondedCompanionWorldLifecycleObserver observer,
            BondedCompanionExpirySystem expiry
    ) {
        this.persistence = persistence;
        this.api = api;
        this.changes = changes;
        this.diagnostics = diagnostics;
        this.transitions = transitions;
        this.projections = projections;
        this.observer = observer;
        this.expiry = expiry;
    }

    /** Opens the bonded authority without accepting generic persistence state. */
    @Nonnull
    public static TameworkBondedCompanionComposition open(
            @Nonnull Path commonDataDirectory,
            @Nonnull BondedCompanionRosterRegistry rosters,
            @Nullable HytaleLogger logger,
            @Nonnull LongSupplier clock
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
        BondedCompanionDiagnosticContributor diagnostics =
                new BondedCompanionDiagnosticContributor(
                        runtime::readiness,
                        store::diagnostics,
                        BondedCompanionSchemaManager.VERSION
                );
        BondedCompanionChangePublisher changes =
                new BondedCompanionChangePublisher(logger);
        BondedCompanionApiFacade api = new BondedCompanionApiFacade(
                runtime::readiness, store, changes, diagnostics
        );
        BondedCompanionPersistenceReadiness started = runtime.start();
        if (!started.availability().available()) {
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.STARTUP
            );
        }
        HytaleBondedCompanionWorldGateway world =
                new HytaleBondedCompanionWorldGateway();
        BondedCompanionTransitionService transitions =
                new BondedCompanionTransitionService(
                        new BondedCompanionPolicyResolver(rosters)
                );
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
                                .toList()
                );
        BondedCompanionExpirySystem expiry = new BondedCompanionExpirySystem(
                observer, durability::findExpired, 64
        );
        if (started.availability().available()) {
            long startupTime = clock.getAsLong();
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
                transitions, projections, observer, expiry
        );
    }

    @Nonnull
    public BondedCompanionApi api() {
        return api;
    }

    @Nonnull
    public BondedCompanionDiagnosticContributor diagnostics() {
        return diagnostics;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            api.close();
            changes.close();
            persistence.close();
            diagnostics.recordFailure(
                    BondedCompanionDiagnosticSnapshot.FailureCategory.CLOSED
            );
        }
    }
}
