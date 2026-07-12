package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Regression coverage for canonical dormant-source validation before coop replacement spawn. */
class CoopPopulationReleaseAdmissionServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "00000000-0000-0000-0000-000000000101"
    );
    private static final UUID HOUSED = UUID.fromString(
            "00000000-0000-0000-0000-000000000201"
    );
    private static final UUID PLANNED = UUID.fromString(
            "00000000-0000-0000-0000-000000000202"
    );

    @Test
    void classifiesReleaseAsCoopPlacementAdmission() {
        assertEquals(
                com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation.COOP_RELEASE,
                CoopPopulationReleaseAdmissionService.CLAIM_OPERATION
        );
    }

    @Test
    void releaseRequestPreservesExactCallerPlannedUuid() {
        CoopPopulationReleaseAdmissionService.ReleaseRequest request = request(PLANNED);

        assertEquals(HOUSED, request.previousNpcUuid());
        assertEquals(PLANNED, request.plannedNpcUuid());
        assertEquals("world", request.worldName());
        assertEquals("managed-release", request.idempotencyKey());
    }

    @Test
    void releaseRequestRejectsReusingTheHousedSourceUuid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> request(HOUSED)
        );
    }

    @Test
    void durableContextFactoryReceivesAndPersistsTheCallerPlannedUuid() {
        CoopPopulationReleaseAdmissionService.ReleaseRequest request = request(PLANNED);
        AtomicReference<UUID> factoryUuid = new AtomicReference<>();

        String context = CoopPopulationReleaseAdmissionService.contextJson(
                request,
                planned -> {
                    factoryUuid.set(planned);
                    return "{\"managedCoopOperationId\":\"release-operation\"}";
                }
        );
        JsonObject json = JsonParser.parseString(context).getAsJsonObject();

        assertEquals(PLANNED, factoryUuid.get());
        assertEquals(PLANNED.toString(), json.get("plannedNpcUuid").getAsString());
        assertEquals("release-operation", json.get("managedCoopOperationId").getAsString());
    }

    @Test
    void durableContextCannotReplaceTheCallerPlannedUuid() {
        CoopPopulationReleaseAdmissionService.ReleaseRequest request = request(PLANNED);

        assertThrows(
                IllegalArgumentException.class,
                () -> CoopPopulationReleaseAdmissionService.contextJson(
                        request,
                        ignored -> "{\"plannedNpcUuid\":\"" + UUID.randomUUID() + "\"}"
                )
        );
    }

    @Test
    void acceptsExactCoopedOwnerAndClaimProjection() {
        assertNull(validate(
                HOUSED,
                CompanionLifecycleState.COOP,
                CompanionLifecycleState.COOP
        ));
    }

    @Test
    void deniesProfileAlreadyMappedToAnotherActiveRepresentation() {
        assertEquals(
                "coop-release-duplicate-active-profile",
                validate(UUID.randomUUID(), CompanionLifecycleState.COOP, CompanionLifecycleState.COOP)
        );
    }

    @Test
    void deniesWrongOwnerLifecycleEvenWhenRevisionAndOwnerMatch() {
        assertEquals(
                "coop-release-profile-not-cooped",
                validate(HOUSED, CompanionLifecycleState.ACTIVE, CompanionLifecycleState.COOP)
        );
    }

    @Test
    void deniesWrongClaimLifecycleEvenWhenOwnerProjectionIsCooped() {
        assertEquals(
                "coop-release-profile-not-cooped",
                validate(HOUSED, CompanionLifecycleState.COOP, CompanionLifecycleState.UNLOADED)
        );
    }

    private static String validate(
            UUID currentNpcUuid,
            CompanionLifecycleState ownerLifecycle,
            CompanionLifecycleState claimLifecycle
    ) {
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "coop-profile", OWNER, "world", ownerLifecycle, 7L
        );
        ClaimOccupancyEntry claim = new ClaimOccupancyEntry(
                "coop-profile", OWNER, claimLifecycle, null, 7L
        );
        return CoopPopulationReleaseAdmissionService.validateDormantSource(
                HOUSED, currentNpcUuid, OWNER, owner, claim
        );
    }

    @Test
    void classifiesOnlyReadyPolicyDenialsAsDefinitiveBeforeAdmission() {
        CompanionPopulationPreparationResult result = deniedPopulationResult(
                "owner-cap-reached", OwnerPopulationReadiness.READY);

        assertEquals(
                CoopPopulationReleaseAdmissionService.PreparationDisposition.DEFINITIVE_DENIAL,
                CoopPopulationReleaseAdmissionService.PreparationResult.denied(result)
                        .disposition());
    }

    @Test
    void classifiesRecoveredInFlightProfileAsAmbiguous() {
        CompanionPopulationPreparationResult result = deniedPopulationResult(
                "profile_operation_in_flight", OwnerPopulationReadiness.READY);

        assertEquals(
                CoopPopulationReleaseAdmissionService.PreparationDisposition.AMBIGUOUS,
                CoopPopulationReleaseAdmissionService.PreparationResult.denied(result)
                        .disposition());
        assertFalse(CoopPopulationReleaseAdmissionService
                .definitivePreAdmissionDenial(result));
    }

    @Test
    void degradedReadinessCannotAuthorizeLifecycleRollback() {
        CompanionPopulationPreparationResult result = deniedPopulationResult(
                "owner-cap-reached", OwnerPopulationReadiness.DEGRADED);

        assertEquals(
                CoopPopulationReleaseAdmissionService.PreparationDisposition.AMBIGUOUS,
                CoopPopulationReleaseAdmissionService.PreparationResult.denied(result)
                        .disposition());
    }

    private static CoopPopulationReleaseAdmissionService.ReleaseRequest request(UUID plannedUuid) {
        return new CoopPopulationReleaseAdmissionService.ReleaseRequest(
                HOUSED,
                plannedUuid,
                OWNER,
                "Owner",
                " world ",
                4,
                7,
                " managed-release "
        );
    }

    private static CompanionPopulationPreparationResult deniedPopulationResult(
            String reason,
            OwnerPopulationReadiness readiness) {
        OwnerPopulationDecision ownerDecision = new OwnerPopulationDecision(
                false, reason, null, readiness, 10, 1L, 0L, 7L, true, false);
        return new CompanionPopulationPreparationResult(
                false, reason, ownerDecision, null, null);
    }
}
