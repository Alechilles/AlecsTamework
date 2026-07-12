package com.alechilles.alecstamework.api;

import com.alechilles.alecstamework.api.internal.UnavailablePopulationPolicyAuthority;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopulationPolicyApiV2Test {
    private static final UUID OWNER_A = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_B = UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID NPC = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final PopulationAdmissionLocation SOURCE =
            new PopulationAdmissionLocation("alpha", 1, 2);
    private static final PopulationAdmissionLocation DESTINATION =
            new PopulationAdmissionLocation("beta", 3, 4);

    @Test
    void legacyPopulationSignatureAndRecordConstructorRemainPresentAndDeprecated() throws Exception {
        Method method = PolicyApi.class.getMethod("evaluatePopulationCap", UUID.class);
        Constructor<PopulationCapDecisionView> constructor = PopulationCapDecisionView.class.getDeclaredConstructor(
                UUID.class,
                boolean.class,
                boolean.class,
                int.class,
                int.class,
                int.class,
                String.class,
                String.class
        );

        assertTrue(method.isAnnotationPresent(Deprecated.class));
        assertNotNull(constructor);
        PopulationCapDecisionView view = constructor.newInstance(
                OWNER_A, false, true, 5, -1, 0, "PER_WORLD", "owner-cap-world-context-required"
        );
        assertEquals(-1, view.currentCount());
    }

    @Test
    void durableAdmissionStagesAreCompletionAwareWhileApplyClaimRemainsSynchronous() throws Exception {
        assertEquals(
                CompletionStage.class,
                PopulationAdmissionApi.class.getMethod(
                        "tryAdmit", PopulationAdmissionRequest.class
                ).getReturnType()
        );
        assertEquals(
                CompletionStage.class,
                PopulationAdmissionApi.class.getMethod(
                        "tryAdmitBatch", PopulationBatchAdmissionRequest.class
                ).getReturnType()
        );
        assertEquals(
                PopulationAdmissionDecision.class,
                PopulationAdmissionApi.class.getMethod(
                        "claimForApply", PopulationAdmissionToken.class
                ).getReturnType()
        );
        assertEquals(
                CompletionStage.class,
                PopulationAdmissionApi.class.getMethod(
                        "commit", PopulationAdmissionToken.class
                ).getReturnType()
        );
        assertEquals(
                CompletionStage.class,
                PopulationAdmissionApi.class.getMethod(
                        "cancel", PopulationAdmissionToken.class
                ).getReturnType()
        );
        assertEquals(
                CompletionStage.class,
                PopulationAdmissionApi.class.getMethod("cleanupExpired").getReturnType()
        );
    }

    @Test
    void ownerOnlyV2RequiresOwnerWorldSlotsAndRepresentsUnknownCountsExplicitly() {
        assertThrows(NullPointerException.class, () -> new OwnerPopulationCapRequestV2(null, "alpha", 1));
        assertThrows(IllegalArgumentException.class, () -> new OwnerPopulationCapRequestV2(OWNER_A, "alpha", 0));

        OwnerPopulationCapRequestV2 request = new OwnerPopulationCapRequestV2(OWNER_A, " alpha ", 2);
        OwnerPopulationCapDecisionViewV2 decision =
                UnavailablePopulationPolicyAuthority.INSTANCE.evaluateOwnerCap(request);

        assertEquals("alpha", request.worldName());
        assertFalse(decision.allowed());
        assertFalse(decision.authoritative());
        assertEquals(OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT, decision.committedCount());
        assertEquals(OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT, decision.pendingCount());
        assertEquals(OwnerPopulationCapDecisionViewV2.Scope.UNKNOWN, decision.scope());
        assertEquals(OwnerPopulationCapDecisionViewV2.Readiness.UNAVAILABLE, decision.readiness());
    }

    @Test
    void admissionIdentityRequiresCanonicalProvisionalOrIdempotencyAndKeepsIdempotency() {
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionIdentity(" ", null, " "));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PopulationAdmissionIdentity("profile-a", "provisional-a", "request-a")
        );

        PopulationAdmissionIdentity identity = new PopulationAdmissionIdentity(null, " provisional-a ", " request-a ");
        assertEquals("provisional-a", identity.provisionalProfileId());
        assertEquals("request-a", identity.idempotencyKey());
        assertTrue(identity.provisional());
        assertFalse(identity.canonical());
    }

    @Test
    void admissionRequestValidatesRevisionOwnersLocationsOperationSlotsAndForce() {
        PopulationAdmissionIdentity canonical = new PopulationAdmissionIdentity("profile-a", null, "transfer-a");
        PopulationAdmissionRequest transfer = new PopulationAdmissionRequest(
                canonical,
                NPC,
                7L,
                OWNER_A,
                OWNER_B,
                SOURCE,
                DESTINATION,
                PopulationAdmissionOperation.OWNER_TRANSFER,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
        assertEquals("transfer-a", transfer.identity().idempotencyKey());

        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                canonical, NPC, -1L, null, OWNER_B, null, DESTINATION,
                PopulationAdmissionOperation.NEW_OWNERSHIP, 1, PopulationAdmissionForcePolicy.ENFORCE
        ));
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, OWNER_B, null, DESTINATION,
                PopulationAdmissionOperation.OWNER_TRANSFER, 1, PopulationAdmissionForcePolicy.ENFORCE
        ));
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, OWNER_A, SOURCE, DESTINATION,
                PopulationAdmissionOperation.OWNER_TRANSFER, 1, PopulationAdmissionForcePolicy.ENFORCE
        ));
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, OWNER_B, SOURCE, DESTINATION,
                PopulationAdmissionOperation.OWNER_TRANSFER, 2, PopulationAdmissionForcePolicy.ENFORCE
        ));
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, OWNER_A, SOURCE, DESTINATION,
                PopulationAdmissionOperation.REHOME, 1, PopulationAdmissionForcePolicy.ADMIN_OVERRIDE
        ));
    }

    @Test
    void ownerClearCanPreserveLiveLifecycleOrDeclarePermanentRelease() {
        PopulationAdmissionIdentity canonical = new PopulationAdmissionIdentity("profile-clear", null, null);
        PopulationAdmissionRequest liveClear = new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, null, SOURCE, DESTINATION,
                PopulationAdmissionOperation.OWNER_CLEAR, 1, PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.ACTIVE
        );
        PopulationAdmissionRequest permanentRelease = new PopulationAdmissionRequest(
                canonical, NPC, 7L, OWNER_A, null, SOURCE, null,
                PopulationAdmissionOperation.OWNER_CLEAR, 1, PopulationAdmissionForcePolicy.ENFORCE,
                PopulationCompanionLifecycle.RELEASED
        );

        assertEquals(PopulationCompanionLifecycle.ACTIVE, liveClear.targetLifecycle());
        assertEquals(PopulationCompanionLifecycle.RELEASED, permanentRelease.targetLifecycle());
    }

    @Test
    void provisionalSingleAndExplicitBatchUnavailableAuthorityFailClosedWithoutIssuingToken() {
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, "birth-job-a", "birth-attempt-a"),
                null,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                OWNER_A,
                null,
                DESTINATION,
                PopulationAdmissionOperation.BREEDING,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );

        PopulationAdmissionDecision decision = UnavailablePopulationPolicyAuthority.INSTANCE
                .tryAdmit(request)
                .toCompletableFuture()
                .join();

        assertEquals(PopulationAdmissionDecision.Status.UNAVAILABLE, decision.status());
        assertFalse(decision.accepted());
        assertNull(decision.token());
        assertEquals(OwnerPopulationCapDecisionViewV2.UNKNOWN_COUNT, decision.committedCount());

        PopulationBatchAdmissionRequest batch = new PopulationBatchAdmissionRequest(
                "birth-batch-a",
                java.util.List.of(request),
                PopulationBatchAdmissionMode.EXACT
        );
        PopulationBatchAdmissionDecision batchDecision = UnavailablePopulationPolicyAuthority.INSTANCE
                .tryAdmitBatch(batch)
                .toCompletableFuture()
                .join();
        assertEquals(PopulationBatchAdmissionDecision.Status.UNAVAILABLE, batchDecision.status());
        assertEquals(0, batchDecision.admittedUnits());
    }

    @Test
    void unownedBreedingStillRequiresDestinationAndSingleRequestsCannotImplyMultipleChildren() {
        PopulationAdmissionIdentity identity = new PopulationAdmissionIdentity(
                null,
                "unowned-child-a",
                "unowned-birth-a"
        );
        PopulationAdmissionRequest request = new PopulationAdmissionRequest(
                identity,
                NPC,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                null,
                DESTINATION,
                PopulationAdmissionOperation.BREEDING,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );

        assertNull(request.newOwnerUuid());
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                identity, NPC, PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null, null, null, null, PopulationAdmissionOperation.BREEDING,
                1, PopulationAdmissionForcePolicy.ENFORCE
        ));
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionRequest(
                identity, NPC, PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null, null, null, DESTINATION, PopulationAdmissionOperation.BREEDING,
                2, PopulationAdmissionForcePolicy.ENFORCE
        ));
    }

    @Test
    void explicitBatchRequiresDistinctPerChildIdentityAndCurrentUuid() {
        PopulationAdmissionRequest first = unownedChild(
                "child-a",
                "birth-a",
                UUID.fromString("00000000-0000-0000-0000-000000000211")
        );
        PopulationAdmissionRequest second = unownedChild(
                "child-b",
                "birth-b",
                UUID.fromString("00000000-0000-0000-0000-000000000212")
        );

        PopulationBatchAdmissionRequest batch = new PopulationBatchAdmissionRequest(
                "batch-a",
                java.util.List.of(first, second),
                PopulationBatchAdmissionMode.EXACT
        );

        assertEquals(2, batch.units().size());
        assertThrows(IllegalArgumentException.class, () -> new PopulationBatchAdmissionRequest(
                "batch-duplicate-profile",
                java.util.List.of(first, first),
                PopulationBatchAdmissionMode.EXACT
        ));
        PopulationAdmissionRequest duplicateUuid = unownedChild(
                "child-c",
                "birth-c",
                first.currentNpcUuid()
        );
        assertThrows(IllegalArgumentException.class, () -> new PopulationBatchAdmissionRequest(
                "batch-duplicate-uuid",
                java.util.List.of(first, duplicateUuid),
                PopulationBatchAdmissionMode.EXACT
        ));
    }

    @Test
    void tokenValidatesAuthorityMetadataWithoutAssumingPositiveMonotonicEpoch() {
        PopulationAdmissionToken token = new PopulationAdmissionToken(
                UUID.randomUUID(),
                UUID.randomUUID(),
                -500L,
                3L,
                "provider-generation-a",
                OwnerPopulationCapDecisionViewV2.Readiness.READY
        );

        assertEquals(-500L, token.expiresAtMonotonicNanos());
        assertThrows(IllegalArgumentException.class, () -> new PopulationAdmissionToken(
                UUID.randomUUID(), UUID.randomUUID(), 0L, -1L, "provider", OwnerPopulationCapDecisionViewV2.Readiness.READY
        ));
    }

    private static PopulationAdmissionRequest unownedChild(String profileId,
                                                           String idempotencyKey,
                                                           UUID npcUuid) {
        return new PopulationAdmissionRequest(
                new PopulationAdmissionIdentity(null, profileId, idempotencyKey),
                npcUuid,
                PopulationAdmissionRequest.NEW_PROFILE_REVISION,
                null,
                null,
                null,
                DESTINATION,
                PopulationAdmissionOperation.BREEDING,
                1,
                PopulationAdmissionForcePolicy.ENFORCE
        );
    }
}
