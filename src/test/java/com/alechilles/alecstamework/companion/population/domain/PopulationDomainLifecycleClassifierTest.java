package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects the managed-domain owned/deployable lifecycle contract. */
class PopulationDomainLifecycleClassifierTest {
    @Test
    void classifiesEveryDurableLifecycleState() {
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, true),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.ACTIVE)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, true),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.UNLOADED)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, true),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.COOP)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, true),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.LOST)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, true),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.UNRESOLVED)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, false),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.CAPTURED)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, false),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.ROSTER_STORED)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, false),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.PROVISIONED_DORMANT)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(true, false),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.DEAD_REVIVABLE)
        );
        assertEquals(
                new PopulationDomainLifecycleClassifier.Classification(false, false),
                PopulationDomainLifecycleClassifier.classify(LifecycleState.RELEASED)
        );
    }
}
