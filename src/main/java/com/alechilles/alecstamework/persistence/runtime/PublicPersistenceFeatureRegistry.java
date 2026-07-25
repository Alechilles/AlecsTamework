package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptCooldownIndex;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CoopSlotRegistrationDefinition;
import com.alechilles.alecstamework.companion.dormant.CompanionDormantTransitionDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationDefinition;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionProjectionIndex;
import com.alechilles.alecstamework.companion.identity.CompanionAliasRotationDefinition;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationReconciliationDefinition;
import com.alechilles.alecstamework.companion.population.OwnerPopulationTransitionDefinition;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignmentDefinition;
import com.alechilles.alecstamework.companion.restoration.CompanionRestorationDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The complete static descriptor set for replacement public persistence behavior. */
public final class PublicPersistenceFeatureRegistry {
    public static final PersistenceFeatureId IDENTITY =
            new PersistenceFeatureId("core_identity");
    public static final PersistenceFeatureId LIFECYCLE =
            new PersistenceFeatureId("core_lifecycle");
    public static final PersistenceFeatureId OWNER_POPULATION =
            new PersistenceFeatureId("owner_population");
    public static final PersistenceFeatureId POPULATION_GROUPS =
            new PersistenceFeatureId("population_groups");
    public static final PersistenceFeatureId COMMAND_ROSTER =
            new PersistenceFeatureId("command_roster");
    public static final PersistenceFeatureId TIMED_SUMMON =
            new PersistenceFeatureId("timed_summon");
    public static final PersistenceFeatureId PROVISIONING =
            new PersistenceFeatureId("provisioning");
    public static final PersistenceFeatureId ECONOMIC_COMPENSATION =
            new PersistenceFeatureId("economic_compensation");
    public static final PersistenceFeatureId CAPTURE =
            new PersistenceFeatureId("capture");
    public static final PersistenceFeatureId DORMANT =
            new PersistenceFeatureId("death_and_lost");
    public static final PersistenceFeatureId COOP =
            new PersistenceFeatureId("coop");
    public static final PersistenceFeatureId EXTENSION =
            new PersistenceFeatureId("extension_data");
    public static final ProjectionConsumerId PROFILE_OBSERVER =
            new ProjectionConsumerId("public_profile_observer");
    public static final ProjectionConsumerId COOP_INDEX =
            new ProjectionConsumerId("coop_residency_index");
    public static final ProjectionConsumerId OWNER_POPULATION_INDEX =
            new ProjectionConsumerId("owner_population_index");
    public static final ProjectionConsumerId POPULATION_GROUP_INDEX =
            new ProjectionConsumerId("population_group_index");
    public static final ProjectionConsumerId COMMAND_ROSTER_INDEX =
            new ProjectionConsumerId("command_roster_index");
    public static final ProjectionConsumerId TIMED_SUMMON_INDEX =
            new ProjectionConsumerId("timed_summon_index");
    public static final ProjectionConsumerId PROVISIONING_INDEX =
            new ProjectionConsumerId("provisioning_index");
    public static final ProjectionConsumerId CAPTURE_COOLDOWN_INDEX =
            CaptureAttemptCooldownIndex.CONSUMER_ID;
    public static final ProjectionConsumerId EXTENSION_INDEX =
            ProfileExtensionProjectionIndex.CONSUMER_ID;

    private PublicPersistenceFeatureRegistry() {
    }

    public static PersistenceFeatureRegistry create() {
        return new PersistenceFeatureRegistry(List.of(
                identity(),
                lifecycle(),
                ownerPopulation(),
                populationGroups(),
                commandRoster(),
                PublicPersistenceTimedFeature.create(),
                PublicPersistenceProvisioningFeature.create(),
                economicCompensation(),
                capture(),
                dormant(),
                coop(),
                extension()
        ));
    }

    private static PersistenceFeatureDescriptor identity() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                IDENTITY,
                PersistenceFeatureDomain.IDENTITY,
                Set.of(
                        "companion_profile",
                        "companion_alias",
                        "companion_tool_link"
                ),
                List.of(
                        CompanionProfileMutationDefinition.INSTANCE,
                        CompanionAliasRotationDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        CompanionProfileMutationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE),
                        CompanionAliasRotationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE)
                ),
                Set.of(),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                Set.of(
                        PersistenceStartupNode.LOAD_CANONICAL,
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE
                )
        );
    }

    private static PersistenceFeatureDescriptor lifecycle() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                LIFECYCLE,
                PersistenceFeatureDomain.LIFECYCLE,
                Set.of("companion_lifecycle", "companion_snapshot"),
                List.of(),
                Map.of(),
                Set.of(IDENTITY),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                Set.of(
                        PersistenceStartupNode.LOAD_CANONICAL,
                        PersistenceStartupNode.RECONCILE_WORLD
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE
                )
        );
    }

    private static PersistenceFeatureDescriptor ownerPopulation() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                OWNER_POPULATION,
                PersistenceFeatureDomain.POPULATION,
                Set.of(
                        "owner_population_reservation",
                        "population_evidence_batch",
                        "population_evidence_observation"
                ),
                List.of(
                        OwnerPopulationTransitionDefinition.INSTANCE,
                        OwnerPopulationReconciliationDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        OwnerPopulationTransitionDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER
                        ),
                        OwnerPopulationReconciliationDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER
                        )
                ),
                Set.of(IDENTITY, LIFECYCLE),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                Set.of(
                        PersistenceStartupNode.LOAD_CANONICAL,
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS,
                        PersistenceStartupNode.RECONCILE_WORLD
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER
                )
        );
    }

    private static PersistenceFeatureDescriptor populationGroups() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                POPULATION_GROUPS,
                PersistenceFeatureDomain.POPULATION,
                Set.of(
                        "population_group_classification",
                        "population_group_membership",
                        "population_group_reservation"
                ),
                List.of(PopulationGroupAssignmentDefinition.INSTANCE),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        PopulationGroupAssignmentDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER
                        )
                ),
                Set.of(IDENTITY, LIFECYCLE, OWNER_POPULATION),
                Set.of(POPULATION_GROUP_INDEX),
                Set.of(
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER
                )
        );
    }

    private static PersistenceFeatureDescriptor commandRoster() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                COMMAND_ROSTER,
                PersistenceFeatureDomain.COMMAND,
                Set.of(
                        "command_family",
                        "command_roster_membership"
                ),
                List.of(
                        CommandRosterMembershipDefinition.INSTANCE,
                        CommandRosterTransitionDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        CommandRosterMembershipDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER,
                                OperationScopeType.COMMAND_FAMILY
                        ),
                        CommandRosterTransitionDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER,
                                OperationScopeType.COMMAND_FAMILY
                        )
                ),
                Set.of(
                        IDENTITY,
                        LIFECYCLE,
                        OWNER_POPULATION,
                        POPULATION_GROUPS
                ),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                PublicPersistenceFeatureDescriptorFactory.worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                )
        );
    }

    private static PersistenceFeatureDescriptor capture() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                CAPTURE,
                PersistenceFeatureDomain.CAPTURE,
                Set.of(),
                List.of(
                        CompanionCaptureDefinition.INSTANCE,
                        CompanionCaptureReleaseDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        CompanionCaptureDefinition.INSTANCE,
                        PublicPersistenceFeatureDescriptorFactory.policy(
                                Set.of(
                                        OperationScopeType.PROFILE,
                                        OperationScopeType.OWNER
                                ),
                                Set.of(
                                        OperationScopeType.COMMAND_FAMILY
                                )
                        ),
                        CompanionCaptureReleaseDefinition.INSTANCE,
                        PublicPersistenceFeatureDescriptorFactory.policy(
                                Set.of(OperationScopeType.PROFILE),
                                Set.of(OperationScopeType.OWNER)
                        )
                ),
                Set.of(IDENTITY, LIFECYCLE, ECONOMIC_COMPENSATION),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX,
                        CAPTURE_COOLDOWN_INDEX
                ),
                PublicPersistenceFeatureDescriptorFactory.worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                )
        );
    }

    private static PersistenceFeatureDescriptor economicCompensation() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                ECONOMIC_COMPENSATION,
                PersistenceFeatureDomain.COMPENSATION,
                Set.of("refund_claim", "refund_claim_item"),
                List.of(),
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECOVER_OPERATIONS
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.OWNER
                )
        );
    }

    private static PersistenceFeatureDescriptor dormant() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                DORMANT,
                PersistenceFeatureDomain.DORMANT,
                Set.of("dormant_transition"),
                List.of(
                        CompanionDormantTransitionDefinition.INSTANCE,
                        CompanionRestorationDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        CompanionDormantTransitionDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE),
                        CompanionRestorationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE)
                ),
                Set.of(IDENTITY, LIFECYCLE),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                PublicPersistenceFeatureDescriptorFactory.worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE
                )
        );
    }

    private static PersistenceFeatureDescriptor coop() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                COOP,
                PersistenceFeatureDomain.COOP,
                Set.of("coop_slot", "coop_residency"),
                List.of(
                        CoopSlotRegistrationDefinition.INSTANCE,
                        CompanionCoopCaptureDefinition.INSTANCE,
                        CompanionCoopReleaseDefinition.INSTANCE
                ),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        CoopSlotRegistrationDefinition.INSTANCE,
                        Set.of(OperationScopeType.COOP),
                        CompanionCoopCaptureDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.COOP
                        ),
                        CompanionCoopReleaseDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.COOP
                        )
                ),
                Set.of(IDENTITY, LIFECYCLE),
                Set.of(
                        PROFILE_OBSERVER,
                        COOP_INDEX,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                PublicPersistenceFeatureDescriptorFactory.worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.COOP
                )
        );
    }

    private static PersistenceFeatureDescriptor extension() {
        return PublicPersistenceFeatureDescriptorFactory.create(
                EXTENSION,
                PersistenceFeatureDomain.EXTENSION,
                Set.of("profile_extension_data"),
                List.of(ProfileExtensionMutationDefinition.INSTANCE),
                PublicPersistenceFeatureDescriptorFactory.scopes(
                        ProfileExtensionMutationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE)
                ),
                Set.of(IDENTITY),
                Set.of(EXTENSION_INDEX),
                Set.of(
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECOVER_OPERATIONS
                ),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE
                )
        );
    }

}
