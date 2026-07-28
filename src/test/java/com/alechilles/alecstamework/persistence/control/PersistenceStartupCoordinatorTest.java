package com.alechilles.alecstamework.persistence.control;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.capture.CompanionCaptureReleaseDefinition;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceFeatureRegistry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Startup, readiness, quarantine, failure, and shutdown gates for one control plane. */
class PersistenceStartupCoordinatorTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId OTHER_PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final OwnerId OWNER =
            OwnerId.parse("10000000-0000-0000-0000-000000000001");

    @Test
    void oneGraphPublishesMutationReadinessInDependencyOrder() {
        ArrayList<PersistenceStartupNode> execution = new ArrayList<>();
        PersistenceStartupCoordinator coordinator = coordinator(
                actions(node -> {
                    execution.add(node);
                    return completed(PersistenceStartupAction.Result.COMPLETE);
                })
        );

        PersistenceStartupReport report =
                coordinator.advance().toCompletableFuture().join();

        assertTrue(report.complete());
        assertEquals(List.of(PersistenceStartupNode.values()), execution);
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                coordinator.readiness(PublicPersistenceFeatureRegistry.CAPTURE)
        );
        coordinator.requireAdmission(
                CompanionCaptureDefinition.INSTANCE.kind(),
                "companion_capture",
                captureScopes(PROFILE)
        );
    }

    @Test
    void deferredWorldEvidenceBlocksOnlyWorldDependentReadinessUntilResumed() {
        AtomicInteger worldAttempts = new AtomicInteger();
        PersistenceStartupCoordinator coordinator = coordinator(
                actions(node -> completed(
                        node == PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                                && worldAttempts.getAndIncrement() == 0
                                ? PersistenceStartupAction.Result.DEFERRED
                                : PersistenceStartupAction.Result.COMPLETE
                ))
        );

        PersistenceStartupReport deferred =
                coordinator.advance().toCompletableFuture().join();

        assertEquals(
                PersistenceStartupNode.WAIT_WORLD_EVIDENCE,
                deferred.deferredNode()
        );
        assertEquals(
                PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING,
                coordinator.readiness(PublicPersistenceFeatureRegistry.CAPTURE)
        );
        assertEquals(
                PersistenceReadinessLevel.PROJECTION_READY,
                coordinator.readiness(PublicPersistenceFeatureRegistry.IDENTITY)
        );
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureDefinition.INSTANCE.kind(),
                        "companion_capture",
                        captureScopes(PROFILE)
                )
        );

        assertTrue(coordinator.advance().toCompletableFuture().join().complete());
        assertEquals(2, worldAttempts.get());
    }

    @Test
    void eachStartupNodeFailureFailsClosedWithoutRunningLaterNodes() {
        for (PersistenceStartupNode failedNode
                : PersistenceStartupNode.values()) {
            ArrayList<PersistenceStartupNode> execution = new ArrayList<>();
            PersistenceStartupCoordinator coordinator = coordinator(
                    actions(node -> {
                        execution.add(node);
                        if (node == failedNode) {
                            return CompletableFuture.failedFuture(
                                    new IllegalStateException("injected")
                            );
                        }
                        return completed(
                                PersistenceStartupAction.Result.COMPLETE
                        );
                    })
            );

            PersistenceStartupReport report =
                    coordinator.advance().toCompletableFuture().join();

            assertEquals(failedNode, report.failedNode());
            assertEquals(
                    PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                    report.readiness()
            );
            assertEquals(failedNode.ordinal() + 1, execution.size());
            assertSame(
                    report.readiness(),
                    coordinator.advance().toCompletableFuture().join().readiness()
            );
        }
    }

    @Test
    void exactScopePolicyAndQuarantineCannotLeakAcrossProfiles() {
        PersistenceStartupCoordinator coordinator = readyCoordinator();
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureDefinition.INSTANCE.kind(),
                        "companion_capture",
                        List.of(OperationScope.profile(PROFILE))
                )
        );

        coordinator.quarantine(
                OperationScope.profile(PROFILE),
                "unknown_commit"
        );
        IllegalStateException rejected = assertThrows(
                IllegalStateException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureDefinition.INSTANCE.kind(),
                        "companion_capture",
                        captureScopes(PROFILE)
                )
        );
        assertTrue(rejected.getMessage().endsWith(":quarantined"));
        coordinator.requireAdmission(
                CompanionCaptureDefinition.INSTANCE.kind(),
                "companion_capture",
                captureScopes(OTHER_PROFILE)
        );

        coordinator.releaseQuarantine(OperationScope.profile(PROFILE));
        coordinator.requireAdmission(
                CompanionCaptureDefinition.INSTANCE.kind(),
                "companion_capture",
                captureScopes(PROFILE)
        );
    }

    @Test
    void captureReleaseAdmitsOptionalOwnerAssignmentWithoutWeakeningPolicy() {
        PersistenceStartupCoordinator coordinator = readyCoordinator();

        coordinator.requireAdmission(
                CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                "companion_capture",
                List.of(OperationScope.profile(PROFILE))
        );
        coordinator.requireAdmission(
                CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                "companion_capture",
                captureScopes(PROFILE)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                        "companion_capture",
                        List.of(OperationScope.owner(OWNER))
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                        "companion_capture",
                        List.of(
                                OperationScope.profile(PROFILE),
                                OperationScope.coop("unexpected")
                        )
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureDefinition.INSTANCE.kind(),
                        "companion_capture",
                        List.of(OperationScope.profile(PROFILE))
                )
        );

        coordinator.quarantine(OperationScope.owner(OWNER), "owner_review");
        assertThrows(
                IllegalStateException.class,
                () -> coordinator.requireAdmission(
                        CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                        "companion_capture",
                        captureScopes(PROFILE)
                )
        );
        coordinator.requireAdmission(
                CompanionCaptureReleaseDefinition.INSTANCE.kind(),
                "companion_capture",
                List.of(OperationScope.profile(PROFILE))
        );
    }

    @Test
    void boundedCircuitBlocksItsFeatureAndDependentsOnly() {
        PersistenceStartupCoordinator coordinator = readyCoordinator();
        java.util.HashMap<PersistenceFeatureId, PersistenceFeatureCircuit>
                circuits = new java.util.HashMap<>(
                coordinator.featureCircuits()
        );
        circuits.put(
                PublicPersistenceFeatureRegistry.ECONOMIC_COMPENSATION,
                openCircuit(
                        PublicPersistenceFeatureRegistry
                                .ECONOMIC_COMPENSATION
                )
        );

        coordinator.installFeatureCircuits(circuits);

        assertEquals(
                PersistenceReadinessLevel.QUARANTINED,
                coordinator.readiness(
                        PublicPersistenceFeatureRegistry.CAPTURE
                )
        );
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                coordinator.readiness(
                        PublicPersistenceFeatureRegistry.IDENTITY
                )
        );
    }

    @Test
    void openCoreCircuitMovesTheWholeRuntimeToGlobalReadOnly() {
        PersistenceStartupCoordinator coordinator = readyCoordinator();
        java.util.HashMap<PersistenceFeatureId, PersistenceFeatureCircuit>
                circuits = new java.util.HashMap<>(
                coordinator.featureCircuits()
        );
        circuits.put(
                PublicPersistenceFeatureRegistry.IDENTITY,
                openCircuit(PublicPersistenceFeatureRegistry.IDENTITY)
        );

        coordinator.installFeatureCircuits(circuits);

        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                coordinator.report().readiness()
        );
        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                coordinator.readiness(
                        PublicPersistenceFeatureRegistry.CAPTURE
                )
        );
    }

    @Test
    void closeWinsAgainstAnInFlightStartupCallbackAndPermanentlyClosesAdmission() {
        CompletableFuture<PersistenceStartupAction.Result> open =
                new CompletableFuture<>();
        Map<PersistenceStartupNode, PersistenceStartupAction> actions =
                actions(node -> node == PersistenceStartupNode.OPEN_TARGET
                        ? open
                        : completed(PersistenceStartupAction.Result.COMPLETE));
        PersistenceStartupCoordinator coordinator = coordinator(actions);

        CompletableFuture<PersistenceStartupReport> advancing =
                coordinator.advance().toCompletableFuture();
        assertFalse(advancing.isDone());
        coordinator.close();

        assertEquals(
                PersistenceReadinessLevel.CLOSED,
                advancing.join().readiness()
        );
        open.complete(PersistenceStartupAction.Result.COMPLETE);
        assertEquals(
                PersistenceReadinessLevel.CLOSED,
                coordinator.report().readiness()
        );
        assertNull(coordinator.report().runningNode());
    }

    private PersistenceStartupCoordinator readyCoordinator() {
        PersistenceStartupCoordinator coordinator = coordinator(
                actions(node -> completed(
                        PersistenceStartupAction.Result.COMPLETE
                ))
        );
        assertTrue(coordinator.advance().toCompletableFuture().join().complete());
        return coordinator;
    }

    private PersistenceStartupCoordinator coordinator(
            Map<PersistenceStartupNode, PersistenceStartupAction> actions
    ) {
        return new PersistenceStartupCoordinator(
                PublicPersistenceFeatureRegistry.create(),
                actions
        );
    }

    private Map<PersistenceStartupNode, PersistenceStartupAction> actions(
            java.util.function.Function<
                    PersistenceStartupNode,
                    java.util.concurrent.CompletionStage<
                            PersistenceStartupAction.Result
                            >
                    > factory
    ) {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node
                : PersistenceStartupNode.values()) {
            actions.put(node, () -> factory.apply(node));
        }
        return Map.copyOf(actions);
    }

    private CompletableFuture<PersistenceStartupAction.Result> completed(
            PersistenceStartupAction.Result result
    ) {
        return CompletableFuture.completedFuture(result);
    }

    private List<OperationScope> captureScopes(ProfileId profileId) {
        return List.of(
                OperationScope.profile(profileId),
                OperationScope.owner(OWNER)
        );
    }

    private PersistenceFeatureCircuit openCircuit(
            PersistenceFeatureId featureId
    ) {
        return new PersistenceFeatureCircuit(
                featureId,
                PersistenceFeatureCircuitState.OPEN,
                1,
                "injected_failure",
                -100L,
                -100
        );
    }
}
