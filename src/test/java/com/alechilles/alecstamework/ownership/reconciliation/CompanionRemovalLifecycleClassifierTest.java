package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.hypixel.hytale.component.RemoveReason;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionRemovalLifecycleClassifierTest {
    @Test
    void unloadAlwaysRetainsPhysicalOccupancyClassification() {
        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                ignored -> true,
                ignored -> true,
                ignored -> true,
                ignored -> true
        );

        assertEquals(CompanionLifecycleState.UNLOADED, classifier.classify(
                UUID.randomUUID(), RemoveReason.UNLOAD, CompanionLifecycleState.ACTIVE
        ));
    }

    @Test
    void deliberateRemovalUsesDurableLifecycleRegistriesAndPreservesKnownDormancy() {
        UUID captured = UUID.randomUUID();
        UUID cooped = UUID.randomUUID();
        UUID dead = UUID.randomUUID();
        UUID lost = UUID.randomUUID();
        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                captured::equals,
                cooped::equals,
                dead::equals,
                lost::equals
        );

        assertEquals(CompanionLifecycleState.CAPTURED,
                classifier.classify(captured, RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE));
        assertEquals(CompanionLifecycleState.COOP,
                classifier.classify(cooped, RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE));
        assertEquals(CompanionLifecycleState.DEAD_REVIVABLE,
                classifier.classify(dead, RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE));
        assertEquals(CompanionLifecycleState.LOST,
                classifier.classify(lost, RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE));
        assertEquals(CompanionLifecycleState.RELEASED,
                classifier.classify(UUID.randomUUID(), RemoveReason.REMOVE, CompanionLifecycleState.RELEASED));
        assertEquals(CompanionLifecycleState.UNKNOWN_DORMANT,
                classifier.classify(UUID.randomUUID(), RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE));
    }

    @Test
    void confirmedUnlinkedDeathPermanentlyReleasesOwnerCapacity() {
        UUID npcUuid = UUID.randomUUID();
        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> false
        );

        assertEquals(CompanionLifecycleState.RELEASED, classifier.classify(
                npcUuid,
                RemoveReason.REMOVE,
                CompanionLifecycleState.ACTIVE,
                true
        ));
    }

    @Test
    void durableDeathSnapshotWinsOverPermanentDeathFallback() {
        UUID npcUuid = UUID.randomUUID();
        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                ignored -> false,
                ignored -> false,
                npcUuid::equals,
                ignored -> false
        );

        assertEquals(CompanionLifecycleState.DEAD_REVIVABLE, classifier.classify(
                npcUuid,
                RemoveReason.REMOVE,
                CompanionLifecycleState.ACTIVE,
                true
        ));
    }

    @Test
    void configuredNonRevivableDeathReleasesEvenWhenCommandLinksExisted() {
        UUID npcUuid = UUID.randomUUID();
        CompanionRemovalLifecycleClassifier classifier = new CompanionRemovalLifecycleClassifier(
                ignored -> false,
                ignored -> false,
                ignored -> false,
                ignored -> false,
                npcUuid::equals
        );

        assertEquals(CompanionLifecycleState.RELEASED, classifier.classify(
                npcUuid, RemoveReason.REMOVE, CompanionLifecycleState.ACTIVE, false
        ));
    }
}
