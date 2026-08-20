package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionDefinition;
import com.alechilles.alecstamework.companion.population.domain.PopulationDomainAdmissionOperation;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceContainmentListener;
import com.alechilles.alecstamework.persistence.control.PersistenceOperationAdmissionGate;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection
        .ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.runtime
        .PersistenceLifecycleAdmissionGateway;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** All replacement operation adapters composed over one shared engine protocol. */
final class SqlitePublicOperationSet {
    private final SqliteOperationEngine engine;
    private final SqliteCompanionProfileOperations profiles;
    private final SqliteCompanionAliasRotationOperations aliases;
    private final SqliteOwnerPopulationTransitionOperations ownerPopulation;
    private final SqliteOwnerPopulationReconciliationOperations
            ownerPopulationReconciliation;
    private final SqlitePopulationGroupAssignmentOperations populationGroups;
    private final PopulationDomainAdmissionOperation populationDomains;
    private final SqliteCommandRosterMembershipOperations commandRosters;
    private final SqliteCommandRosterTransitionOperations commandTransitions;
    private final SqliteTimedSummonLeaseOperations timedSummons;
    private final SqliteTimedSummonTransitionOperations timedTransitions;
    private final SqliteCompanionProvisioningOperations provisioning;
    private final SqliteProvisioningActivationOperations
            provisioningActivations;
    private final SqliteCompanionCaptureOperations captures;
    private final SqliteCompanionCaptureReleaseOperations captureReleases;
    private final SqliteCompanionDormantOperations dormant;
    private final SqliteCompanionRestorationOperations restorations;
    private final SqliteCoopSlotOperations coopSlots;
    private final SqliteCompanionCoopCaptureOperations coopCaptures;
    private final SqliteCompanionCoopReleaseOperations coopReleases;
    private final SqlitePaidRevivalOperations paidRevivals;
    private final SqliteProfileExtensionOperations extensions;
    private final SqliteLifecycleAdmissionBinding lifecycleAdmission;

    SqlitePublicOperationSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull SqlitePublicProjectionSet projections,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull ProjectionPublicationContext publicationContext
    ) {
        this(
                registry,
                kernel,
                projections,
                admission,
                clock,
                refunds,
                publicationContext,
                null
        );
    }

    SqlitePublicOperationSet(
            @Nonnull PersistenceFeatureRegistry registry,
            @Nonnull SqlitePersistenceKernel kernel,
            @Nonnull SqlitePublicProjectionSet projections,
            @Nonnull PersistenceOperationAdmissionGate admission,
            @Nonnull LongSupplier clock,
            @Nonnull RefundDeliveryBoundary refunds,
            @Nonnull ProjectionPublicationContext publicationContext,
            @Nullable SqliteLifecycleAdmissionBinding sharedLifecycleAdmission
    ) {
        if (registry == null || kernel == null || projections == null
                || admission == null || clock == null || refunds == null
                || publicationContext == null) {
            throw new IllegalArgumentException(
                    "Public operation dependencies are required"
            );
        }
        lifecycleAdmission = sharedLifecycleAdmission == null
                ? new SqliteLifecycleAdmissionBinding()
                : sharedLifecycleAdmission;
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
        Function<OperationKind, List<ProjectionConsumer>> consumers =
                operationKind -> projections.requiredFor(
                        operationKind, publicationContext
                );
        SqliteOperationReader operationReader = new SqliteOperationReader(
                kernel.reads()
        );
        SqliteLifecycleAdmissionSourceReader lifecycleSources =
                new SqliteLifecycleAdmissionSourceReader(kernel.reads());
        profiles = new SqliteCompanionProfileOperations(
                database,
                consumers.apply(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                )
        );
        aliases = new SqliteCompanionAliasRotationOperations(
                database,
                consumers.apply(
                        CompanionAliasRotationDefinition.INSTANCE.kind()
                )
        );
        ownerPopulation = new SqliteOwnerPopulationTransitionOperations(
                database,
                consumers.apply(
                        OwnerPopulationTransitionDefinition.INSTANCE.kind()
                )
        );
        ownerPopulationReconciliation =
                new SqliteOwnerPopulationReconciliationOperations(
                        database,
                        consumers.apply(
                                OwnerPopulationReconciliationDefinition
                                        .INSTANCE.kind()
                        )
                );
        populationGroups = new SqlitePopulationGroupAssignmentOperations(
                database,
                consumers.apply(
                        PopulationGroupAssignmentDefinition.INSTANCE.kind()
                )
        );
        populationDomains = new PopulationDomainAdmissionOperation(
                engine,
                publisher,
                operationReader,
                consumers.apply(
                        PopulationDomainAdmissionDefinition.INSTANCE.kind()
                ),
                clock
        );
        commandRosters = new SqliteCommandRosterMembershipOperations(
                database,
                consumers.apply(
                        CommandRosterMembershipDefinition.INSTANCE.kind()
                )
        );
        commandTransitions = new SqliteCommandRosterTransitionOperations(
                database,
                consumers.apply(
                        CommandRosterTransitionDefinition.INSTANCE.kind()
                )
        );
        timedSummons = new SqliteTimedSummonLeaseOperations(
                database,
                consumers.apply(
                        TimedSummonLeaseMutationDefinition.INSTANCE.kind()
                )
        );
        timedTransitions = new SqliteTimedSummonTransitionOperations(
                engine,
                publisher,
                clock,
                consumers.apply(
                        TimedSummonTransitionDefinition.INSTANCE.kind()
                )
        );
        provisioning = new SqliteCompanionProvisioningOperations(
                database,
                consumers.apply(
                        CompanionProvisioningDefinition.INSTANCE.kind()
                )
        );
        provisioningActivations =
                new SqliteProvisioningActivationOperations(
                        engine,
                        publisher,
                        clock,
                        consumers.apply(
                                ProvisioningActivationDefinition
                                        .INSTANCE.kind()
                        )
                );
        captures = new SqliteCompanionCaptureOperations(
                engine,
                publisher,
                clock,
                refunds,
                operationReader,
                lifecycleAdmission,
                lifecycleSources,
                consumers.apply(
                        CompanionCaptureDefinition.INSTANCE.kind()
                )
        );
        captureReleases = new SqliteCompanionCaptureReleaseOperations(
                engine,
                publisher,
                clock,
                operationReader,
                lifecycleAdmission,
                consumers.apply(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind()
                )
        );
        dormant = new SqliteCompanionDormantOperations(
                engine,
                evidence,
                projections.coordinator(),
                clock,
                consumers.apply(
                        CompanionDormantTransitionDefinition.INSTANCE.kind()
                )
        );
        restorations = new SqliteCompanionRestorationOperations(
                engine,
                publisher,
                clock,
                consumers.apply(
                        CompanionRestorationDefinition.INSTANCE.kind()
                )
        );
        coopSlots = new SqliteCoopSlotOperations(
                database,
                consumers.apply(
                        CoopSlotRegistrationDefinition.INSTANCE.kind()
                )
        );
        coopCaptures = new SqliteCompanionCoopCaptureOperations(
                engine,
                publisher,
                clock,
                consumers.apply(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                )
        );
        coopReleases = new SqliteCompanionCoopReleaseOperations(
                engine,
                publisher,
                clock,
                consumers.apply(
                        CompanionCoopReleaseDefinition.INSTANCE.kind()
                )
        );
        paidRevivals = new SqlitePaidRevivalOperations(
                engine,
                publisher,
                kernel.reads(),
                clock,
                refunds,
                consumers.apply(
                        PaidRevivalDefinition.INSTANCE.kind()
                )
        );
        extensions = new SqliteProfileExtensionOperations(
                database,
                consumers.apply(
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

    PopulationDomainAdmissionOperation populationDomains() {
        return populationDomains;
    }

    SqliteCommandRosterMembershipOperations commandRosters() {
        return commandRosters;
    }

    SqliteCommandRosterTransitionOperations commandTransitions() {
        return commandTransitions;
    }

    SqliteTimedSummonLeaseOperations timedSummons() {
        return timedSummons;
    }

    SqliteTimedSummonTransitionOperations timedTransitions() {
        return timedTransitions;
    }

    SqliteCompanionProvisioningOperations provisioning() {
        return provisioning;
    }

    SqliteProvisioningActivationOperations provisioningActivations() {
        return provisioningActivations;
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

    SqlitePaidRevivalOperations paidRevivals() {
        return paidRevivals;
    }

    SqliteProfileExtensionOperations extensions() {
        return extensions;
    }

    void bindLifecycleAdmission(
            @Nonnull PersistenceLifecycleAdmissionGateway gateway
    ) {
        lifecycleAdmission.bind(gateway);
    }

    @Nonnull
    SqliteLifecycleAdmissionBinding lifecycleAdmission() {
        return lifecycleAdmission;
    }
}
