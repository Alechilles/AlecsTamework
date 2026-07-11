package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.OwnerPopulationCapDecisionViewV2;
import com.alechilles.alecstamework.api.OwnerPopulationCapRequestV2;
import com.alechilles.alecstamework.api.PopulationAdmissionApi;
import com.alechilles.alecstamework.api.PopulationAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionToken;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionDecision;
import com.alechilles.alecstamework.api.PopulationBatchAdmissionRequest;
import com.alechilles.alecstamework.api.PopulationCapDecisionView;
import com.alechilles.alecstamework.api.PopulationDiagnosticsView;
import com.alechilles.alecstamework.damage.SimpleClaimsTamedDamagePolicy;
import com.alechilles.alecstamework.persistence.sqlite.TameworkPersistenceRuntime;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TameworkApiPopulationPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void injectedPopulationAuthorityOwnsV2EvaluationAndAdmissionLifecycle() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            FakeAuthority authority = new FakeAuthority();
            TameworkApiImpl api = new TameworkApiImpl(
                    runtime,
                    new TameworkEventBus(null),
                    null,
                    new InteractionExtensionRegistry(null),
                    new TraitEffectRegistry(null, runtime.getNpcProfileRepository()),
                    new SimpleClaimsTamedDamagePolicy(),
                    authority
            );
            OwnerPopulationCapRequestV2 request = new OwnerPopulationCapRequestV2(
                    UUID.fromString("00000000-0000-0000-0000-000000000101"),
                    "alpha",
                    2
            );

            OwnerPopulationCapDecisionViewV2 decision = api.evaluatePopulationCap(request);

            assertSame(request, authority.lastRequest);
            assertSame(authority, api.populationAdmissions());
            assertEquals(2L, decision.committedCount());
            assertEquals(1L, decision.pendingCount());
            assertEquals(OwnerPopulationCapDecisionViewV2.Scope.PER_WORLD, decision.scope());
        }
    }

    private static final class FakeAuthority implements PopulationPolicyAuthority {
        private final PopulationAdmissionApi unavailable = PopulationAdmissionApi.unavailable();
        private OwnerPopulationCapRequestV2 lastRequest;

        @Override
        public OwnerPopulationCapDecisionViewV2 evaluateOwnerCap(OwnerPopulationCapRequestV2 request) {
            lastRequest = request;
            return new OwnerPopulationCapDecisionViewV2(
                    request.ownerUuid(),
                    request.worldName(),
                    request.requestedSlots(),
                    true,
                    true,
                    true,
                    10,
                    2L,
                    1L,
                    5L,
                    OwnerPopulationCapDecisionViewV2.Scope.PER_WORLD,
                    OwnerPopulationCapDecisionViewV2.Readiness.READY,
                    "owner-cap-allow"
            );
        }

        @Override
        public PopulationCapDecisionView evaluateLegacyOwnerCap(UUID ownerUuid) {
            return new PopulationCapDecisionView(
                    ownerUuid, true, false, 0, 0, Integer.MAX_VALUE, "GLOBAL", "owner-cap-disabled"
            );
        }

        @Override
        public PopulationDiagnosticsView populationDiagnostics() {
            return PopulationDiagnosticsView.unavailable();
        }

        @Override
        public CompletionStage<PopulationAdmissionDecision> tryAdmit(PopulationAdmissionRequest request) {
            return unavailable.tryAdmit(request);
        }

        @Override
        public CompletionStage<PopulationBatchAdmissionDecision> tryAdmitBatch(
                PopulationBatchAdmissionRequest request
        ) {
            return unavailable.tryAdmitBatch(request);
        }

        @Override
        public PopulationAdmissionDecision claimForApply(PopulationAdmissionToken token) {
            return unavailable.claimForApply(token);
        }

        @Override
        public CompletionStage<PopulationAdmissionDecision> commit(PopulationAdmissionToken token) {
            return unavailable.commit(token);
        }

        @Override
        public CompletionStage<PopulationAdmissionDecision> cancel(PopulationAdmissionToken token) {
            return unavailable.cancel(token);
        }

        @Override
        public CompletionStage<Integer> cleanupExpired() {
            return unavailable.cleanupExpired();
        }
    }
}
