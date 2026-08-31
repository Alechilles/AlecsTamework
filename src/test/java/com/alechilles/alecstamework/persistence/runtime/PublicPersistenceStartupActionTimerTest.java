package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicPersistenceStartupActionTimerTest {

    @Test
    void exceptionalStartupActionEmitsFailureSignal() {
        ArrayList<PersistenceFailureSignal> signals = new ArrayList<>();
        PublicPersistenceControlPlane control = new PublicPersistenceControlPlane(
                PublicPersistenceFeatureRegistry.create(),
                signals::add
        );
        Map<PersistenceStartupNode, PersistenceStartupAction> actions =
                PublicPersistenceStartupActionTimer.wrap(
                        Map.of(
                                PersistenceStartupNode.OPEN_TARGET,
                                () -> CompletableFuture.failedFuture(
                                        new IllegalStateException("open failed")
                                )
                        ),
                        control
                );

        assertThrows(
                CompletionException.class,
                () -> actions.get(PersistenceStartupNode.OPEN_TARGET)
                        .execute().toCompletableFuture().join()
        );

        assertEquals(1, signals.size());
        assertEquals("persistence_startup_failed", signals.getFirst().eventName());
        assertEquals("OPEN_TARGET", signals.getFirst().operation());
    }
}
