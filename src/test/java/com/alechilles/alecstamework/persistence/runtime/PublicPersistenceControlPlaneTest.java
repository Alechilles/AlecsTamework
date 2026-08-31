package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.capture.CompanionCaptureDefinition;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteFailureClassifier;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureDescriptor;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureRegistry;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupAction;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceWriteRejection;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for descriptor-derived metrics and failure containment. */
class PublicPersistenceControlPlaneTest {
    private static final PersistenceReadKind TEST_READ =
            new PersistenceReadKind("control_plane_test");
    private static final ProfileId PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final ProfileId OTHER_PROFILE = ProfileId.parse(
            "10000000-0000-0000-0000-000000000002"
    );
    private static final OwnerId OWNER = OwnerId.parse(
            "20000000-0000-0000-0000-000000000001"
    );

    @Test
    void emitsSignalsOnlyForTerminalPersistenceFailures() {
        PersistenceFeatureRegistry registry = PublicPersistenceFeatureRegistry.create();
        ArrayList<PersistenceFailureSignal> signals = new ArrayList<>();
        PublicPersistenceControlPlane control =
                new PublicPersistenceControlPlane(registry, signals::add);
        control.bind(ready(registry));
        OperationId operationId = OperationId.create();

        control.writeCompleted(
                operationId,
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.Committed<>("done")
        );
        assertTrue(signals.isEmpty());

        control.writeCompleted(
                operationId,
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.RolledBack<>(failure(
                        StorageFailureKind.IO,
                        "sqlite_write_failed",
                        false
                ))
        );
        control.readCompleted(
                TEST_READ,
                PersistenceReadResult.failed(failure(
                        StorageFailureKind.CORRUPT,
                        "sqlite_read_failed",
                        false
                ))
        );
        control.checkpointFailure("shutdown", new IllegalStateException("checkpoint"));
        control.shutdownTimedOut(2);

        assertEquals(4, signals.size());
        assertEquals("persistence_write_failed", signals.get(0).eventName());
        assertEquals(operationId.toString(), signals.get(0).incidentKey());
        assertEquals("persistence_read_failed", signals.get(1).eventName());
        assertEquals("persistence_checkpoint_failed", signals.get(2).eventName());
        assertEquals("persistence_shutdown_timeout", signals.get(3).eventName());
    }

    @Test
    void derivesEveryFeatureMetricAndKeepsBoundedFailuresBounded() {
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        PersistenceStartupCoordinator startup = ready(registry);
        PublicPersistenceControlPlane control =
                new PublicPersistenceControlPlane(registry);
        control.bind(startup);

        PublicPersistenceMetricsSnapshot initial = control.snapshot();
        assertEquals(registry.descriptors().size(), initial.features().size());
        for (PersistenceFeatureDescriptor descriptor
                : registry.descriptors()) {
            assertEquals(
                    descriptor.metricsNamespace(),
                    initial.features().get(descriptor.featureId()).namespace()
            );
        }

        OperationId operationId = OperationId.create();
        control.writeAccepted(
                operationId, CompanionCaptureDefinition.KIND
        );
        control.writeRejected(
                operationId,
                CompanionCaptureDefinition.KIND,
                PersistenceWriteRejection.SATURATED
        );
        control.busyRetry(
                operationId, CompanionCaptureDefinition.KIND, 1
        );
        control.unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.Committed<>("done")
        );
        control.unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.RolledBack<>(
                        failure(
                                StorageFailureKind.BUSY,
                                "sqlite_busy",
                                true
                        )
                )
        );
        control.unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.RolledBack<>(
                        failure(
                                StorageFailureKind.UNKNOWN,
                                "unknown_commit_proven_absent",
                                false
                        )
                )
        );
        control.readCompleted(
                TEST_READ,
                PersistenceReadResult.failed(failure(
                        StorageFailureKind.BUSY,
                        "sqlite_read_busy",
                        true
                ))
        );
        control.contained(
                List.of(OperationScope.profile(PROFILE)),
                "capture_scope_unknown"
        );

        PublicPersistenceMetricsSnapshot metrics = control.snapshot();
        PublicPersistenceMetricsSnapshot.FeatureMetrics capture =
                metrics.features().get(
                        PublicPersistenceFeatureRegistry.CAPTURE
                );
        assertEquals(1, capture.writesAccepted());
        assertEquals(1, capture.writesRejected());
        assertEquals(1, capture.busyRetries());
        assertEquals(3, capture.unitsCompleted());
        assertEquals(2, capture.unitsFailed());
        assertEquals(1, metrics.readsCompleted());
        assertEquals(1, metrics.readsFailed());
        assertNull(metrics.lastGlobalFailureCode());
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                startup.report().readiness()
        );
        assertThrows(
                IllegalStateException.class,
                () -> control.requireAdmission(
                        CompanionCaptureDefinition.KIND,
                        "capture",
                        captureScopes(PROFILE)
                )
        );
        control.requireAdmission(
                CompanionCaptureDefinition.KIND,
                "capture",
                captureScopes(OTHER_PROFILE)
        );
    }

    @Test
    void routineReadFailuresDoNotDisableUnrelatedMutations() {
        for (StorageFailureKind kind : List.of(
                StorageFailureKind.UNKNOWN,
                StorageFailureKind.IO,
                StorageFailureKind.UNAVAILABLE,
                StorageFailureKind.DECODE
        )) {
            Fixture fixture = fixture();

            fixture.control().readCompleted(
                    TEST_READ,
                    PersistenceReadResult.failed(failure(
                            kind, "read_" + kind.name(), false
                    ))
            );

            assertUnrelatedCaptureReady(fixture);
            assertEquals(1, fixture.control().snapshot().readsFailed());
            assertEquals(1, fixture.signals().size());
            assertEquals(
                    "persistence_read_failed",
                    fixture.signals().getFirst().eventName()
            );
        }
    }

    @Test
    void unknownTransactionOutcomeEntersGlobalReadOnlyMode() {
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        PersistenceStartupCoordinator startup = ready(registry);
        PublicPersistenceControlPlane control =
                new PublicPersistenceControlPlane(registry);
        control.bind(startup);

        control.unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.Unknown<>(failure(
                        StorageFailureKind.UNKNOWN,
                        "commit_outcome_unknown",
                        false
                ))
        );

        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                startup.report().readiness()
        );
        assertEquals(
                "commit_outcome_unknown",
                control.snapshot().lastGlobalFailureCode()
        );
        assertEquals(
                1,
                control.snapshot().features().get(
                        PublicPersistenceFeatureRegistry.CAPTURE
                ).unitsFailed()
        );
    }

    @Test
    void confirmedRollbacksDoNotDisableUnrelatedMutations() {
        for (StorageFailureKind kind : List.of(
                StorageFailureKind.UNKNOWN,
                StorageFailureKind.IO,
                StorageFailureKind.UNAVAILABLE,
                StorageFailureKind.DECODE
        )) {
            Fixture fixture = fixture();

            fixture.control().unitOfWorkCompleted(
                    CompanionCaptureDefinition.KIND,
                    new PersistenceTransactionResult.RolledBack<>(failure(
                            kind, "rollback_" + kind.name(), false
                    ))
            );

            assertUnrelatedCaptureReady(fixture);
            assertEquals(
                    1,
                    fixture.control().snapshot().features().get(
                            PublicPersistenceFeatureRegistry.CAPTURE
                    ).unitsFailed()
            );
        }
    }

    @Test
    void checkpointFailureReportsWithoutDisablingMutations() {
        Fixture fixture = fixture();

        fixture.control().checkpointFailure(
                "after_commit", new IllegalStateException("checkpoint")
        );

        assertUnrelatedCaptureReady(fixture);
        assertEquals(1, fixture.control().snapshot().checkpointFailures());
        assertEquals(1, fixture.signals().size());
        assertEquals(
                "persistence_checkpoint_failed",
                fixture.signals().getFirst().eventName()
        );
    }

    @Test
    void corruptionAndSchemaFailureRemainGlobal() {
        Fixture corruptRead = fixture();
        corruptRead.control().readCompleted(
                TEST_READ,
                PersistenceReadResult.failed(failure(
                        StorageFailureKind.CORRUPT,
                        "sqlite_corrupt",
                        false
                ))
        );
        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                corruptRead.startup().report().readiness()
        );

        Fixture schemaRollback = fixture();
        schemaRollback.control().unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.RolledBack<>(failure(
                        StorageFailureKind.SCHEMA,
                        "sqlite_schema",
                        false
                ))
        );
        assertEquals(
                PersistenceReadinessLevel.GLOBAL_READ_ONLY,
                schemaRollback.startup().report().readiness()
        );
    }

    /** Guards the capture-race warning reported on 2026-08-05. */
    @Test
    void capturePreconditionRollbackDoesNotMakeAllPersistenceReadOnly() {
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        PersistenceStartupCoordinator startup = ready(registry);
        PublicPersistenceControlPlane control =
                new PublicPersistenceControlPlane(registry);
        control.bind(startup);

        control.unitOfWorkCompleted(
                CompanionCaptureDefinition.KIND,
                new PersistenceTransactionResult.RolledBack<>(
                        SqliteFailureClassifier.classify(
                                new IllegalStateException(
                                        "capture_prepare_not_exact_live_profile"
                                ),
                                "companion_capture"
                        )
                )
        );

        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                startup.report().readiness()
        );
        assertNull(control.snapshot().lastGlobalFailureCode());
        control.requireAdmission(
                CompanionCaptureDefinition.KIND,
                "companion_capture",
                captureScopes(PROFILE)
        );
    }

    private Fixture fixture() {
        PersistenceFeatureRegistry registry =
                PublicPersistenceFeatureRegistry.create();
        PersistenceStartupCoordinator startup = ready(registry);
        ArrayList<PersistenceFailureSignal> signals = new ArrayList<>();
        PublicPersistenceControlPlane control =
                new PublicPersistenceControlPlane(registry, signals::add);
        control.bind(startup);
        return new Fixture(control, startup, signals);
    }

    private void assertUnrelatedCaptureReady(Fixture fixture) {
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                fixture.startup().report().readiness()
        );
        assertNull(fixture.control().snapshot().lastGlobalFailureCode());
        fixture.control().requireAdmission(
                CompanionCaptureDefinition.KIND,
                "companion_capture",
                captureScopes(OTHER_PROFILE)
        );
    }

    private PersistenceStartupCoordinator ready(
            PersistenceFeatureRegistry registry
    ) {
        EnumMap<PersistenceStartupNode, PersistenceStartupAction> actions =
                new EnumMap<>(PersistenceStartupNode.class);
        for (PersistenceStartupNode node
                : PersistenceStartupNode.values()) {
            actions.put(
                    node,
                    () -> CompletableFuture.completedFuture(
                            PersistenceStartupAction.Result.COMPLETE
                    )
            );
        }
        PersistenceStartupCoordinator startup =
                new PersistenceStartupCoordinator(registry, Map.copyOf(actions));
        assertTrue(startup.advance().toCompletableFuture().join().complete());
        return startup;
    }

    private List<OperationScope> captureScopes(ProfileId profileId) {
        return List.of(
                OperationScope.profile(profileId),
                OperationScope.owner(OWNER)
        );
    }

    private StorageFailure failure(
            StorageFailureKind kind,
            String code,
            boolean retryable
    ) {
        return new StorageFailure(
                kind, code, "control_plane_test", retryable, null
        );
    }

    private record Fixture(
            PublicPersistenceControlPlane control,
            PersistenceStartupCoordinator startup,
            ArrayList<PersistenceFailureSignal> signals
    ) {
    }
}
