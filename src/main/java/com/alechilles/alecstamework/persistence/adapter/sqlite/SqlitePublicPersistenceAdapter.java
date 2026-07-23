package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.companion.coop.CoopResidencyProjectionIndex;
import com.alechilles.alecstamework.companion.population.OwnerPopulationProjectionIndex;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupProjectionIndex;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Typed SQLite adapter boundary for all released persistence behavior.
 *
 * <p>Gameplay receives operations, queries, and projections only. Connection-bound
 * stores, writer lanes, and transaction infrastructure remain package-private.</p>
 */
public final class SqlitePublicPersistenceAdapter {
    private final SqlitePublicProjectionSet projections;
    private final SqlitePublicOperationSet publicOperations;
    private final SqlitePublicOperationSet recoveryOperations;
    private final SqliteOperationRecoveryCoordinator recovery;
    private final SqlitePublicStartupGateway startup;
    private final SqliteCompanionProfileReader profiles;
    private final SqliteCompanionLifecycleReader lifecycles;
    private final SqliteCompanionCoopReader coops;
    private final SqliteProfileExtensionReader extensions;
    private final SqlitePopulationGroupReader populationGroups;
    private final LongSupplier clock;
    private final PersistenceFeatureRegistry registry;

    public SqlitePublicPersistenceAdapter(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull Consumer<NpcProfileChangedEvent> profileListener
    ) {
        if (registry == null || kernel == null || admission == null
                || clock == null || refunds == null || profileListener == null) {
            throw new IllegalArgumentException(
                    "Public persistence adapter dependencies are required"
            );
        }
        this.clock = clock;
        this.registry = registry;
        projections = new SqlitePublicProjectionSet(
                registry,
                kernel,
                clock,
                profileListener
        );
        publicOperations = new SqlitePublicOperationSet(
                registry,
                kernel,
                projections,
                admission,
                clock,
                refunds
        );
        recoveryOperations = new SqlitePublicOperationSet(
                registry,
                kernel,
                projections,
                PersistenceOperationAdmissionGate.allowAll(),
                clock,
                refunds
        );
        recovery = new SqliteOperationRecoveryCoordinator(
                registry.operationDefinitions(),
                kernel.reads(),
                kernel.units()
        );
        startup = new SqlitePublicStartupGateway(kernel.reads());
        profiles = new SqliteCompanionProfileReader(kernel.reads());
        lifecycles = new SqliteCompanionLifecycleReader(kernel.reads());
        coops = new SqliteCompanionCoopReader(kernel.reads());
        extensions = new SqliteProfileExtensionReader(kernel.reads());
        populationGroups = new SqlitePopulationGroupReader(kernel.reads());
    }

    @Nonnull
    public SqliteCompanionProfileOperations profileOperations() {
        return publicOperations.profiles();
    }

    @Nonnull
    public SqliteCompanionAliasRotationOperations aliasOperations() {
        return publicOperations.aliases();
    }

    @Nonnull
    public SqliteOwnerPopulationTransitionOperations
    ownerPopulationOperations() {
        return publicOperations.ownerPopulation();
    }

    @Nonnull
    public SqliteOwnerPopulationReconciliationOperations
    ownerPopulationReconciliationOperations() {
        return publicOperations.ownerPopulationReconciliation();
    }

    @Nonnull
    public SqlitePopulationGroupAssignmentOperations
    populationGroupOperations() {
        return publicOperations.populationGroups();
    }

    @Nonnull
    public SqliteCompanionCaptureOperations captureOperations() {
        return publicOperations.captures();
    }

    @Nonnull
    public SqliteCompanionDormantOperations dormantOperations() {
        return publicOperations.dormant();
    }

    @Nonnull
    public SqliteCompanionRestorationOperations restorationOperations() {
        return publicOperations.restorations();
    }

    @Nonnull
    public SqliteCoopSlotOperations coopSlotOperations() {
        return publicOperations.coopSlots();
    }

    @Nonnull
    public SqliteCompanionCoopCaptureOperations coopCaptureOperations() {
        return publicOperations.coopCaptures();
    }

    @Nonnull
    public SqliteCompanionCoopReleaseOperations coopReleaseOperations() {
        return publicOperations.coopReleases();
    }

    @Nonnull
    public SqliteProfileExtensionOperations extensionOperations() {
        return publicOperations.extensions();
    }

    @Nonnull
    public SqliteCompanionProfileReader profileReader() {
        return profiles;
    }

    @Nonnull
    public SqliteCompanionLifecycleReader lifecycleReader() {
        return lifecycles;
    }

    @Nonnull
    public SqliteCompanionCoopReader coopReader() {
        return coops;
    }

    @Nonnull
    public SqliteProfileExtensionReader extensionReader() {
        return extensions;
    }

    @Nonnull
    public SqlitePopulationGroupReader populationGroupReader() {
        return populationGroups;
    }

    @Nonnull
    public CoopResidencyProjectionIndex coopIndex() {
        return projections.coopIndex();
    }

    @Nonnull
    public OwnerPopulationProjectionIndex ownerPopulationIndex() {
        return projections.ownerPopulationIndex();
    }

    @Nonnull
    public PopulationGroupProjectionIndex populationGroupIndex() {
        return projections.populationGroupIndex();
    }

    /** Loads the complete canonical startup evidence through the read lane. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<SqlitePublicCanonicalSnapshot>>
    loadCanonical() {
        return startup.loadCanonical();
    }

    /** Rebuilds canonical derived state and catches every registry consumer up. */
    @Nonnull
    public CompletionStage<SqlitePublicProjectionStartupResult>
    buildProjections() {
        return projections.rebuildAndCatchUp(
                coops, lifecycles, populationGroups
        );
    }

    /** Scans and resumes every recoverable operation through typed adapters. */
    @Nonnull
    public CompletionStage<SqlitePublicRecoveryResult> recover(
            @Nonnull PublicPersistenceLiveBoundaries boundaries,
            @Nonnull String workerId
    ) {
        return new SqlitePublicRecoveryDispatcher(
                recovery,
                registry,
                recoveryOperations,
                boundaries,
                clock,
                workerId
        ).recover();
    }

    SqlitePublicProjectionSet projections() {
        return projections;
    }

    SqlitePublicOperationSet publicOperations() {
        return publicOperations;
    }

    SqlitePublicOperationSet recoveryOperations() {
        return recoveryOperations;
    }

    SqliteOperationRecoveryCoordinator recovery() {
        return recovery;
    }

    SqlitePublicStartupGateway startup() {
        return startup;
    }
}
