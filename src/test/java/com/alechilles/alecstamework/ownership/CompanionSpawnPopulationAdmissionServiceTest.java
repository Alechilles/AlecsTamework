package com.alechilles.alecstamework.ownership;

import com.alechilles.alecstamework.integration.claims.ClaimChunkCoordinate;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Canonical-source and idempotent-planning regressions for every prepared spawn caller. */
class CompanionSpawnPopulationAdmissionServiceTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID PREVIOUS = UUID.fromString("00000000-0000-0000-0000-000000000201");

    @Test
    void exactRetryUsesTheSamePlannedProfileAndNpcIdentity() {
        CompanionSpawnAdmissionRequest first = fresh("same-key", 0);
        CompanionSpawnAdmissionRequest retry = fresh("same-key", 0);

        assertEquals(
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(first, "profile"),
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(retry, "profile")
        );
        assertEquals(
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(first, "npc"),
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(retry, "npc")
        );
    }

    @Test
    void differentKeyOrRequestCannotAliasAPlannedIdentity() {
        CompanionSpawnAdmissionRequest first = fresh("first-key", 0);
        CompanionSpawnAdmissionRequest differentKey = fresh("second-key", 0);
        CompanionSpawnAdmissionRequest differentRequest = fresh("first-key", 1);

        assertNotEquals(
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(first, "npc"),
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(differentKey, "npc")
        );
        assertNotEquals(
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(first, "npc"),
                CompanionSpawnPopulationAdmissionService.deterministicIdentity(differentRequest, "npc")
        );
    }

    @Test
    void duplicateActiveCanonicalRepresentationIsRejected() {
        assertEquals(
                "spawn-source-duplicate-active-profile",
                validate(PREVIOUS, CompanionLifecycleState.ACTIVE, CompanionLifecycleState.ACTIVE)
        );
    }

    @Test
    void exactDormantLifecycleAndRevisionPairIsAccepted() {
        assertNull(validate(PREVIOUS, CompanionLifecycleState.CAPTURED, CompanionLifecycleState.CAPTURED));
    }

    @Test
    void provisionedNullNpcProfileCanEnterTheNormalRestorePipelineWithoutAClaimBaseline() {
        CompanionSpawnAdmissionRequest request = new CompanionSpawnAdmissionRequest(
                "profile", null, CompanionLifecycleState.PROVISIONED_DORMANT, false,
                OWNER, null, "world", 2, -4, OwnerPopulationOperation.RESTORE,
                "companion_provisioning", "provisioning:activate", false);
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "profile", OWNER, "world", CompanionLifecycleState.PROVISIONED_DORMANT, 1L);

        assertTrue(request.replacement());
        assertTrue(request.canonicalNullNpcRestore());
        assertNull(CompanionSpawnPopulationAdmissionService.validateDormantSource(
                null, null, CompanionLifecycleState.PROVISIONED_DORMANT, owner, null, false));
        assertEquals("spawn-source-duplicate-active-profile",
                CompanionSpawnPopulationAdmissionService.validateDormantSource(
                        null, PREVIOUS, CompanionLifecycleState.PROVISIONED_DORMANT,
                        owner, null, false));
    }

    @Test
    void lifecycleOrRevisionMismatchFailsClosed() {
        assertEquals(
                "spawn-source-lifecycle-mismatch",
                validate(PREVIOUS, CompanionLifecycleState.CAPTURED, CompanionLifecycleState.LOST)
        );
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "profile", OWNER, "world", CompanionLifecycleState.CAPTURED, 7L
        );
        ClaimOccupancyEntry claim = new ClaimOccupancyEntry(
                "profile", OWNER, CompanionLifecycleState.CAPTURED,
                new ClaimChunkCoordinate("world", 0, 0), 8L
        );
        assertEquals(
                "spawn-source-population-state-mismatch",
                CompanionSpawnPopulationAdmissionService.validateDormantSource(
                        PREVIOUS, PREVIOUS, CompanionLifecycleState.CAPTURED, owner, claim, false
                )
        );
    }

    @Test
    void legacyAdoptionAcceptsOnlyAnEmptyPopulationProjection() {
        assertNull(CompanionSpawnPopulationAdmissionService.validateDormantSource(
                PREVIOUS, PREVIOUS, CompanionLifecycleState.CAPTURED, null, null, true
        ));
    }

    @Test
    void restoreCannotSilentlyTransferOrClearThePersistedOwner() {
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "profile", OWNER, "world", CompanionLifecycleState.CAPTURED, 7L
        );

        assertEquals(
                "spawn-source-owner-mismatch",
                CompanionSpawnPopulationAdmissionService.validateRequestedOwner(
                        owner, UUID.randomUUID(), OwnerPopulationOperation.RESTORE, false
                )
        );
        assertEquals(
                "spawn-source-owner-mismatch",
                CompanionSpawnPopulationAdmissionService.validateRequestedOwner(
                        owner, null, OwnerPopulationOperation.RESTORE, false
                )
        );
        assertNull(CompanionSpawnPopulationAdmissionService.validateRequestedOwner(
                owner, OWNER, OwnerPopulationOperation.RESTORE, false
        ));
    }

    @Test
    void canonicalUnownedRestoreMayAcquireAnOwnerThroughNormalAdmission() {
        OwnerPopulationEntry unowned = new OwnerPopulationEntry(
                "profile", null, null, CompanionLifecycleState.CAPTURED, 7L
        );

        assertNull(CompanionSpawnPopulationAdmissionService.validateRequestedOwner(
                unowned, OWNER, OwnerPopulationOperation.RESTORE, false
        ));
        assertNull(CompanionSpawnPopulationAdmissionService.validateRequestedOwner(
                unowned, null, OwnerPopulationOperation.RESTORE, false
        ));
    }

    @Test
    void asymmetricClaimFailureStillMarksTheOwnerCommittedIdentityDurable() {
        OwnerPopulationCommitResult ownerCommit = new OwnerPopulationCommitResult(
                OwnerPopulationCommitResult.Status.COMMITTED, "owner-committed", null
        );
        CompanionPopulationCommitResult result = new CompanionPopulationCommitResult(
                false, "companion-claim-index-commit-failed", false, ownerCommit
        );

        assertTrue(CompanionSpawnPopulationAdmissionService.shouldMarkIdentityDurable(result));
    }

    /** Regression: a claimed command batch must expose its planned identity before ECS spawn. */
    @Test
    void claimRetainsPreparedAliasBeforeThePopulationCapabilityIsApplied() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ownership/CompanionSpawnPopulationAdmissionService.java"
        ));
        int retain = source.indexOf("identityResolver.retainPreparedAlias(");
        int claim = source.indexOf("batchCoordinator.claimForApply(");

        assertTrue(retain >= 0);
        assertTrue(claim > retain);
    }

    private static String validate(UUID current, CompanionLifecycleState ownerState,
                                   CompanionLifecycleState claimState) {
        OwnerPopulationEntry owner = new OwnerPopulationEntry(
                "profile", OWNER, "world", ownerState, 7L
        );
        ClaimOccupancyEntry claim = new ClaimOccupancyEntry(
                "profile", OWNER, claimState, new ClaimChunkCoordinate("world", 0, 0), 7L
        );
        return CompanionSpawnPopulationAdmissionService.validateDormantSource(
                PREVIOUS, current, CompanionLifecycleState.CAPTURED, owner, claim, false
        );
    }

    private static CompanionSpawnAdmissionRequest fresh(String key, int chunkX) {
        return new CompanionSpawnAdmissionRequest(
                null, null, null, false, OWNER, "Owner", "world", chunkX, 0,
                OwnerPopulationOperation.NEW_OWNERSHIP, "test", key, false
        );
    }
}
