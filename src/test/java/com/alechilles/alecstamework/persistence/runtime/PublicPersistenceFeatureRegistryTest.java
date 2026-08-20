package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.coop.CompanionCoopCaptureDefinition;
import com.alechilles.alecstamework.companion.revival.PaidRevivalDefinition;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
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
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER
                ),
                capture.operationScopes().get(
                        CompanionCaptureDefinition.INSTANCE.kind()
                ).required()
        );
        assertEquals(
                Set.of(OperationScopeType.COMMAND_FAMILY),
                capture.operationScopes().get(
                        CompanionCaptureDefinition.INSTANCE.kind()
                ).optional()
        );
        assertEquals(
                Set.of(OperationScopeType.PROFILE),
                capture.operationScopes().get(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind()
                ).required()
        );
        assertEquals(
                Set.of(OperationScopeType.OWNER),
                capture.operationScopes().get(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind()
                ).optional()
        );

        PersistenceFeatureDescriptor paidRevival =
                registry.requireFeature(
                        PublicPersistenceFeatureRegistry.PAID_REVIVAL
                );
        assertTrue(paidRevival.ownedAuthorities().isEmpty());
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.OWNER,
                        OperationScopeType.COMMAND_FAMILY
                ),
                paidRevival.operationScopes().get(
                        PaidRevivalDefinition.INSTANCE.kind()
                ).required()
        );
        assertTrue(paidRevival.operationScopes().get(
                PaidRevivalDefinition.INSTANCE.kind()
        ).optional().isEmpty());
        assertTrue(paidRevival.startupDependencies().containsAll(
                Set.of(
                        PublicPersistenceFeatureRegistry
                                .POPULATION_GROUPS,
                        PublicPersistenceFeatureRegistry.COMMAND_ROSTER,
                        PublicPersistenceFeatureRegistry.TIMED_SUMMON,
                        PublicPersistenceFeatureRegistry
                                .ECONOMIC_COMPENSATION
                )
        ));
        assertTrue(registry.requireFeature(
                PublicPersistenceFeatureRegistry.COMMAND_ROSTER
        ).projectionConsumers().contains(
                PublicPersistenceFeatureRegistry
                        .PUBLIC_FEATURE_EVENT_OBSERVER
        ));
        assertTrue(registry.requireFeature(
                PublicPersistenceFeatureRegistry.TIMED_SUMMON
        ).projectionConsumers().contains(
                PublicPersistenceFeatureRegistry
                        .PUBLIC_FEATURE_EVENT_OBSERVER
        ));
        assertTrue(registry.requireFeature(
                PublicPersistenceFeatureRegistry.DORMANT
        ).projectionConsumers().contains(
                PublicPersistenceFeatureRegistry
                        .PUBLIC_FEATURE_EVENT_OBSERVER
        ));
        assertTrue(registry.requireFeature(
                PublicPersistenceFeatureRegistry.PROVISIONING
        ).projectionConsumers().contains(
                PublicPersistenceFeatureRegistry
                        .PUBLIC_FEATURE_EVENT_OBSERVER
        ));

        PersistenceFeatureDescriptor coop = registry.requireFeature(
                PublicPersistenceFeatureRegistry.COOP
        );
        assertEquals(
                Set.of(
                        OperationScopeType.PROFILE,
                        OperationScopeType.COOP
                ),
                coop.operationScopes().get(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                ).required()
        );
        assertEquals(
                Set.of(OperationScopeType.OWNER),
                coop.operationScopes().get(
                        CompanionCoopCaptureDefinition.INSTANCE.kind()
                ).optional()
        );
    }
}
