package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
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
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.persistence.compensation.RefundDeliveryBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
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
    private final SqliteCommandRosterMembershipOperations commandRosters;
    private final SqliteCommandRosterTransitionOperations commandTransitions;
    private final SqliteTimedSummonLeaseOperations timedSummons;
    private final SqliteTimedSummonTransitionOperations timedTransitions;
    private final SqliteCompanionProvisioningOperations provisioning;
    private final SqliteProvisioningActivationOperations
            provisioningActivations;
    private final SqliteCompanionCaptureOperations captures;
    private final SqliteCompanionDormantOperations dormant;
    private final SqliteCompanionRestorationOperations restorations;
    private final SqlitePaidRevivalOperations paidRevivals;
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
                admission
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
                engine,
                publisher,
                clock,
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
        commandRosters = new SqliteCommandRosterMembershipOperations(
                database,
                projections.requiredFor(
                        CommandRosterMembershipDefinition.INSTANCE.kind()
                )
        );
        commandTransitions = new SqliteCommandRosterTransitionOperations(
                database,
                projections.requiredFor(
                        CommandRosterTransitionDefinition.INSTANCE.kind()
                )
        );
        timedSummons = new SqliteTimedSummonLeaseOperations(
                database,
                projections.requiredFor(
                        TimedSummonLeaseMutationDefinition.INSTANCE.kind()
                )
        );
        timedTransitions = new SqliteTimedSummonTransitionOperations(
                engine,
                publisher,
                clock,
                projections.requiredFor(
                        TimedSummonTransitionDefinition.INSTANCE.kind()
                )
        );
        provisioning = new SqliteCompanionProvisioningOperations(
                database,
                projections.requiredFor(
                        CompanionProvisioningDefinition.INSTANCE.kind()
                )
        );
        provisioningActivations =
                new SqliteProvisioningActivationOperations(
                        engine,
                        publisher,
                        clock,
                        projections.requiredFor(
                                ProvisioningActivationDefinition
                                        .INSTANCE.kind()
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
        paidRevivals = new SqlitePaidRevivalOperations(
                engine,
                publisher,
                kernel.reads(),
                clock,
                refunds,
                projections.requiredFor(
                        PaidRevivalDefinition.INSTANCE.kind()
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

    SqliteCompanionDormantOperations dormant() {
        return dormant;
    }

    SqliteCompanionRestorationOperations restorations() {
        return restorations;
    }

    SqlitePaidRevivalOperations paidRevivals() {
        return paidRevivals;
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
