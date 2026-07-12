package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
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
                        policy()
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
                        policy()
                );
        JsonObject target = JsonParser.parseString(
                prepared.units().get(0).ownerPlan().targetContextJson()
        ).getAsJsonObject();

        assertFalse(request.hasCanonicalParentPair());
        assertFalse(target.has("parentProfileIds"));
        assertEquals("world-a", target.get("world").getAsString());
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
}
