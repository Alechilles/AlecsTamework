package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
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
        assertEquals(11, registry.descriptors().size());
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
        assertEquals(17, operationKinds.size());

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
    }
}
