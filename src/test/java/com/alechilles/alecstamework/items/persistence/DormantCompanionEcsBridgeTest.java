package com.alechilles.alecstamework.items.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.hypixel.hytale.component.RemoveReason;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DormantCompanionEcsBridgeTest {
    @Test
    void duplicateCallbackSharesOneInFlightStableObservation() {
        DormantCompanionObservation observation = lostObservation();
        CompletableFuture<CompanionLifecycleAuthorResult> first =
                new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        List<DormantCompanionEcsBridge.Completion> completions =
                new ArrayList<>();
        DormantCompanionEcsBridge bridge = new DormantCompanionEcsBridge(
                new FakeObservationFactory(observation),
                intent -> {
                    calls.incrementAndGet();
                    return first;
                },
                completions::add
        );

        assertTrue(bridge.onRemoval(null, RemoveReason.REMOVE, null));
        assertFalse(bridge.onRemoval(null, RemoveReason.REMOVE, null));
        assertEquals(1, calls.get());

        first.complete(published());

        assertEquals(1, completions.size());
        assertEquals(
                observation.observationKey(),
                completions.getFirst().observationKey()
        );
        assertTrue(completions.getFirst().result().published());
    }

    @Test
    void completedObservationCanBeResubmittedForDurableIdempotency() {
        DormantCompanionObservation observation = lostObservation();
        AtomicInteger calls = new AtomicInteger();
        DormantCompanionEcsBridge bridge = new DormantCompanionEcsBridge(
                new FakeObservationFactory(observation),
                intent -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(published());
                },
                ignored -> {
                }
        );

        assertTrue(bridge.onRemoval(null, RemoveReason.REMOVE, null));
        assertTrue(bridge.onRemoval(null, RemoveReason.REMOVE, null));
        assertEquals(2, calls.get());
    }

    @Test
    void productionStyleCompletionIsDispatchedAfterEvidenceIsFrozen() {
        DormantCompanionObservation observation = lostObservation();
        List<Runnable> queuedCompletions = new ArrayList<>();
        List<DormantCompanionEcsBridge.Completion> completions =
                new ArrayList<>();
        DormantCompanionEcsBridge bridge = new DormantCompanionEcsBridge(
                new FakeObservationFactory(observation),
                intent -> CompletableFuture.completedFuture(published()),
                completions::add,
                queuedCompletions::add
        );

        assertTrue(bridge.onRemoval(null, RemoveReason.REMOVE, null));
        assertTrue(completions.isEmpty());
        assertEquals(1, queuedCompletions.size());

        queuedCompletions.getFirst().run();

        assertEquals(1, completions.size());
        assertEquals(
                observation.observationKey(),
                completions.getFirst().observationKey()
        );
        assertTrue(completions.getFirst().result().published());
    }

    private CompanionLifecycleAuthorResult published() {
        return new CompanionLifecycleAuthorResult(
                CompanionLifecycleAuthorResult.Kind.DORMANT,
                CompanionLifecycleAuthorResult.Status.PUBLISHED,
                null,
                null,
                null,
                null
        );
    }

    private DormantCompanionObservation lostObservation() {
        UUID id = UUID.fromString("4d450295-290e-42e2-a3cf-a15e58b0d48e");
        return new DormantCompanionObservation(
                "stable-observation",
                new ProfileId(id),
                new NpcAlias(id),
                "world",
                DormantCompanionObservation.Evidence.DESTRUCTIVE_REMOVAL,
                "stable-receipt",
                123L,
                null,
                null,
                new DormantCompanionObservation.LostObservation(0L, 0)
        );
    }

    private record FakeObservationFactory(
            DormantCompanionObservation observation
    ) implements DormantCompanionEcsBridge.ObservationFactory {
        @Override
        public DormantCompanionEcsBridge.FrozenObservation death(
                com.hypixel.hytale.component.Ref<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> reference,
                com.hypixel.hytale.server.core.modules.entity.damage
                        .DeathComponent death,
                com.hypixel.hytale.component.Store<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> store
        ) {
            return null;
        }

        @Override
        public DormantCompanionEcsBridge.FrozenObservation removal(
                com.hypixel.hytale.component.Ref<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> reference,
                RemoveReason reason,
                com.hypixel.hytale.component.Store<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> store
        ) {
            return new DormantCompanionEcsBridge.FrozenObservation(
                    observation, "Test_Role"
            );
        }

        @Override
        public DormantCompanionEcsBridge.FrozenObservation worldDeletion(
                com.hypixel.hytale.component.Ref<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> reference,
                com.hypixel.hytale.component.Store<
                        com.hypixel.hytale.server.core.universe.world.storage
                                .EntityStore> store
        ) {
            return null;
        }
    }
}
