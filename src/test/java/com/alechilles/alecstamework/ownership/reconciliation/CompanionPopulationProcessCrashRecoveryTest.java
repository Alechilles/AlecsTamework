package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionDecision;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.integration.claims.ClaimResolution;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.alechilles.alecstamework.ownership.CompanionPopulationBootstrapService;
import com.alechilles.alecstamework.ownership.OwnerPopulationDecision;
import com.alechilles.alecstamework.ownership.OwnerPopulationEntry;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationLimitScope;
import com.alechilles.alecstamework.ownership.OwnerPopulationOperation;
import com.alechilles.alecstamework.ownership.OwnerPopulationReadiness;
import com.alechilles.alecstamework.ownership.OwnerPopulationTransitionRequest;
import com.alechilles.alecstamework.persistence.sqlite.CompanionIdentityRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationCoverageRepository;
import com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationOperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.PersistenceHealthService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Verifies journal recovery after an abrupt JVM halt at every durable mutation boundary. */
class CompanionPopulationProcessCrashRecoveryTest {
    private static final ClaimChunkCoordinate CHUNK = new ClaimChunkCoordinate(
            CompanionPopulationCrashBoundaryChild.WORLD, 0, 0
    );
    private static final UUID CLAIM_PARTY =
            UUID.fromString("00000000-0000-0000-0000-000000000904");

    @TempDir
    Path tempDir;

    @Test
    void preparedCrashClosesUnappliedJournalWithoutReleasingPopulationSlots() throws Exception {
        verifyBoundary(new Expectation(
                CompanionPopulationOperationRecord.State.PREPARED,
                CompanionPopulationOperationRecord.State.FAILED,
                CompanionPopulationCrashBoundaryChild.OLD_OWNER,
                OwnerPopulationReadiness.READY,
                0,
                1,
                null
        ));
    }

    @Test
    void applyingCrashCommitsObservedTargetWithoutReleasingPopulationSlots() throws Exception {
        verifyBoundary(new Expectation(
                CompanionPopulationOperationRecord.State.APPLYING,
                CompanionPopulationOperationRecord.State.COMMITTED,
                CompanionPopulationCrashBoundaryChild.NEW_OWNER,
                OwnerPopulationReadiness.READY,
                1,
                0,
                null
        ));
    }

    @Test
    void appliedCrashQuarantinesPendingSourceFinalizationAndRetainsSlots() throws Exception {
        verifyBoundary(new Expectation(
                CompanionPopulationOperationRecord.State.APPLIED,
                CompanionPopulationOperationRecord.State.APPLIED,
                CompanionPopulationCrashBoundaryChild.NEW_OWNER,
                OwnerPopulationReadiness.RECONCILING,
                0,
                0,
                "operation-recovery-source-finalization-pending:death_record"
        ));
    }

    @Test
    void compensatingCrashQuarantinesIncompleteRollbackAndRetainsSlots() throws Exception {
        verifyBoundary(new Expectation(
                CompanionPopulationOperationRecord.State.COMPENSATING,
                CompanionPopulationOperationRecord.State.COMPENSATING,
                CompanionPopulationCrashBoundaryChild.OLD_OWNER,
                OwnerPopulationReadiness.RECONCILING,
                0,
                0,
                "operation-recovery-compensation-incomplete"
        ));
    }

    private void verifyBoundary(Expectation expectation) throws Exception {
        Path database = tempDir.resolve(expectation.boundary().name().toLowerCase(Locale.ROOT))
                .resolve("boundary.sqlite");
        Files.createDirectories(database.getParent());
        String childOutput = haltChildAt(expectation.boundary(), database);
        try (CompanionPopulationOperationRecoveryTestSupport.Harness harness =
                     CompanionPopulationOperationRecoveryTestSupport.open(
                             database.getParent(), database.getFileName().toString()
                     )) {
            assertEquals(expectation.boundary(), harness.operationState(), childOutput);
            UUID persistedOwnerBeforeRecovery = expectation.boundary()
                    == CompanionPopulationOperationRecord.State.APPLIED
                    ? CompanionPopulationCrashBoundaryChild.NEW_OWNER
                    : CompanionPopulationCrashBoundaryChild.OLD_OWNER;
            assertEquals(persistedOwnerBeforeRecovery, harness.state().ownerUuid());

            CompanionPopulationOperationRecoveryService.RecoveryResult recovery = harness.recover(
                    List.of(CompanionPopulationOperationRecoveryTestSupport.physical(
                            CompanionPopulationCrashBoundaryChild.NPC_UUID,
                            CompanionPopulationCrashBoundaryChild.NEW_OWNER,
                            CompanionPopulationCrashBoundaryChild.WORLD,
                            0,
                            0
                    ))
            );

            assertEquals(expectation.recoveredState(), harness.operationState());
            assertEquals(expectation.committed(), recovery.committed());
            assertEquals(expectation.canceled(), recovery.canceled());
            assertEquals(expectation.ambiguousReason() == null, recovery.complete());
            if (expectation.ambiguousReason() != null) {
                assertEquals(1, recovery.ambiguous().size());
                assertEquals(expectation.ambiguousReason(), recovery.ambiguous().getFirst().reason());
            }

            Projection projection = bootstrap(harness);
            assertProjectionRetainsCapacity(projection, expectation);
        }
    }

    @Nonnull
    private static String haltChildAt(
            CompanionPopulationOperationRecord.State boundary,
            Path database
    ) throws Exception {
        String testClasspath = System.getProperty("surefire.test.class.path");
        if (testClasspath == null || testClasspath.isBlank()) {
            testClasspath = System.getProperty("java.class.path");
        }
        if (testClasspath == null || testClasspath.isBlank()) {
            throw new IllegalStateException("Forked test JVM classpath is unavailable.");
        }
        Path javaHome = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaHome.resolve("java.exe");
        if (!Files.isRegularFile(java)) {
            java = javaHome.resolve("java");
        }
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                testClasspath,
                CompanionPopulationCrashBoundaryChild.class.getName(),
                boundary.name(),
                database.toString()
        ).redirectErrorStream(true).start();
        if (!process.waitFor(30L, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5L, TimeUnit.SECONDS);
            throw new AssertionError("Crash-boundary child did not terminate.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(CompanionPopulationCrashBoundaryChild.HALT_EXIT_CODE, process.exitValue(), output);
        return output;
    }

    private static Projection bootstrap(
            CompanionPopulationOperationRecoveryTestSupport.Harness harness
    ) {
        OwnerPopulationIndex ownerIndex = new OwnerPopulationIndex();
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        CompanionPopulationBootstrapService service = new CompanionPopulationBootstrapService(
                harness.repository(),
                new CompanionPopulationCoverageRepository(harness.connections(), harness.queue()),
                new CompanionIdentityRepository(harness.connections()),
                new PersistenceHealthService(),
                ownerIndex,
                identities,
                claimIndex
        );
        return new Projection(service.load(), ownerIndex, claimIndex);
    }

    private static void assertProjectionRetainsCapacity(
            Projection projection,
            Expectation expectation
    ) {
        OwnerPopulationEntry entry = projection.ownerIndex().entry(
                CompanionPopulationCrashBoundaryChild.PROFILE_ID
        ).orElseThrow();
        assertEquals(expectation.expectedOwner(), entry.ownerId());
        assertEquals(1, projection.result().profileCount());
        assertEquals(
                expectation.readiness() == OwnerPopulationReadiness.READY ? 0 : 1,
                projection.result().nonterminalOperationCount()
        );
        assertEquals(expectation.readiness(), projection.result().globalReadiness());
        assertEquals(expectation.readiness(), projection.result().perWorldReadiness());
        assertEquals(expectation.readiness(), projection.ownerIndex().readiness(
                OwnerPopulationLimitScope.GLOBAL
        ));
        assertEquals(
                expectation.readiness() == OwnerPopulationReadiness.READY
                        ? ClaimOccupancyReadiness.READY
                        : ClaimOccupancyReadiness.RECONCILING,
                projection.claimIndex().readiness()
        );
        assertEquals(1L, projection.ownerIndex().counts(
                expectation.expectedOwner(), CompanionPopulationCrashBoundaryChild.WORLD
        ).globalCommitted());
        assertEquals(1L, projection.ownerIndex().counts(
                expectation.expectedOwner(), CompanionPopulationCrashBoundaryChild.WORLD
        ).worldCommitted());
        UUID otherOwner = expectation.expectedOwner().equals(
                CompanionPopulationCrashBoundaryChild.OLD_OWNER
        ) ? CompanionPopulationCrashBoundaryChild.NEW_OWNER
                : CompanionPopulationCrashBoundaryChild.OLD_OWNER;
        assertEquals(0L, projection.ownerIndex().counts(
                otherOwner, CompanionPopulationCrashBoundaryChild.WORLD
        ).globalCommitted());
        assertEquals(1, projection.claimIndex().snapshot().occupiedProfileCount());
        assertEquals(
                Set.of(CompanionPopulationCrashBoundaryChild.PROFILE_ID),
                projection.claimIndex().snapshot().profilesByChunk().get(CHUNK)
        );
        assertEquals(0, projection.ownerIndex().pendingReservationCount());

        OwnerPopulationDecision ownerAdmission = projection.ownerIndex().reserve(
                new OwnerPopulationTransitionRequest(
                        "post-crash-owner-admission-"
                                + expectation.boundary().name().toLowerCase(Locale.ROOT),
                        OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                        null,
                        null,
                        expectation.expectedOwner(),
                        CompanionPopulationCrashBoundaryChild.WORLD,
                        CompanionLifecycleState.ACTIVE,
                        OwnerPopulationOperation.NEW_OWNERSHIP,
                        OwnerPopulationLimitScope.GLOBAL,
                        1,
                        false
                )
        );
        assertFalse(ownerAdmission.allowed());
        assertEquals(
                expectation.readiness() == OwnerPopulationReadiness.READY
                        ? "owner-cap-reached"
                        : "owner-population-not-ready",
                ownerAdmission.reason()
        );

        ClaimAdmissionService claimService = new ClaimAdmissionService(projection.claimIndex());
        ClaimPolicyContext policy = claimPolicy();
        ClaimAdmissionDecision claimAdmission = claimService.reserve(
                new ClaimAdmissionRequest(
                        ClaimAdmissionOperation.EXTERNAL,
                        List.of(new ClaimOccupancyTransition(
                                null,
                                new ClaimOccupancyEntry(
                                        "post-crash-claim-admission",
                                        expectation.expectedOwner(),
                                        CompanionLifecycleState.ACTIVE,
                                        CHUNK,
                                        1L
                                )
                        )),
                        CHUNK,
                        policy,
                        0,
                        1,
                        false,
                        false,
                        TimeUnit.SECONDS.toNanos(10L)
                ),
                new ClaimLookupSession(policy)
        );
        assertFalse(claimAdmission.allowed());
        assertEquals(
                expectation.readiness() == OwnerPopulationReadiness.READY
                        ? "claim-cap-reached"
                        : "claim-occupancy-not-ready",
                claimAdmission.reason()
        );
        assertEquals(0, claimService.pendingReservationCount());
    }

    private static ClaimPolicyContext claimPolicy() {
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims(
                CompanionPopulationCrashBoundaryChild.WORLD, CLAIM_PARTY
        );
        ClaimFootprint footprint = new ClaimFootprint(List.of(CHUNK));
        ClaimIntegrationBridge bridge = new ClaimIntegrationBridge() {
            @Override
            public String providerId() {
                return "simpleclaims";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String getUnavailableReason() {
                return null;
            }

            @Override
            public ClaimResolution resolveClaim(String worldName, double blockX, double blockZ) {
                return ClaimResolution.found(key, footprint);
            }

            @Override
            public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
                return ClaimLookupResult.found(key, footprint.chunkCount());
            }
        };
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                "simpleclaims",
                ClaimProviderState.READY,
                Set.of(
                        ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT
                ),
                "test",
                null,
                new ClaimProviderGeneration("process-boundary", "test-loader", 1L),
                1L,
                bridge
        );
    }

    private record Expectation(
            CompanionPopulationOperationRecord.State boundary,
            CompanionPopulationOperationRecord.State recoveredState,
            UUID expectedOwner,
            OwnerPopulationReadiness readiness,
            int committed,
            int canceled,
            @Nullable String ambiguousReason
    ) {
    }

    private record Projection(
            CompanionPopulationBootstrapService.BootstrapResult result,
            OwnerPopulationIndex ownerIndex,
            ClaimOccupancyIndex claimIndex
    ) {
    }
}
