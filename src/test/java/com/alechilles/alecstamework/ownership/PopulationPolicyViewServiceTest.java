package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionService;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimAdmissionRequest;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimFootprint;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationBridge;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimLookupMetrics;
import com.alechilles.alecstamework.integration.claims.ClaimLookupResult;
import com.alechilles.alecstamework.integration.claims.ClaimLookupSession;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyReadiness;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPopulationKey;
import com.alechilles.alecstamework.integration.claims.ClaimProviderCapability;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationPolicyViewServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void globalLegacyAndV2UseAuthoritativeCommittedAndPendingIndexCounts() {
        OwnerPopulationIndex index = indexWithTwoOwnedEntries();
        OwnerPopulationDecision pending = index.reserve(new OwnerPopulationTransitionRequest(
                "profile-c", -1L, null, null, OWNER, "alpha",
                CompanionLifecycleState.ACTIVE, OwnerPopulationOperation.NEW_OWNERSHIP,
                OwnerPopulationLimitScope.GLOBAL, 5, false
        ));
        assertTrue(pending.allowed());
        PopulationPolicyViewService service = service(index, 5, OwnerPopulationLimitScope.GLOBAL);

        PopulationCapDecisionView legacy = service.evaluateLegacy(OWNER);
        OwnerPopulationCapDecisionViewV2 v2 = service.evaluate(
                new OwnerPopulationCapRequestV2(OWNER, null, 2)
        );

        assertTrue(legacy.allowed());
        assertEquals(3, legacy.currentCount());
        assertEquals(2, legacy.remainingHeadroom());
        assertTrue(v2.allowed());
        assertTrue(v2.authoritative());
        assertEquals(2L, v2.committedCount());
        assertEquals(1L, v2.pendingCount());
        assertEquals(2L, v2.remainingHeadroom());
        assertEquals(OwnerPopulationCapDecisionViewV2.Scope.GLOBAL, v2.scope());
    }

    @Test
    void perWorldLegacyFailsClosedAndV2RequiresWorldContext() {
        OwnerPopulationIndex index = indexWithTwoOwnedEntries();
        PopulationPolicyViewService service = service(index, 5, OwnerPopulationLimitScope.PER_WORLD);

        PopulationCapDecisionView legacy = service.evaluateLegacy(OWNER);
        OwnerPopulationCapDecisionViewV2 missingWorld = service.evaluate(
                new OwnerPopulationCapRequestV2(OWNER, null, 1)
        );
        OwnerPopulationCapDecisionViewV2 withWorld = service.evaluate(
                new OwnerPopulationCapRequestV2(OWNER, "alpha", 3)
        );

        assertFalse(legacy.allowed());
        assertEquals(-1, legacy.currentCount());
        assertEquals(0, legacy.remainingHeadroom());
        assertEquals("owner-cap-world-context-required", legacy.reason());
        assertFalse(missingWorld.allowed());
        assertFalse(missingWorld.authoritative());
        assertEquals(OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT, missingWorld.committedCount());
        assertEquals(0L, missingWorld.remainingHeadroom());
        assertTrue(withWorld.allowed());
        assertEquals(2L, withWorld.committedCount());
        assertEquals(3L, withWorld.remainingHeadroom());
    }

    @Test
    void degradedReadinessIsExplicitAndDiagnosticsExposeCountsAndReconciliation() {
        OwnerPopulationIndex index = indexWithTwoOwnedEntries();
        index.setReadiness(OwnerPopulationReadiness.DEGRADED);
        PopulationPolicyViewService service = service(index, 5, OwnerPopulationLimitScope.GLOBAL);
        service.setReconciliationSupplier(() -> new PopulationDiagnosticsView.ReconciliationView(
                "RECONCILING", "scanning", 4L, 10L, 2L, 0L, 1L, 0L, 100L, 0L
        ));

        OwnerPopulationCapDecisionViewV2 decision = service.evaluate(
                new OwnerPopulationCapRequestV2(OWNER, null, 1)
        );
        PopulationDiagnosticsView diagnostics = service.diagnostics();

        assertFalse(decision.allowed());
        assertFalse(decision.authoritative());
        assertEquals(OwnerPopulationCapDecisionViewV2.Readiness.DEGRADED, decision.readiness());
        assertEquals("owner-population-degraded", decision.reason());
        assertEquals(2L, diagnostics.counts().committedOwnerProfiles());
        assertEquals("DEGRADED", diagnostics.readiness().ownerGlobal());
        assertEquals(4L, diagnostics.reconciliation().scannedUnits());
    }

    @Test
    void currentProviderContextIsVisibleBeforeAnyLookupSession() {
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        claimIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.READY);
        ClaimAdmissionService claimService = new ClaimAdmissionService(claimIndex);
        ClaimPolicyContext context = readyContext();
        PopulationPolicyViewService service = new PopulationPolicyViewService(
                new OwnerPopulationIndex(), claimService, new ClaimLookupMetrics(),
                () -> new CompanionAdmissionPolicyResolver.Policy(
                        0, OwnerPopulationLimitScope.GLOBAL, 77L, 2, 0, false, context
                )
        );

        PopulationDiagnosticsView diagnostics = service.diagnostics();
        PopulationDiagnosticsView.ProviderContextView provider = diagnostics.claimLookups().provider();
        PopulationDiagnosticsView.ActiveRulesView rules = diagnostics.activeRules();

        assertEquals("SIMPLE_CLAIMS", provider.requestedProvider());
        assertEquals("simpleclaims", provider.providerId());
        assertEquals("READY", provider.state());
        assertEquals(77L, provider.settingsRevision());
        assertEquals("BREEDING", rules.operation());
        assertEquals(0, rules.ownerLimit());
        assertEquals("GLOBAL", rules.ownerScope());
        assertEquals(2, rules.claimLimitPerChunk());
        assertEquals(0, rules.claimLimitTotal());
        assertEquals(false, rules.requireClaim());
    }

    @Test
    void diagnosticsUseClaimAwarePolicyWithoutActivatingClaimsForOwnerCapReads() {
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        claimIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.READY);
        PopulationPolicyViewService service = new PopulationPolicyViewService(
                new OwnerPopulationIndex(),
                new ClaimAdmissionService(claimIndex),
                new ClaimLookupMetrics(),
                () -> new CompanionAdmissionPolicyResolver.Policy(
                        5, OwnerPopulationLimitScope.GLOBAL, 12L, 0, 0, false, offContext()
                ),
                () -> new CompanionAdmissionPolicyResolver.Policy(
                        5, OwnerPopulationLimitScope.GLOBAL, 77L, 2, 3, true, readyContext()
                )
        );

        OwnerPopulationCapDecisionViewV2 cap = service.evaluate(
                new OwnerPopulationCapRequestV2(OWNER, null, 1)
        );
        PopulationDiagnosticsView diagnostics = service.diagnostics();
        PopulationDiagnosticsView.ProviderContextView provider = diagnostics.claimLookups().provider();
        PopulationDiagnosticsView.ActiveRulesView rules = diagnostics.activeRules();

        assertEquals(5, cap.limit());
        assertEquals("SIMPLE_CLAIMS", provider.requestedProvider());
        assertEquals("simpleclaims", provider.providerId());
        assertEquals(77L, provider.settingsRevision());
        assertEquals(5, rules.ownerLimit());
        assertEquals(2, rules.claimLimitPerChunk());
        assertEquals(3, rules.claimLimitTotal());
        assertTrue(rules.requireClaim());
    }

    @Test
    void nonPublicClaimAdmissionFeedsSharedLookupDiagnostics() {
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        claimIndex.replaceCommittedEntries(List.of(), ClaimOccupancyReadiness.READY);
        ClaimAdmissionService claimService = new ClaimAdmissionService(claimIndex);
        ClaimLookupMetrics shared = new ClaimLookupMetrics();
        ClaimPolicyContext context = readyContext();
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate("alpha", 4, 5);
        ClaimOccupancyEntry proposed = new ClaimOccupancyEntry(
                "metrics-profile", OWNER, CompanionLifecycleState.ACTIVE, destination, 1L
        );
        ClaimAdmissionRequest request = ClaimAdmissionRequest.single(
                ClaimAdmissionOperation.SET_OWNER,
                new ClaimOccupancyTransition(null, proposed),
                destination,
                context,
                2,
                0,
                false,
                OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()
        );
        assertTrue(claimService.reserve(
                request, new ClaimLookupSession(context, true, shared)
        ).allowed());
        PopulationPolicyViewService service = new PopulationPolicyViewService(
                new OwnerPopulationIndex(), claimService, shared,
                () -> new CompanionAdmissionPolicyResolver.Policy(
                        0, OwnerPopulationLimitScope.GLOBAL, 77L, 2, 0, false, context
                )
        );

        PopulationDiagnosticsView.LookupMetricsView lookups = service.diagnostics().claimLookups();

        assertEquals(1L, lookups.sessions());
        assertEquals(1L, lookups.uniqueChunks());
        assertEquals(1L, lookups.providerCalls());
        assertEquals("simpleclaims", lookups.provider().providerId());
    }

    private static PopulationPolicyViewService service(OwnerPopulationIndex index,
                                                       int limit,
                                                       OwnerPopulationLimitScope scope) {
        ClaimOccupancyIndex claimIndex = new ClaimOccupancyIndex();
        ClaimAdmissionService claimService = new ClaimAdmissionService(claimIndex);
        CompanionAdmissionPolicyResolver.Policy policy = new CompanionAdmissionPolicyResolver.Policy(
                limit,
                scope,
                1L,
                0,
                0,
                false,
                offContext()
        );
        return new PopulationPolicyViewService(
                index,
                claimService,
                new ClaimLookupMetrics(),
                () -> policy
        );
    }

    private static OwnerPopulationIndex indexWithTwoOwnedEntries() {
        OwnerPopulationIndex index = new OwnerPopulationIndex();
        index.replaceCommittedEntries(List.of(
                new OwnerPopulationEntry(
                        "profile-a", OWNER, "alpha", CompanionLifecycleState.ACTIVE, 1L
                ),
                new OwnerPopulationEntry(
                        "profile-b", OWNER, "alpha", CompanionLifecycleState.CAPTURED, 2L
                )
        ), OwnerPopulationReadiness.READY);
        return index;
    }

    private static ClaimPolicyContext offContext() {
        return new ClaimPolicyContext(
                "Off",
                ClaimIntegrationProvider.OFF,
                ClaimIntegrationProvider.OFF,
                "off",
                ClaimProviderState.OFF,
                Set.of(),
                null,
                "Claim integration is off.",
                ClaimProviderGeneration.NONE,
                1L,
                null
        );
    }

    private static ClaimPolicyContext readyContext() {
        ClaimChunkCoordinate coordinate = new ClaimChunkCoordinate("alpha", 4, 5);
        ClaimPopulationKey key = ClaimPopulationKey.simpleClaims("alpha", OWNER);
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
            public ClaimLookupResult lookupClaim(String worldName, double blockX, double blockZ) {
                return ClaimLookupResult.found(key, 1);
            }

            @Override
            public com.alechilles.alecstamework.integration.claims.ClaimResolution resolveClaim(
                    String worldName, double blockX, double blockZ
            ) {
                return com.alechilles.alecstamework.integration.claims.ClaimResolution.found(
                        key, new ClaimFootprint(List.of(coordinate))
                );
            }
        };
        return new ClaimPolicyContext(
                "SimpleClaims",
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                ClaimIntegrationProvider.SIMPLE_CLAIMS,
                "simpleclaims",
                ClaimProviderState.READY,
                Set.of(ClaimProviderCapability.STABLE_CLAIM_IDENTITY,
                        ClaimProviderCapability.WORLD_SCOPED_EXTENT),
                "test",
                null,
                new ClaimProviderGeneration("plugin", "loader", 3L),
                77L,
                bridge
        );
    }
}
