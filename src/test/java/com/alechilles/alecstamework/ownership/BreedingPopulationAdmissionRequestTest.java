package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for canonical breeding-pair metadata at the durable request boundary. */
class BreedingPopulationAdmissionRequestTest {

    @Test
    void canonicalRequestSortsAndDeduplicatesParentProfiles() {
        BreedingPopulationAdmissionRequest request = request(
                List.of("parent-z", "parent-a", "parent-z")
        );

        assertTrue(request.hasCanonicalParentPair());
        assertEquals(List.of("parent-a", "parent-z"), request.parentProfileIds());
        assertThrows(
                IllegalArgumentException.class,
                () -> request(List.of("same-parent", "same-parent"))
        );
    }

    @Test
    void admissionTargetPersistsSortedPairAndOriginalWorld() {
        BreedingPopulationAdmissionRequest request = request(
                List.of("parent-z", "parent-a")
        );
        BreedingPopulationAdmissionUnitFactory.PreparedUnits prepared =
                new BreedingPopulationAdmissionUnitFactory().build(
                        request,
                        1,
                        new ClaimChunkCoordinate("world-a", 4, 7),
                        policy(), freshBaselineResolver(), false
                );

        JsonObject target = JsonParser.parseString(
                prepared.units().get(0).ownerPlan().targetContextJson()
        ).getAsJsonObject();
        JsonArray parents = target.getAsJsonArray("parentProfileIds");

        assertEquals("world-a", target.get("world").getAsString());
        assertEquals("parent-a", parents.get(0).getAsString());
        assertEquals("parent-z", parents.get(1).getAsString());
    }

    @Test
    void compatibilityRequestOmitsPairMetadataWithoutInventingIt() {
        BreedingBirthPlanSnapshot plan = plan();
        BreedingPopulationAdmissionRequest request = new BreedingPopulationAdmissionRequest(
                "world-a",
                4,
                7,
                List.of(new BreedingPopulationAdmissionRequest.PlannedChild(
                        "child-0", null, null
                )),
                1,
                false,
                "breeding:legacy",
                plan
        );
        BreedingPopulationAdmissionUnitFactory.PreparedUnits prepared =
                new BreedingPopulationAdmissionUnitFactory().build(
                        request,
                        1,
                        new ClaimChunkCoordinate("world-a", 4, 7),
                        policy(), freshBaselineResolver(), false
                );
        JsonObject target = JsonParser.parseString(
                prepared.units().get(0).ownerPlan().targetContextJson()
        ).getAsJsonObject();

        assertFalse(request.hasCanonicalParentPair());
        assertFalse(target.has("parentProfileIds"));
        assertEquals("world-a", target.get("world").getAsString());
    }

    @Test
    void exactReplayBuildsFromTheRetainedRevisionZeroBaseline() {
        BreedingPopulationAdmissionRequest request = request(List.of("parent-a", "parent-z"));
        ClaimChunkCoordinate destination = new ClaimChunkCoordinate("world-a", 4, 7);
        String profileId = BreedingAdmissionIdentity.profileId(
                request.idempotencyKey(), "child-0"
        );
        UUID plannedNpcUuid = BreedingAdmissionIdentity.npcUuid(
                request.idempotencyKey(), "child-0"
        );
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                profileId, null, "world-a", CompanionLifecycleState.ACTIVE, 0L
        );
        ClaimOccupancyEntry claim = new ClaimOccupancyEntry(
                profileId, null, CompanionLifecycleState.ACTIVE, destination, 0L
        );
        OwnerPopulationIndex owners = new OwnerPopulationIndex();
        ClaimOccupancyIndex claims = new ClaimOccupancyIndex();
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        owners.reconcileCommittedEntry(owner);
        claims.reconcileCommittedEntry(claim);
        identities.remap(profileId, null, plannedNpcUuid);

        BreedingPopulationAdmissionUnitFactory.PreparedUnits prepared =
                new BreedingPopulationAdmissionUnitFactory().build(
                        request, 1, destination, policy(),
                        new BreedingPopulationRetryBaselineResolver(owners, claims, identities),
                        true
                );

        CompanionPopulationAdmissionUnit unit = prepared.units().get(0);
        assertEquals(0L, unit.ownerPlan().transition().expectedRevision());
        assertEquals(0L, unit.ownerPlan().baselineState().revision());
        assertEquals("breeding_retry", unit.ownerPlan().source());
        assertEquals(claim, unit.claimRequest().transitions().get(0).expected());
        assertEquals(1L, unit.claimRequest().transitions().get(0).proposed().revision());
    }

    private static BreedingPopulationAdmissionRequest request(List<String> parentProfileIds) {
        BreedingBirthPlanSnapshot plan = plan();
        return new BreedingPopulationAdmissionRequest(
                "world-a",
                4,
                7,
                List.of(new BreedingPopulationAdmissionRequest.PlannedChild(
                        "child-0", null, null
                )),
                1,
                false,
                "breeding:request-test",
                plan,
                parentProfileIds
        );
    }

    private static BreedingBirthPlanSnapshot plan() {
        return new BreedingBirthPlanSnapshot(
                1.0,
                1.0,
                1.0,
                1,
                List.of(new BreedingBirthPlanSnapshot.PlannedChild(
                        "child-0",
                        "role-baby",
                        0,
                        "role-adult",
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        "family"
                ))
        );
    }

    private static CompanionAdmissionPolicyResolver.Policy policy() {
        ClaimPolicyContext claimContext = new ClaimPolicyContext(
                "Off",
                ClaimIntegrationProvider.OFF,
                ClaimIntegrationProvider.OFF,
                "off",
                ClaimProviderState.OFF,
                Set.of(),
                null,
                "test",
                ClaimProviderGeneration.NONE,
                1L,
                null
        );
        return new CompanionAdmissionPolicyResolver.Policy(
                0,
                OwnerPopulationLimitScope.GLOBAL,
                1L,
                0,
                0,
                false,
                claimContext
        );
    }

    private static BreedingPopulationRetryBaselineResolver freshBaselineResolver() {
        return new BreedingPopulationRetryBaselineResolver(
                new OwnerPopulationIndex(),
                new ClaimOccupancyIndex(),
                new CompanionIdentityResolver()
        );
    }
}
