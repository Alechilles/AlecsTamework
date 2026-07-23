package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import java.util.HashSet;
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
        assertEquals(7, registry.descriptors().size());
        assertEquals(
                PublicPersistenceFeatureRegistry.IDENTITY,
                registry.descriptors().getFirst().featureId()
        );
        HashSet<String> operationKinds = new HashSet<>();
        for (PersistenceFeatureDescriptor descriptor
                : registry.descriptors()) {
            assertFalse(descriptor.ownedAuthorities().isEmpty());
            assertFalse(descriptor.readinessEvidence().isEmpty());
            assertFalse(descriptor.quarantineGranularity().isEmpty());
            assertFalse(descriptor.metricsNamespace().isBlank());
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
        assertEquals(9, operationKinds.size());
    }
}
