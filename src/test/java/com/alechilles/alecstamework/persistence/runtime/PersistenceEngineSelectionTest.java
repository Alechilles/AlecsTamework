package com.alechilles.alecstamework.persistence.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Process-level engine selection and lazy-construction contracts. */
class PersistenceEngineSelectionTest {
    @Test
    void releaseCandidateDefaultSelectsOnlyNext() {
        PersistenceEngineSelection selection =
                PersistenceEngineSelection.resolve(key -> null);
        AtomicInteger next = new AtomicInteger();
        AtomicInteger legacy = new AtomicInteger();

        String constructed = PersistenceEngineSelector.construct(
                selection,
                () -> {
                    next.incrementAndGet();
                    return "next";
                },
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                }
        );

        assertEquals(PersistenceEngineMode.NEXT, selection.mode());
        assertEquals(
                PersistenceEngineSelection.Source.RELEASE_DEFAULT,
                selection.source()
        );
        assertEquals("next", constructed);
        assertEquals(1, next.get());
        assertEquals(0, legacy.get());
    }

    @Test
    void explicitNextStillConstructsNoLegacyState() {
        Map<String, String> values = Map.of(
                PersistenceEngineSelection.ENGINE_PROPERTY,
                " NEXT "
        );
        AtomicInteger legacy = new AtomicInteger();

        String constructed = PersistenceEngineSelector.construct(
                PersistenceEngineSelection.resolve(values::get),
                () -> "next",
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                }
        );

        assertEquals("next", constructed);
        assertEquals(0, legacy.get());
    }

    @Test
    void legacyRequiresExplicitUnsupportedDevelopmentAcknowledgement() {
        Map<String, String> values = Map.of(
                PersistenceEngineSelection.ENGINE_PROPERTY,
                "legacy"
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineSelection.resolve(values::get)
        );

        assertEquals(
                "legacy_persistence_requires_unsupported_development_ack",
                failure.getMessage()
        );
    }

    @Test
    void acknowledgedLegacyConstructsOnlyLegacy() {
        Map<String, String> values = new HashMap<>();
        values.put(
                PersistenceEngineSelection.ENGINE_PROPERTY,
                "legacy"
        );
        values.put(
                PersistenceEngineSelection.ALLOW_LEGACY_PROPERTY,
                "true"
        );
        AtomicInteger next = new AtomicInteger();
        AtomicInteger legacy = new AtomicInteger();

        String constructed = PersistenceEngineSelector.construct(
                PersistenceEngineSelection.resolve(values::get),
                () -> {
                    next.incrementAndGet();
                    return "next";
                },
                () -> {
                    legacy.incrementAndGet();
                    return "legacy";
                }
        );

        assertEquals("legacy", constructed);
        assertEquals(0, next.get());
        assertEquals(1, legacy.get());
    }

    @Test
    void unknownEngineNeverFallsBack() {
        Map<String, String> values = Map.of(
                PersistenceEngineSelection.ENGINE_PROPERTY,
                "maybe"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> PersistenceEngineSelection.resolve(values::get)
        );
    }
}
