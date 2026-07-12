package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.ALLOW;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.DEFER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.IGNORE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.SUPPRESS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ACTIVE_CAPTURE_SOURCE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ACTIVE_RELEASE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.AUTHORITY_NOT_CURRENTLY_MANAGED;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.CONFLICTING_EVIDENCE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.DEPLOYED_IMPORT_ADOPTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.DEPLOYED_RELEASE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.DEPLOYED_IDENTITY_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.HISTORICAL_RESIDENT_ALIAS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.HOUSED_ALIAS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.INVALID_CAPTURE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.INVALID_DEPLOYED_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.INVALID_RELEASE_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ORPHAN_MANAGED_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.BLOCKED_RESIDENT_STATE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_NOT_SPAWN_VISIBLE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_OPERATION_MISSING;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_TARGET_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_RESIDENT_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RESIDENT_IDENTITY_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNRELATED_NPC;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNTRUSTED_COMPOSITE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNSUPPORTED_OPERATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exhaustive decision coverage for stale managed-coop source and projection aliases. */
class ManagedCoopStaleEntityPolicyTest {
    private static final ManagedCoopAuthorityKey COOP =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);
    private static final ManagedCoopAuthorityKey OTHER_COOP =
            new ManagedCoopAuthorityKey("world", 8, 9, 10);
    private static final String COOP_ID = "coop_chicken";
    private static final String PROFILE = "profile-a";
    private static final String RELEASE_ID = operationId('a');
    private static final String HASH = "b".repeat(64);
    private static final UUID SOURCE = uuid(1L);
    private static final UUID TARGET = uuid(2L);

    @Test
    void unrelatedNpcIsIgnoredEvenWhenCompositeTrustIsUnavailable() {
        Fixture fixture = fixture(List.of(), List.of(), false);

        ManagedCoopStaleEntityPolicy.Decision decision = fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(uuid(99L), null)
        );

        assertDecision(decision, IGNORE, UNRELATED_NPC);
    }

    @Test
    void validDeployedProjectionDefersWhenCompositeIsUntrusted() {
        ResidentRecord deployed = resident(
                COOP, PROFILE, TARGET, SOURCE, TARGET, ResidentState.DEPLOYED, 2L);
        Fixture fixture = fixture(List.of(deployed), List.of(), false);

        ManagedCoopStaleEntityPolicy.Decision decision = fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, releaseMarker(RELEASE_ID))
        );

        assertDecision(decision, DEFER, UNTRUSTED_COMPOSITE);
    }

    @Test
    void housedAndActiveCaptureSourceAliasesAreAlwaysSuppressed() {
        ResidentRecord housed = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.HOUSED, 0L);
        Fixture housedFixture = fixture(List.of(housed), List.of(), true);
        OperationRecord capture = capture(
                "managed-coop-capture:" + "c".repeat(64),
                PROFILE,
                COOP,
                SOURCE,
                OperationState.SOURCE_RETIRE_REQUESTED,
                2L
        );
        Fixture captureFixture = fixture(List.of(), List.of(capture), true);

        assertDecision(housedFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), SUPPRESS, HOUSED_ALIAS);
        assertDecision(housedFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, releaseMarker(RELEASE_ID))
        ), DEFER, RESIDENT_IDENTITY_MISMATCH);
        assertDecision(captureFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), SUPPRESS, ACTIVE_CAPTURE_SOURCE);
    }

    @Test
    void mappedNpcDefersWhenExactAuthorityIsNoLongerCurrentlyManaged() {
        ResidentRecord housed = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.HOUSED, 0L);
        Fixture fixture = fixture(List.of(housed), List.of(), true);
        fixture.authorityEligibility().invalidateWorld(COOP.worldName());

        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), DEFER, AUTHORITY_NOT_CURRENTLY_MANAGED);

        fixture.authorityEligibility().replaceWorld(
                COOP.worldName(),
                List.of(new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        OTHER_COOP, COOP_ID)));
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), DEFER, AUTHORITY_NOT_CURRENTLY_MANAGED);

        fixture.authorityEligibility().replaceWorld(
                COOP.worldName(),
                List.of(new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                        COOP, "coop_duck")));
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), DEFER, AUTHORITY_NOT_CURRENTLY_MANAGED);

        ResidentRecord deployed = resident(
                COOP, PROFILE, TARGET, SOURCE, TARGET, ResidentState.DEPLOYED, 2L);
        Fixture deployedFixture = fixture(List.of(deployed), List.of(), true);
        deployedFixture.authorityEligibility().invalidateWorld(COOP.worldName());
        assertDecision(deployedFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET, releaseMarker(RELEASE_ID))
        ), DEFER, AUTHORITY_NOT_CURRENTLY_MANAGED);
    }

    @Test
    void exactSpawnClaimedAndProjectionCreatedReleaseTargetsAreAllowed() {
        ResidentRecord releasing = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.RELEASING, 1L);
        for (OperationRecord release : List.of(
                release(OperationState.SPAWN_CLAIMED, 1L, null),
                release(OperationState.PROJECTION_CREATED, 2L, TARGET)
        )) {
            Fixture fixture = fixture(List.of(releasing), List.of(release), true);
            ManagedCoopStaleEntityPolicy.Decision decision = fixture.policy().decide(
                    ManagedCoopStaleEntityPolicy.Observation.of(TARGET, releaseMarker(RELEASE_ID))
            );

            assertDecision(decision, ALLOW, ACTIVE_RELEASE_PROJECTION);
        }
    }

    @Test
    void releaseTargetRequiresEveryExactMarkerIdentityField() {
        ResidentRecord releasing = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.RELEASING, 1L);
        Fixture fixture = fixture(
                List.of(releasing),
                List.of(release(OperationState.SPAWN_CLAIMED, 1L, null)),
                true
        );
        List<ManagedCoopStaleEntityPolicy.MarkerEvidence> invalidMarkers = List.of(
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        "profile-b", RELEASE_ID, releaseKind(), slotKey(), SOURCE, 1L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId('d'), releaseKind(), slotKey(), SOURCE, 1L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, RELEASE_ID, "RECOVERY", slotKey(), SOURCE, 1L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, RELEASE_ID, releaseKind(), OTHER_COOP.slotKey(0), SOURCE, 1L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, RELEASE_ID, releaseKind(), slotKey(), uuid(77L), 1L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, RELEASE_ID, releaseKind(), slotKey(), SOURCE, 2L)
        );

        for (ManagedCoopStaleEntityPolicy.MarkerEvidence marker : invalidMarkers) {
            assertDecision(fixture.policy().decide(
                    ManagedCoopStaleEntityPolicy.Observation.of(TARGET, marker)
            ), DEFER, INVALID_RELEASE_MARKER);
        }
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, releaseMarker(RELEASE_ID))
        ), SUPPRESS, HISTORICAL_RESIDENT_ALIAS);
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(uuid(78L), releaseMarker(RELEASE_ID))
        ), DEFER, RELEASE_TARGET_MISMATCH);

        OperationRecord sourceAliasingRelease = new OperationRecord(
                RELEASE_ID, OperationKind.RELEASE, PROFILE, COOP, COOP_ID, 0,
                null, SOURCE, null, OperationState.SPAWN_CLAIMED, HASH,
                0L, 1L, 0, true, -100L, -90L, 0L, null
        );
        Fixture sourceAliasFixture = fixture(
                List.of(releasing),
                List.of(sourceAliasingRelease),
                true
        );
        assertDecision(sourceAliasFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, releaseMarker(RELEASE_ID))
        ), DEFER, RELEASE_RESIDENT_MISMATCH);
    }

    @Test
    void finalizedDeploymentUsesResidentAndMarkerShapeWithoutDerivingOperationId() {
        ResidentRecord deployed = resident(
                COOP, PROFILE, TARGET, SOURCE, TARGET, ResidentState.DEPLOYED, 2L);
        Fixture fixture = fixture(List.of(deployed), List.of(), true);
        String retainedMarkerOperationId = operationId('f');

        ManagedCoopStaleEntityPolicy.Decision deployedDecision = fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET,
                        releaseMarker(retainedMarkerOperationId)
                )
        );
        ManagedCoopStaleEntityPolicy.Decision sourceDecision = fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        SOURCE,
                        releaseMarker(retainedMarkerOperationId)
                )
        );

        assertDecision(deployedDecision, ALLOW, DEPLOYED_RELEASE_PROJECTION);
        assertDecision(sourceDecision, SUPPRESS, HISTORICAL_RESIDENT_ALIAS);
        assertEquals(TARGET, sourceDecision.requiredLiveProjectionUuid());
        assertEquals(SOURCE, deployedDecision.staleAliasUuid());
        assertNull(deployedDecision.operationId(),
                "no active operation is invented for finalized resident evidence");
    }

    @Test
    void deployedProjectionWithoutExactManagedReleaseMarkerIsDeferred() {
        ResidentRecord deployed = resident(
                COOP, PROFILE, TARGET, SOURCE, TARGET, ResidentState.DEPLOYED, 2L);
        Fixture fixture = fixture(List.of(deployed), List.of(), true);
        ManagedCoopStaleEntityPolicy.MarkerEvidence recovery =
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId('e'), TameworkProjectionIdentityComponent.KIND_RECOVERY,
                        slotKey(), SOURCE, 1L
                );

        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, null)
        ), DEFER, INVALID_DEPLOYED_MARKER);
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, recovery)
        ), DEFER, INVALID_DEPLOYED_MARKER);
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET,
                        releaseMarker("managed-coop-release:not-a-hash")
                )
        ), DEFER, INVALID_DEPLOYED_MARKER);
    }

    @Test
    void exactFinalizedImportAdoptionMarkerAllowsOnlyThePersistedLiveUuid() {
        ResidentRecord deployed = resident(
                COOP, PROFILE, TARGET, TARGET, TARGET, ResidentState.DEPLOYED, 0L);
        Fixture fixture = fixture(List.of(deployed), List.of(), true);
        String operationId = "managed-coop-import-operation:" + "d".repeat(64);
        ManagedCoopStaleEntityPolicy.MarkerEvidence exact =
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId,
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        slotKey(), TARGET, 0L);

        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, exact)
        ), ALLOW, DEPLOYED_IMPORT_ADOPTION);

        List<ManagedCoopStaleEntityPolicy.MarkerEvidence> invalid = List.of(
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, "managed-coop-import-operation:not-a-hash",
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        slotKey(), TARGET, 0L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId,
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        COOP.slotKey(1), TARGET, 0L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId,
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        slotKey(), SOURCE, 0L),
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        PROFILE, operationId,
                        TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                        slotKey(), TARGET, 1L));
        for (ManagedCoopStaleEntityPolicy.MarkerEvidence marker : invalid) {
            assertDecision(fixture.policy().decide(
                    ManagedCoopStaleEntityPolicy.Observation.of(TARGET, marker)
            ), DEFER, INVALID_DEPLOYED_MARKER);
        }
    }

    @Test
    void conflictingCrossIndexAssignmentsAndOrphanManagedMarkersDefer() {
        ResidentRecord resident = resident(
                COOP, PROFILE, TARGET, SOURCE, TARGET, ResidentState.DEPLOYED, 2L);
        OperationRecord conflictingRelease = new OperationRecord(
                operationId('9'), OperationKind.RELEASE, "profile-b", OTHER_COOP, COOP_ID, 0,
                null, TARGET, null, OperationState.SPAWN_CLAIMED, HASH,
                0L, 1L, 0, true, -100L, -90L, 0L, null
        );
        Fixture conflictFixture = fixture(List.of(resident), List.of(conflictingRelease), true);
        Fixture orphanFixture = fixture(List.of(), List.of(), true);
        ManagedCoopStaleEntityPolicy.MarkerEvidence conflictMarker =
                new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                        "profile-b", conflictingRelease.operationId(), releaseKind(),
                        OTHER_COOP.slotKey(0), uuid(55L), 1L
                );

        assertDecision(conflictFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, conflictMarker)
        ), DEFER, CONFLICTING_EVIDENCE);
        assertDecision(orphanFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(uuid(88L), releaseMarker(RELEASE_ID))
        ), DEFER, ORPHAN_MANAGED_MARKER);
    }

    @Test
    void invalidAndUnsupportedOperationEvidenceDefers() {
        OperationRecord capture = capture(
                "managed-coop-capture:" + "c".repeat(64), PROFILE, COOP, SOURCE,
                OperationState.SOURCE_RETIRE_REQUESTED, 2L);
        Fixture captureFixture = fixture(List.of(), List.of(capture), true);
        assertDecision(captureFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET, managedMarker(capture.operationId(), "MANAGED_COOP_CAPTURE_SOURCE"))
        ), DEFER, INVALID_CAPTURE_PROJECTION);

        OperationRecord importOperation = importOperation();
        Fixture importFixture = fixture(List.of(), List.of(importOperation), true);
        assertDecision(importFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET, managedMarker(importOperation.operationId(), "MANAGED_COOP_IMPORT"))
        ), DEFER, UNSUPPORTED_OPERATION);
    }

    @Test
    void incompleteReleaseEvidenceDefers() {
        ResidentRecord releasing = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.RELEASING, 1L);
        Fixture hiddenReleaseFixture = fixture(
                List.of(releasing), List.of(release(OperationState.PREPARED, 0L, null)), true);
        assertDecision(hiddenReleaseFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, releaseMarker(RELEASE_ID))
        ), DEFER, RELEASE_NOT_SPAWN_VISIBLE);

        Fixture missingResidentFixture = fixture(
                List.of(), List.of(release(OperationState.SPAWN_CLAIMED, 1L, null)), true);
        assertDecision(missingResidentFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, releaseMarker(RELEASE_ID))
        ), DEFER, RELEASE_RESIDENT_MISMATCH);
    }

    @Test
    void blockedAndIncompleteResidentEvidenceDefers() {
        ResidentRecord releasing = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.RELEASING, 1L);
        Fixture missingOperationFixture = fixture(List.of(releasing), List.of(), true);
        assertDecision(missingOperationFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), DEFER, RELEASE_OPERATION_MISSING);

        ResidentRecord quarantined = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.QUARANTINED, 0L);
        Fixture blockedFixture = fixture(List.of(quarantined), List.of(), true);
        assertDecision(blockedFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), DEFER, BLOCKED_RESIDENT_STATE);

        ResidentRecord missingDeployment = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.DEPLOYED, 2L);
        Fixture missingDeploymentFixture = fixture(List.of(missingDeployment), List.of(), true);
        assertDecision(missingDeploymentFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, releaseMarker(RELEASE_ID))
        ), DEFER, DEPLOYED_IDENTITY_MISMATCH);
    }

    private static Fixture fixture(List<ResidentRecord> residents,
                                   List<OperationRecord> operations,
                                   boolean trusted) {
        ManagedCoopResidentIndex residentIndex = new ManagedCoopResidentIndex();
        ManagedCoopLifecycleOperationIndex operationIndex = new ManagedCoopLifecycleOperationIndex();
        Map<ManagedCoopAuthorityKey, AuthorityRecord> authorities = new LinkedHashMap<>();
        for (ResidentRecord resident : residents) {
            authorities.putIfAbsent(resident.authorityKey(), authority(resident));
        }
        assertTrue(residentIndex.rebuild(
                ManagedCoopReadResult.loaded(new ArrayList<>(authorities.values())),
                ManagedCoopReadResult.loaded(residents)
        ).rebuilt());
        assertTrue(operationIndex.rebuild(ManagedCoopReadResult.loaded(operations)).rebuilt());
        AtomicBoolean compositeTrust = new AtomicBoolean(trusted);
        ManagedCoopAuthorityEligibilityIndex authorityEligibility =
                new ManagedCoopAuthorityEligibilityIndex();
        LinkedHashMap<ManagedCoopAuthorityKey, String> eligible = new LinkedHashMap<>();
        for (ResidentRecord resident : residents) {
            eligible.put(resident.authorityKey(), resident.coopId());
        }
        for (OperationRecord operation : operations) {
            eligible.put(operation.authorityKey(), operation.coopId());
        }
        Map<String, List<ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence>> byWorld =
                new LinkedHashMap<>();
        for (Map.Entry<ManagedCoopAuthorityKey, String> entry : eligible.entrySet()) {
            byWorld.computeIfAbsent(entry.getKey().worldName(), ignored -> new ArrayList<>())
                    .add(new ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence(
                            entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<String, List<ManagedCoopAuthorityEligibilityIndex.AuthorityEvidence>> entry
                : byWorld.entrySet()) {
            authorityEligibility.replaceWorld(entry.getKey(), entry.getValue());
        }
        return new Fixture(
                new ManagedCoopStaleEntityPolicy(
                        residentIndex,
                        operationIndex,
                        authorityEligibility,
                        compositeTrust::get
                ),
                compositeTrust,
                authorityEligibility
        );
    }

    private static AuthorityRecord authority(ResidentRecord resident) {
        return new AuthorityRecord(
                resident.authorityKey().authorityId(),
                resident.authorityKey(),
                resident.coopId(),
                AuthorityState.TWORK_MANAGED,
                true,
                1,
                -100L,
                -90L,
                null
        );
    }

    private static ResidentRecord resident(ManagedCoopAuthorityKey key,
                                           String profileId,
                                           UUID residentUuid,
                                           UUID sourceUuid,
                                           UUID deployedUuid,
                                           ResidentState state,
                                           long generation) {
        return new ResidentRecord(
                "resident-" + profileId,
                key,
                COOP_ID,
                0,
                profileId,
                "Mob_Chicken",
                residentUuid,
                sourceUuid,
                deployedUuid,
                "{}",
                HASH,
                1,
                state,
                generation,
                true,
                -100L,
                state == ResidentState.DEPLOYED ? -50L : 0L,
                -100L,
                -90L
        );
    }

    private static OperationRecord release(OperationState state,
                                           long generation,
                                           UUID actualTargetUuid) {
        return new OperationRecord(
                RELEASE_ID,
                OperationKind.RELEASE,
                PROFILE,
                COOP,
                COOP_ID,
                0,
                null,
                TARGET,
                actualTargetUuid,
                state,
                HASH,
                0L,
                generation,
                0,
                true,
                -100L,
                -90L,
                0L,
                null
        );
    }

    private static OperationRecord capture(String operationId,
                                           String profileId,
                                           ManagedCoopAuthorityKey key,
                                           UUID sourceUuid,
                                           OperationState state,
                                           long generation) {
        return new OperationRecord(
                operationId,
                OperationKind.CAPTURE,
                profileId,
                key,
                COOP_ID,
                0,
                sourceUuid,
                null,
                null,
                state,
                HASH,
                0L,
                generation,
                0,
                true,
                -100L,
                -90L,
                0L,
                null
        );
    }

    private static OperationRecord importOperation() {
        return new OperationRecord(
                "managed-coop-import:" + "d".repeat(64),
                OperationKind.IMPORT,
                PROFILE,
                COOP,
                COOP_ID,
                0,
                null,
                null,
                null,
                OperationState.PREPARED,
                HASH,
                0L,
                0L,
                0,
                true,
                -100L,
                -90L,
                0L,
                null
        );
    }

    private static ManagedCoopStaleEntityPolicy.MarkerEvidence managedMarker(
            String operationId,
            String projectionKind) {
        return new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                PROFILE,
                operationId,
                projectionKind,
                slotKey(),
                SOURCE,
                1L
        );
    }

    private static ManagedCoopStaleEntityPolicy.MarkerEvidence releaseMarker(String operationId) {
        return new ManagedCoopStaleEntityPolicy.MarkerEvidence(
                PROFILE,
                operationId,
                releaseKind(),
                slotKey(),
                SOURCE,
                1L
        );
    }

    private static String releaseKind() {
        return TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_RELEASE;
    }

    private static String slotKey() {
        return COOP.slotKey(0);
    }

    private static String operationId(char value) {
        return "managed-coop-release:" + Character.toString(value).repeat(64);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static void assertDecision(ManagedCoopStaleEntityPolicy.Decision decision,
                                       ManagedCoopStaleEntityPolicy.Action action,
                                       ManagedCoopStaleEntityPolicy.Reason reason) {
        assertEquals(action, decision.action());
        assertEquals(reason, decision.reason());
    }

    private record Fixture(ManagedCoopStaleEntityPolicy policy,
                           AtomicBoolean compositeTrust,
                           ManagedCoopAuthorityEligibilityIndex authorityEligibility) {
    }
}
