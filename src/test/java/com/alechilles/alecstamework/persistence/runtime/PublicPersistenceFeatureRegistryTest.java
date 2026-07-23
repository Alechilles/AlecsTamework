package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDefinition;
import com.alechilles.alecstamework.companion.command.CommandRosterTransitionDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonLeaseMutationDefinition;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionDefinition;
import com.alechilles.alecstamework.companion.provisioning.CompanionProvisioningDefinition;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.operation.OperationScopeType;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Completeness and dependency gates for every public persistence feature. */
class PublicPersistenceFeatureRegistryTest {
    @Test
    void registryOwnsEveryPublicOperationAndCrossCuttingHookExactlyOnce() {
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        assertEquals(12, registry.descriptors().size());
        assertEquals(
                PublicPersistenceFeatureRegistry.IDENTITY,
                registry.descriptors().getFirst().featureId()
        );
        HashSet<String> operationKinds = new HashSet<>();
        HashSet<String> authorities = new HashSet<>();
        for (PersistenceFeatureDescriptor descriptor
                : registry.descriptors()) {
            assertFalse(descriptor.readinessEvidence().isEmpty());
            assertFalse(descriptor.quarantineGranularity().isEmpty());
            assertFalse(descriptor.metricsNamespace().isBlank());
            descriptor.ownedAuthorities().forEach(
                    authority -> assertTrue(authorities.add(authority))
            );
            descriptor.operationDefinitions().forEach(definition -> {
                assertTrue(operationKinds.add(
                        definition.kind().value()
                ));
                assertSame(
                        descriptor,
                        registry.requireOperation(definition.kind())
                );
                assertTrue(descriptor.operationScopes()
                        .containsKey(definition.kind()));
            });
        }
        assertEquals(18, operationKinds.size());

        PersistenceFeatureDescriptor economics = registry.requireFeature(
                PublicPersistenceFeatureRegistry.ECONOMIC_COMPENSATION
        );
        assertEquals(
                Set.of("refund_claim", "refund_claim_item"),
                economics.ownedAuthorities()
        );
        PersistenceFeatureDescriptor capture = registry.requireFeature(
                PublicPersistenceFeatureRegistry.CAPTURE
        );
        assertTrue(capture.ownedAuthorities().isEmpty());
        assertTrue(capture.startupDependencies().contains(
                PublicPersistenceFeatureRegistry.ECONOMIC_COMPENSATION
        ));

        PersistenceFeatureDescriptor groups = registry.requireFeature(
                PublicPersistenceFeatureRegistry.POPULATION_GROUPS
        );
        assertEquals(
                Set.of(
                        PublicPersistenceFeatureRegistry.IDENTITY,
                        PublicPersistenceFeatureRegistry.LIFECYCLE,
                        PublicPersistenceFeatureRegistry.OWNER_POPULATION
                ),
                groups.startupDependencies()
        );
        assertEquals(
                Set.of(
                        PersistenceStartupNode.LOAD_FEATURE_DETAIL,
                        PersistenceStartupNode.RECOVER_OPERATIONS,
                        PersistenceStartupNode.BUILD_PROJECTIONS
                ),
                groups.readinessEvidence()
        );
        assertEquals(
                Set.of(PublicPersistenceFeatureRegistry.POPULATION_GROUP_INDEX),
                groups.projectionConsumers()
        );

        PersistenceFeatureDescriptor command = registry.requireFeature(
                PublicPersistenceFeatureRegistry.COMMAND_ROSTER
        );
        assertEquals(
                Set.of(
                        "command_family",
                        "command_roster_membership"
                ),
                command.ownedAuthorities()
        );
        assertEquals(
                Set.of(
                        PublicPersistenceFeatureRegistry.IDENTITY,
                        PublicPersistenceFeatureRegistry.LIFECYCLE,
                        PublicPersistenceFeatureRegistry.OWNER_POPULATION,
                        PublicPersistenceFeatureRegistry.POPULATION_GROUPS
                ),
                command.startupDependencies()
        );
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                command.operationScopes().get(
                        CommandRosterMembershipDefinition.INSTANCE.kind()
                )
        );
        assertEquals(
                command.operationScopes().get(
                        CommandRosterMembershipDefinition.INSTANCE.kind()
                ),
                command.operationScopes().get(
                        CommandRosterTransitionDefinition.INSTANCE.kind()
                )
        );
        assertTrue(command.projectionConsumers().contains(
                PublicPersistenceFeatureRegistry.COMMAND_ROSTER_INDEX
        ));

        PersistenceFeatureDescriptor timed = registry.requireFeature(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON
        );
        assertEquals(Set.of("timed_summon_lease"),
                timed.ownedAuthorities());
        assertEquals(
                Set.of(OperationScopeType.PROFILE),
                timed.operationScopes().get(
                        TimedSummonLeaseMutationDefinition.INSTANCE.kind()
                )
        );
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                timed.operationScopes().get(
                        TimedSummonTransitionDefinition.INSTANCE.kind()
                )
        );
        assertEquals(
                Set.of(PublicPersistenceFeatureRegistry.TIMED_SUMMON_INDEX),
                timed.projectionConsumers()
        );

        PersistenceFeatureDescriptor provisioning =
                registry.requireFeature(
                        PublicPersistenceFeatureRegistry.PROVISIONING
                );
        assertEquals(
                Set.of("provisioning_record"),
                provisioning.ownedAuthorities()
        );
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                provisioning.operationScopes().get(
                        CompanionProvisioningDefinition.INSTANCE.kind()
                )
        );
        assertEquals(
                provisioning.operationScopes().get(
                        CompanionProvisioningDefinition.INSTANCE.kind()
                ),
                provisioning.operationScopes().get(
                        ProvisioningActivationDefinition.INSTANCE.kind()
                )
        );
        assertTrue(provisioning.startupDependencies().contains(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON
        ));
        assertTrue(provisioning.projectionConsumers().contains(
                PublicPersistenceFeatureRegistry.PROVISIONING_INDEX
        ));
    }
}
