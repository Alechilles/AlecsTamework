package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimAdmissionOperation;
import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimIntegrationProvider;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyTransition;
import com.alechilles.alecstamework.integration.claims.ClaimPolicyContext;
import com.alechilles.alecstamework.integration.claims.ClaimProviderGeneration;
import com.alechilles.alecstamework.integration.claims.ClaimProviderState;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.CaptureRequest;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.PlanResult;
import com.alechilles.alecstamework.ownership.CoopPopulationCaptureAdmissionService.SourceKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the H1 split-commit bug: capture admission must describe one exact
 * canonical source and carry the schema-v5 mutation into the shared population transaction.
 */
class CoopPopulationCaptureAdmissionServiceTest {
    private static final String PROFILE = "capture-profile";
    private static final UUID SOURCE = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OWNER = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final ClaimChunkCoordinate SOURCE_CHUNK =
            new ClaimChunkCoordinate("source-world", 4, -3);

    @Test
    void liveActiveAndUnloadedCaptureKeepOwnerSlotAndRemoveOneClaimOccupancy() {
        for (CompanionLifecycleState sourceState : new CompanionLifecycleState[]{
                CompanionLifecycleState.ACTIVE,
                CompanionLifecycleState.UNLOADED
        }) {
            AtomicReference<String> factoryProfile = new AtomicReference<>();
            PlanResult plan = plan(
                    liveRequest(),
                    owner(OWNER, sourceState, 7L),
                    claim(OWNER, sourceState, SOURCE_CHUNK, 7L),
                    profileId -> {
                        factoryProfile.set(profileId);
                        return v5Context("capture-operation");
                    }
            );

            assertTrue(plan.allowed(), plan.reason());
            assertEquals(PROFILE, factoryProfile.get());
            OwnerPopulationTransitionRequest ownerTransition = plan.ownerPlan().transition();
            assertEquals(OWNER, ownerTransition.expectedOwnerId());
            assertEquals(OWNER, ownerTransition.newOwnerId());
            assertEquals("ownership-world", ownerTransition.sourceWorldName());
            assertEquals("ownership-world", ownerTransition.destinationWorldName());
            assertEquals(CompanionLifecycleState.COOP, ownerTransition.lifecycleState());
            assertEquals(OwnerPopulationOperation.LIFECYCLE_CHANGE, ownerTransition.operation());

            ClaimOccupancyTransition claimTransition =
                    plan.claimRequest().transitions().getFirst();
            assertTrue(claimTransition.expected().occupiesClaim());
            assertFalse(claimTransition.proposed().occupiesClaim());
            assertEquals(CompanionLifecycleState.COOP,
                    claimTransition.proposed().lifecycleState());
            assertNull(claimTransition.proposed().physicalChunk());
            assertEquals(ClaimAdmissionOperation.COOP_CAPTURE,
                    plan.claimRequest().operation());
            assertNull(plan.claimRequest().destinationChunk());

            assertEquals("source-world", plan.ownerPlan().baselineState().physicalWorldName());
            assertEquals(4, plan.ownerPlan().baselineState().physicalChunkX());
            assertEquals(-3, plan.ownerPlan().baselineState().physicalChunkZ());
            assertNull(plan.ownerPlan().finalPhysicalWorldName());
            assertNull(plan.ownerPlan().finalPhysicalChunkX());
            assertNull(plan.ownerPlan().finalPhysicalChunkZ());
            assertV5Context(plan.ownerPlan().targetContextJson(), "live_entity");
        }
    }

    @Test
    void capturedItemCaptureKeepsBothOwnerAndClaimDeltasAtZero() {
        ClaimChunkCoordinate retainedRecoveryLocation =
                new ClaimChunkCoordinate("old-world", 8, 9);
        PlanResult plan = plan(
                itemRequest(),
                owner(OWNER, CompanionLifecycleState.CAPTURED, 12L),
                claim(OWNER, CompanionLifecycleState.CAPTURED,
                        retainedRecoveryLocation, 12L),
                ignored -> v5Context("item-operation")
        );

        assertTrue(plan.allowed(), plan.reason());
        OwnerPopulationTransitionRequest ownerTransition = plan.ownerPlan().transition();
        assertEquals(ownerTransition.expectedOwnerId(), ownerTransition.newOwnerId());
        ClaimOccupancyTransition claimTransition = plan.claimRequest().transitions().getFirst();
        assertFalse(claimTransition.expected().occupiesClaim());
        assertFalse(claimTransition.proposed().occupiesClaim());
        assertNull(claimTransition.proposed().physicalChunk());
        assertV5Context(plan.ownerPlan().targetContextJson(), "captured_item");
        JsonObject context = JsonParser.parseString(
                plan.ownerPlan().targetContextJson()).getAsJsonObject();
        assertFalse(context.has("world"));
        assertFalse(context.has("chunkX"));
        assertFalse(context.has("chunkZ"));
    }

    @Test
    void newlyEnsuredUnownedWildProfileUsesRevisionZeroBaselineAndZeroDeltas() {
        PlanResult plan = plan(
                newlyEnsuredWildRequest(),
                null,
                null,
                ignored -> v5Context("new-wild-operation")
        );

        assertTrue(plan.allowed(), plan.reason());
        OwnerPopulationTransitionRequest ownerTransition = plan.ownerPlan().transition();
        assertEquals(OwnerPopulationTransitionRequest.NEW_PROFILE_REVISION,
                ownerTransition.expectedRevision());
        assertNull(ownerTransition.expectedOwnerId());
        assertNull(ownerTransition.sourceWorldName());
        assertNull(ownerTransition.newOwnerId());
        assertNull(ownerTransition.destinationWorldName());
        assertEquals(CompanionLifecycleState.COOP, ownerTransition.lifecycleState());

        assertEquals(0L, plan.ownerPlan().baselineState().revision());
        assertNull(plan.ownerPlan().baselineState().ownerUuid());
        assertEquals(CompanionLifecycleState.ACTIVE.name(),
                plan.ownerPlan().baselineState().lifecycleState());
        assertEquals("source-world", plan.ownerPlan().baselineState().physicalWorldName());
        assertEquals(4, plan.ownerPlan().baselineState().physicalChunkX());
        assertEquals(-3, plan.ownerPlan().baselineState().physicalChunkZ());

        ClaimOccupancyTransition claimTransition =
                plan.claimRequest().transitions().getFirst();
        assertNull(claimTransition.expected());
        assertNull(claimTransition.proposed().ownerId());
        assertEquals(1L, claimTransition.proposed().revision());
        assertFalse(claimTransition.proposed().occupiesClaim());
        JsonObject context = JsonParser.parseString(
                plan.ownerPlan().targetContextJson()).getAsJsonObject();
        assertTrue(context.get("newlyEnsuredUnownedProfile").getAsBoolean());
    }

    @Test
    void canonicalIdentityOwnerAndRevisionMismatchesFailClosed() {
        CaptureRequest request = liveRequest();
        OwnerPopulationEntry owner = owner(OWNER, CompanionLifecycleState.ACTIVE, 7L);
        ClaimOccupancyEntry claim = claim(
                OWNER, CompanionLifecycleState.ACTIVE, SOURCE_CHUNK, 7L);

        assertEquals("coop-capture-canonical-identity-unavailable",
                validate(request, null, SOURCE, owner, claim));
        assertEquals("coop-capture-source-profile-mismatch",
                validate(request, "other-profile", SOURCE, owner, claim));
        assertEquals("coop-capture-duplicate-active-profile",
                validate(request, PROFILE, UUID.randomUUID(), owner, claim));
        assertEquals("coop-capture-owner-mismatch",
                validate(request, PROFILE, SOURCE,
                        owner(UUID.randomUUID(), CompanionLifecycleState.ACTIVE, 7L), claim));
        assertEquals("coop-capture-population-state-mismatch",
                validate(request, PROFILE, SOURCE, owner,
                        claim(OWNER, CompanionLifecycleState.ACTIVE, SOURCE_CHUNK, 8L)));
        assertEquals("coop-capture-population-profile-unavailable",
                validate(request, PROFILE, SOURCE, null, claim));
        assertEquals("coop-capture-population-profile-unavailable",
                validate(request, PROFILE, SOURCE, null, null));

        CaptureRequest newlyEnsured = newlyEnsuredWildRequest();
        assertEquals("coop-capture-new-profile-population-present",
                validate(newlyEnsured, PROFILE, SOURCE, owner, claim));
    }

    @Test
    void lifecycleAndLiveSourceLocationMismatchesFailClosed() {
        CaptureRequest live = liveRequest();
        assertEquals("coop-capture-profile-not-physical",
                validate(live, PROFILE, SOURCE,
                        owner(OWNER, CompanionLifecycleState.CAPTURED, 7L),
                        claim(OWNER, CompanionLifecycleState.CAPTURED, null, 7L)));
        assertEquals("coop-capture-source-location-mismatch",
                validate(live, PROFILE, SOURCE,
                        owner(OWNER, CompanionLifecycleState.ACTIVE, 7L),
                        claim(OWNER, CompanionLifecycleState.ACTIVE,
                                new ClaimChunkCoordinate("source-world", 5, -3), 7L)));
        assertEquals("coop-capture-population-state-mismatch",
                validate(live, PROFILE, SOURCE,
                        owner(OWNER, CompanionLifecycleState.ACTIVE, 7L),
                        claim(OWNER, CompanionLifecycleState.UNLOADED, SOURCE_CHUNK, 7L)));

        CaptureRequest item = itemRequest();
        assertEquals("coop-capture-profile-not-captured",
                validate(item, PROFILE, SOURCE,
                        owner(OWNER, CompanionLifecycleState.LOST, 7L),
                        claim(OWNER, CompanionLifecycleState.LOST, null, 7L)));
    }

    @Test
    void durableContextFactoryCannotOmitOrOverrideExactCaptureIdentity() {
        CaptureRequest request = liveRequest();
        assertThrows(IllegalArgumentException.class,
                () -> CoopPopulationCaptureAdmissionService.contextJson(request, ignored -> "{}"));
        assertThrows(IllegalArgumentException.class,
                () -> CoopPopulationCaptureAdmissionService.contextJson(
                        request, ignored -> "{\"npcUuid\":\"wrong\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureRequest(
                        PROFILE, SOURCE, OWNER, SourceKind.LIVE_ENTITY,
                        null, false, "missing-location"));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureRequest(
                        PROFILE, SOURCE, OWNER, SourceKind.CAPTURED_ITEM,
                        SOURCE_CHUNK, false, "invented-location"));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureRequest(
                        PROFILE, SOURCE, OWNER, SourceKind.LIVE_ENTITY,
                        SOURCE_CHUNK, true, "owned-new-profile"));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureRequest(
                        PROFILE, SOURCE, null, SourceKind.CAPTURED_ITEM,
                        null, true, "captured-new-profile"));
    }

    private static PlanResult plan(
            CaptureRequest request,
            OwnerPopulationEntry owner,
            ClaimOccupancyEntry claim,
            CoopPopulationCaptureAdmissionService.DurableContextFactory contextFactory) {
        return CoopPopulationCaptureAdmissionService.planResolved(
                request, PROFILE, SOURCE, owner, claim, policy(), contextFactory);
    }

    private static String validate(
            CaptureRequest request,
            String mappedProfile,
            UUID currentUuid,
            OwnerPopulationEntry owner,
            ClaimOccupancyEntry claim) {
        return CoopPopulationCaptureAdmissionService.validateSource(
                request, mappedProfile, currentUuid, owner, claim);
    }

    private static CaptureRequest liveRequest() {
        return new CaptureRequest(
                PROFILE, SOURCE, OWNER, SourceKind.LIVE_ENTITY,
                SOURCE_CHUNK, false, "live-capture-key");
    }

    private static CaptureRequest itemRequest() {
        return new CaptureRequest(
                PROFILE, SOURCE, OWNER, SourceKind.CAPTURED_ITEM,
                null, false, "item-capture-key");
    }

    private static CaptureRequest newlyEnsuredWildRequest() {
        return new CaptureRequest(
                PROFILE, SOURCE, null, SourceKind.LIVE_ENTITY,
                SOURCE_CHUNK, true, "new-wild-capture-key");
    }

    private static OwnerPopulationEntry owner(
            UUID ownerId,
            CompanionLifecycleState lifecycle,
            long revision) {
        return new OwnerPopulationEntry(
                PROFILE, ownerId, "ownership-world", lifecycle, revision);
    }

    private static ClaimOccupancyEntry claim(
            UUID ownerId,
            CompanionLifecycleState lifecycle,
            ClaimChunkCoordinate physical,
            long revision) {
        return new ClaimOccupancyEntry(
                PROFILE, ownerId, lifecycle, physical, revision);
    }

    private static CompanionAdmissionPolicyResolver.Policy policy() {
        long settingsRevision = 44L;
        ClaimPolicyContext context = new ClaimPolicyContext(
                "Off",
                ClaimIntegrationProvider.OFF,
                ClaimIntegrationProvider.OFF,
                "off",
                ClaimProviderState.OFF,
                Set.of(),
                null,
                "capture transition is non-positive",
                ClaimProviderGeneration.NONE,
                settingsRevision,
                null
        );
        return new CompanionAdmissionPolicyResolver.Policy(
                10,
                OwnerPopulationLimitScope.GLOBAL,
                settingsRevision,
                0,
                0,
                false,
                context
        );
    }

    private static String v5Context(String operationId) {
        return "{\"managedCoopCapture\":{\"operationId\":\"" + operationId + "\"}}";
    }

    private static void assertV5Context(String raw, String sourceKind) {
        JsonObject context = JsonParser.parseString(raw).getAsJsonObject();
        assertEquals("managed_coop_capture", context.get("operation").getAsString());
        assertEquals(PROFILE, context.get("profileId").getAsString());
        assertEquals(SOURCE.toString(), context.get("npcUuid").getAsString());
        assertEquals(sourceKind, context.get("sourceKind").getAsString());
        assertFalse(context.get("newlyEnsuredUnownedProfile").getAsBoolean());
        assertTrue(context.get("managedCoopCapture").isJsonObject());
    }
}
