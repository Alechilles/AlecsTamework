package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceContainmentListener;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;

/** All replacement operation adapters composed over one shared engine protocol. */
final class SqlitePublicOperationSet {
    private final SqliteOperationEngine engine;
    private final SqliteCompanionProfileOperations profiles;
    private final SqliteCompanionAliasRotationOperations aliases;
    private final SqliteOwnerPopulationTransitionOperations ownerPopulation;
    private final SqliteOwnerPopulationReconciliationOperations
            ownerPopulationReconciliation;
    private final SqlitePopulationGroupAssignmentOperations populationGroups;
    private final SqliteCompanionCaptureOperations captures;
    private final SqliteCompanionCaptureReleaseOperations captureReleases;
    private final SqliteCompanionDormantOperations dormant;
    private final SqliteCompanionRestorationOperations restorations;
    private final SqliteCoopSlotOperations coopSlots;
    private final SqliteCompanionCoopCaptureOperations coopCaptures;
    private final SqliteCompanionCoopReleaseOperations coopReleases;
    private final SqliteProfileExtensionOperations extensions;

    SqlitePublicOperationSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull SqlitePublicProjectionSet projections,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds
    ) {
        if (registry == null || kernel == null || projections == null
                || admission == null || clock == null || refunds == null) {
            throw new IllegalArgumentException(
                    "Public operation dependencies are required"
            );
        }
        engine = new SqliteOperationEngine(
                registry.operationDefinitions(),
                kernel.units(),
                admission,
                admission instanceof PersistenceContainmentListener listener
                        ? listener
                        : PersistenceContainmentListener.NO_OP
        );
        SqliteOperationEvidenceReader evidence =
                new SqliteOperationEvidenceReader(kernel.reads());
        SqliteOperationPublisher publisher = new SqliteOperationPublisher(
                engine,
                evidence,
                projections.coordinator(),
                clock
        );
        SqliteDatabaseOperationCoordinator database =
                new SqliteDatabaseOperationCoordinator(
                        engine,
                        evidence,
                        projections.coordinator(),
                        clock
                );
        profiles = new SqliteCompanionProfileOperations(
                database,
                projections.requiredFor(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                )
        );
        aliases = new SqliteCompanionAliasRotationOperations(
                database,
                projections.requiredFor(
                        CompanionAliasRotationDefinition.INSTANCE.kind()
                )
        );
        ownerPopulation = new SqliteOwnerPopulationTransitionOperations(
                database,
                projections.requiredFor(
                        OwnerPopulationTransitionDefinition.INSTANCE.kind()
                )
        );
        ownerPopulationReconciliation =
                new SqliteOwnerPopulationReconciliationOperations(
                        database,
                        projections.requiredFor(
                                OwnerPopulationReconciliationDefinition
                                        .INSTANCE.kind()
                        )
                );
        populationGroups = new SqlitePopulationGroupAssignmentOperations(
                database,
                projections.requiredFor(
                        PopulationGroupAssignmentDefinition.INSTANCE.kind()
                )
        );
        captures = new SqliteCompanionCaptureOperations(
                engine,
                publisher,
                clock,
                refunds,
                projections.requiredFor(
                        CompanionCaptureDefinition.INSTANCE.kind()
                )
        );
        captureReleases = new SqliteCompanionCaptureReleaseOperations(
                engine,
                publisher,
                clock,
                projections.requiredFor(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind()
                )
        );
        dormant = new SqliteCompanionDormantOperations(
                engine,
                evidence,
                projections.coordinator(),
                clock,
                projections.requiredFor(
                        CompanionDormantTransitionDefinition.INSTANCE.kind()
                )
        );
        restorations = new SqliteCompanionRestorationOperations(
                engine,
                publisher,
                clock,
                projections.requiredFor(
                        CompanionRestorationDefinition.INSTANCE.kind()
                )
        );
        coopSlots = new SqliteCoopSlotOperations(
                database,
                projections.requiredFor(
                        CoopSlotRegistrationDefinition.INSTANCE.kind()
                )
        );
        coopCaptures = new SqliteCompanionCoopCaptureOperations(
                engine,
                publisher,
                clock,
                projections.requiredFor(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                )
        );
        coopReleases = new SqliteCompanionCoopReleaseOperations(
                engine,
                publisher,
                clock,
                projections.requiredFor(
                        CompanionCoopReleaseDefinition.INSTANCE.kind()
                )
        );
        extensions = new SqliteProfileExtensionOperations(
                database,
                projections.requiredFor(
                        ProfileExtensionMutationDefinition.INSTANCE.kind()
                )
        );
    }

    SqliteOperationEngine engine() {
        return engine;
    }

    SqliteCompanionProfileOperations profiles() {
        return profiles;
    }

    SqliteCompanionAliasRotationOperations aliases() {
        return aliases;
    }

    SqliteOwnerPopulationTransitionOperations ownerPopulation() {
        return ownerPopulation;
    }

    SqliteOwnerPopulationReconciliationOperations
    ownerPopulationReconciliation() {
        return ownerPopulationReconciliation;
    }

    SqlitePopulationGroupAssignmentOperations populationGroups() {
        return populationGroups;
    }

    SqliteCompanionCaptureOperations captures() {
        return captures;
    }

    SqliteCompanionCaptureReleaseOperations captureReleases() {
        return captureReleases;
    }

    SqliteCompanionDormantOperations dormant() {
        return dormant;
    }

    SqliteCompanionRestorationOperations restorations() {
        return restorations;
    }

    SqliteCoopSlotOperations coopSlots() {
        return coopSlots;
    }

    SqliteCompanionCoopCaptureOperations coopCaptures() {
        return coopCaptures;
    }

    SqliteCompanionCoopReleaseOperations coopReleases() {
        return coopReleases;
    }

    SqliteProfileExtensionOperations extensions() {
        return extensions;
    }
}
