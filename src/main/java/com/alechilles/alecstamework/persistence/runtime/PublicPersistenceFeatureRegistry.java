package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
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
import com.alechilles.alecstamework.persistence.control.PersistenceCircuitPolicy;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDomain;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureHookId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureId;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
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
        return descriptor(
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
                scopes(
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
        return descriptor(
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
        return descriptor(
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
                scopes(
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
        return descriptor(
                POPULATION_GROUPS,
                PersistenceFeatureDomain.POPULATION,
                Set.of(
                        "population_group_classification",
                        "population_group_membership",
                        "population_group_reservation"
                ),
                List.of(PopulationGroupAssignmentDefinition.INSTANCE),
                scopes(
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
        return descriptor(
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
                scopes(
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
                worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                )
        );
    }

    private static PersistenceFeatureDescriptor capture() {
        return descriptor(
                CAPTURE,
                PersistenceFeatureDomain.CAPTURE,
                Set.of(),
                List.of(CompanionCaptureDefinition.INSTANCE),
                scopes(
                        CompanionCaptureDefinition.INSTANCE,
                        Set.of(
                                OperationScopeType.PROFILE,
                                OperationScopeType.OWNER
                        )
                ),
                Set.of(IDENTITY, LIFECYCLE, ECONOMIC_COMPENSATION),
                Set.of(
                        PROFILE_OBSERVER,
                        OWNER_POPULATION_INDEX,
                        POPULATION_GROUP_INDEX,
                        COMMAND_ROSTER_INDEX,
                        TIMED_SUMMON_INDEX
                ),
                worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER
                )
        );
    }

    private static PersistenceFeatureDescriptor economicCompensation() {
        return descriptor(
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
        return descriptor(
                DORMANT,
                PersistenceFeatureDomain.DORMANT,
                Set.of("dormant_transition"),
                List.of(
                        CompanionDormantTransitionDefinition.INSTANCE,
                        CompanionRestorationDefinition.INSTANCE
                ),
                scopes(
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
                worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE
                )
        );
    }

    private static PersistenceFeatureDescriptor coop() {
        return descriptor(
                COOP,
                PersistenceFeatureDomain.COOP,
                Set.of("coop_slot", "coop_residency"),
                List.of(
                        CoopSlotRegistrationDefinition.INSTANCE,
                        CompanionCoopCaptureDefinition.INSTANCE,
                        CompanionCoopReleaseDefinition.INSTANCE
                ),
                scopes(
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
                worldReadiness(),
                Set.of(
                        OperationScopeType.OPERATION,
                        OperationScopeType.PROFILE,
                        OperationScopeType.COOP
                )
        );
    }

    private static PersistenceFeatureDescriptor extension() {
        return descriptor(
                EXTENSION,
                PersistenceFeatureDomain.EXTENSION,
                Set.of("profile_extension_data"),
                List.of(ProfileExtensionMutationDefinition.INSTANCE),
                scopes(
                        ProfileExtensionMutationDefinition.INSTANCE,
                        Set.of(OperationScopeType.PROFILE)
                ),
                Set.of(IDENTITY),
                Set.of(),
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

    private static Set<PersistenceStartupNode> worldReadiness() {
        return Set.of(
                PersistenceStartupNode.RECOVER_OPERATIONS,
                PersistenceStartupNode.BUILD_PROJECTIONS,
                PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                PersistenceStartupNode.RECONCILE_WORLD
        );
    }

    private static PersistenceFeatureDescriptor descriptor(
            PersistenceFeatureId id,
            PersistenceFeatureDomain domain,
            Set<String> authorities,
            List<OperationDefinition<?>> definitions,
            Map<OperationKind, Set<OperationScopeType>> scopes,
            Set<PersistenceFeatureId> dependencies,
            Set<ProjectionConsumerId> consumers,
            Set<PersistenceStartupNode> readiness,
            Set<OperationScopeType> quarantine
    ) {
        return new PersistenceFeatureDescriptor(
                id,
                domain,
                authorities,
                definitions,
                scopes,
                dependencies,
                hook(id, "loader"),
                consumers,
                hook(id, "recovery"),
                readiness,
                PersistenceCircuitPolicy.BOUNDED_SCOPE,
                quarantine,
                hook(id, "shutdown"),
                "persistence." + id
        );
    }

    private static PersistenceFeatureHookId hook(
            PersistenceFeatureId id,
            String kind
    ) {
        return new PersistenceFeatureHookId(id + "." + kind);
    }

    private static Map<OperationKind, Set<OperationScopeType>> scopes(
            Object... pairs
    ) {
        java.util.HashMap<OperationKind, Set<OperationScopeType>> result =
                new java.util.HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            OperationDefinition<?> definition =
                    (OperationDefinition<?>) pairs[index];
            @SuppressWarnings("unchecked")
            Set<OperationScopeType> value =
                    (Set<OperationScopeType>) pairs[index + 1];
            result.put(definition.kind(), value);
        }
        return Map.copyOf(result);
    }
}
