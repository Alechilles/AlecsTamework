package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.VanillaResidentImportPlanner.Classification.CONFLICT;
import static com.alechilles.alecstamework.items.VanillaResidentImportPlanner.Classification.IMPORTABLE;
import static com.alechilles.alecstamework.items.VanillaResidentImportPlanner.Classification.MATCH_EXISTING;
import static com.alechilles.alecstamework.items.VanillaResidentImportPlanner.Classification.OVERFLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure regression coverage for deterministic report-only vanilla resident classification. */
class VanillaResidentImportPlannerTest {
    private static final VanillaResidentImportPlanner.CoopAuthority AUTHORITY =
            new VanillaResidentImportPlanner.CoopAuthority(
                    "world|1|2|3", "world", "Coop_Chicken", 1, 2, 3, 3);

    private final VanillaResidentImportPlanner planner = new VanillaResidentImportPlanner();

    @Test
    void exactUuidAndProfileMatchWhileDistinctSourcesReceiveDeterministicFreeSlots() {
        VanillaResidentImportPlanner.ManagedResidentEvidence managed = managed(
                "resident-a", 1, "profile-a", uuid(1));
        VanillaResidentImportPlanner.VanillaResidentEvidence matching = vanilla(
                "fingerprint-match", "RAW_MATCH", 1, 0, uuid(1), "profile-a", "Mob_Chicken", "Same");
        VanillaResidentImportPlanner.VanillaResidentEvidence preferred = vanilla(
                "fingerprint-preferred", "RAW_PREFERRED", 2, 1, uuid(2), "profile-b", "Mob_Chicken", "Same");
        VanillaResidentImportPlanner.VanillaResidentEvidence fallback = vanilla(
                "fingerprint-fallback", "RAW_FALLBACK", 0, 2, null, null, "Mob_Chicken", "Same");

        VanillaResidentImportPlanner.ImportPlan plan = planner.plan(new VanillaResidentImportPlanner.ImportRequest(
                AUTHORITY, List.of(managed), List.of(fallback, matching, preferred)));

        assertEquals(List.of("fingerprint-match", "fingerprint-preferred", "fingerprint-fallback"),
                plan.decisions().stream().map(decision -> decision.source().sourceFingerprint()).toList());
        assertEquals(MATCH_EXISTING, plan.decisions().get(0).classification());
        assertEquals("resident-a", plan.decisions().get(0).matchedResidentId());
        assertEquals(1, plan.decisions().get(0).targetSlot());
        assertEquals(IMPORTABLE, plan.decisions().get(1).classification());
        assertEquals(2, plan.decisions().get(1).targetSlot());
        assertEquals(IMPORTABLE, plan.decisions().get(2).classification());
        assertEquals(0, plan.decisions().get(2).targetSlot());
        assertFalse(plan.hasConflicts());
        assertFalse(plan.isOverCapacity());
    }

    @Test
    void capacityOverflowRemainsAVisibleDecisionWithUnchangedRawEvidence() {
        VanillaResidentImportPlanner.CoopAuthority oneSlot =
                new VanillaResidentImportPlanner.CoopAuthority(
                        "world|1|2|3", "world", "Coop_Chicken", 1, 2, 3, 1);
        VanillaResidentImportPlanner.VanillaResidentEvidence first = vanilla(
                "a", "{\"raw\":\"first\"}", 0, 0, uuid(10), null, null, null);
        VanillaResidentImportPlanner.VanillaResidentEvidence second = vanilla(
                "b", "  exact raw payload  ", 1, 1, uuid(11), null, null, null);

        VanillaResidentImportPlanner.ImportPlan plan = planner.plan(new VanillaResidentImportPlanner.ImportRequest(
                oneSlot, List.of(), List.of(second, first)));

        assertEquals(IMPORTABLE, plan.decisions().get(0).classification());
        assertEquals(OVERFLOW, plan.decisions().get(1).classification());
        assertSame(second, plan.decisions().get(1).source());
        assertEquals("  exact raw payload  ", plan.decisions().get(1).source().sourcePayload());
        assertTrue(plan.isOverCapacity());
    }

    @Test
    void duplicateVanillaIdentityEvidenceFailsClosedWithoutDiscardingEitherSource() {
        UUID duplicateUuid = uuid(20);
        VanillaResidentImportPlanner.VanillaResidentEvidence first = vanilla(
                "duplicate", "payload-a", 0, 0, duplicateUuid, "profile-x", null, null);
        VanillaResidentImportPlanner.VanillaResidentEvidence second = vanilla(
                "duplicate", "payload-b", 0, 0, duplicateUuid, "profile-x", null, null);
        VanillaResidentImportPlanner.VanillaResidentEvidence distinct = vanilla(
                "distinct", "payload-c", 1, 1, uuid(21), "profile-y", null, null);

        VanillaResidentImportPlanner.ImportPlan plan = planner.plan(new VanillaResidentImportPlanner.ImportRequest(
                AUTHORITY, List.of(), List.of(second, distinct, first)));

        assertEquals(3, plan.decisions().size());
        assertEquals(2, plan.count(CONFLICT));
        assertEquals(1, plan.count(IMPORTABLE));
        for (VanillaResidentImportPlanner.Decision decision : plan.decisions()) {
            if (decision.classification() != CONFLICT) {
                continue;
            }
            assertTrue(decision.reasons().contains(
                    VanillaResidentImportPlanner.Reason.DUPLICATE_SOURCE_FINGERPRINT));
            assertTrue(decision.reasons().contains(
                    VanillaResidentImportPlanner.Reason.DUPLICATE_PERSISTENT_UUID));
            assertTrue(decision.reasons().contains(
                    VanillaResidentImportPlanner.Reason.DUPLICATE_RESOLVED_PROFILE));
        }
    }

    @Test
    void uuidAndProfileResolvingToDifferentManagedResidentsIsAConflict() {
        VanillaResidentImportPlanner.ManagedResidentEvidence first = managed(
                "resident-a", 0, "profile-a", uuid(30));
        VanillaResidentImportPlanner.ManagedResidentEvidence second = managed(
                "resident-b", 1, "profile-b", uuid(31));
        VanillaResidentImportPlanner.VanillaResidentEvidence conflicting = vanilla(
                "identity-split", "raw", 2, 0, uuid(30), "profile-b", null, null);

        VanillaResidentImportPlanner.Decision decision = planner.plan(
                new VanillaResidentImportPlanner.ImportRequest(
                        AUTHORITY, List.of(first, second), List.of(conflicting)))
                .decisions().getFirst();

        assertEquals(CONFLICT, decision.classification());
        assertTrue(decision.reasons().contains(
                VanillaResidentImportPlanner.Reason.MANAGED_UUID_PROFILE_MISMATCH));
    }

    @Test
    void matchingRoleAndNameNeverSubstituteForExactIdentityEvidence() {
        VanillaResidentImportPlanner.ManagedResidentEvidence managed = managed(
                "resident-a", 0, "profile-a", uuid(40));
        VanillaResidentImportPlanner.VanillaResidentEvidence samePresentation = vanilla(
                "presentation-only", "raw", 1, 0, null, null, "Mob_Chicken", "Clucky");

        VanillaResidentImportPlanner.Decision decision = planner.plan(
                new VanillaResidentImportPlanner.ImportRequest(
                        AUTHORITY, List.of(managed), List.of(samePresentation)))
                .decisions().getFirst();

        assertEquals(IMPORTABLE, decision.classification());
        assertEquals(1, decision.targetSlot());
    }

    @Test
    void managedDuplicateIdentityFailsEveryVanillaSourceClosed() {
        UUID duplicate = uuid(50);
        VanillaResidentImportPlanner.ManagedResidentEvidence first = managed(
                "resident-a", 0, "profile-a", duplicate);
        VanillaResidentImportPlanner.ManagedResidentEvidence second = managed(
                "resident-b", 1, "profile-b", duplicate);
        VanillaResidentImportPlanner.VanillaResidentEvidence source = vanilla(
                "source", "raw", 2, 0, uuid(51), null, null, null);

        VanillaResidentImportPlanner.Decision decision = planner.plan(
                new VanillaResidentImportPlanner.ImportRequest(
                        AUTHORITY, List.of(first, second), List.of(source)))
                .decisions().getFirst();

        assertEquals(CONFLICT, decision.classification());
        assertTrue(decision.reasons().contains(
                VanillaResidentImportPlanner.Reason.MANAGED_DUPLICATE_UUID));
    }

    @Test
    void inputAndOutputCollectionsAreImmutableAndInputOrderDoesNotChangePlan() {
        VanillaResidentImportPlanner.VanillaResidentEvidence a = vanilla(
                "a", "raw-a", 2, 2, uuid(60), null, null, null);
        VanillaResidentImportPlanner.VanillaResidentEvidence b = vanilla(
                "b", "raw-b", 0, 0, uuid(61), null, null, null);
        ArrayList<VanillaResidentImportPlanner.VanillaResidentEvidence> mutable =
                new ArrayList<>(List.of(a, b));
        VanillaResidentImportPlanner.ImportRequest request =
                new VanillaResidentImportPlanner.ImportRequest(AUTHORITY, List.of(), mutable);
        mutable.clear();

        VanillaResidentImportPlanner.ImportPlan first = planner.plan(request);
        VanillaResidentImportPlanner.ImportPlan second = planner.plan(
                new VanillaResidentImportPlanner.ImportRequest(AUTHORITY, List.of(), List.of(b, a)));

        assertEquals(first.decisions(), second.decisions());
        assertThrows(UnsupportedOperationException.class, () -> first.decisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> request.vanillaResidents().clear());
    }

    @Test
    void reportOnlyPlannerHasNoVanillaRuntimeOrReflectionDependency() throws Exception {
        String source = Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items",
                "VanillaResidentImportPlanner.java"));

        assertFalse(source.contains("com.hypixel"));
        assertFalse(source.contains("CoopBlock"));
        assertFalse(source.contains("java.lang.reflect"));
        assertFalse(source.contains("setAccessible"));
    }

    private VanillaResidentImportPlanner.ManagedResidentEvidence managed(
            String residentId,
            int slot,
            String profileId,
            UUID residentUuid) {
        return new VanillaResidentImportPlanner.ManagedResidentEvidence(
                residentId,
                AUTHORITY.authorityId(),
                AUTHORITY.coopId(),
                slot,
                profileId,
                residentUuid,
                null,
                null
        );
    }

    private VanillaResidentImportPlanner.VanillaResidentEvidence vanilla(
            String fingerprint,
            String payload,
            Integer slot,
            int order,
            UUID persistentUuid,
            String profileId,
            String roleId,
            String displayName) {
        return new VanillaResidentImportPlanner.VanillaResidentEvidence(
                fingerprint, payload, slot, order, persistentUuid, profileId, roleId, displayName);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
