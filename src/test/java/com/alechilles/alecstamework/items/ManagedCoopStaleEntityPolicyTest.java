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
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.IGNORE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Action.SUPPRESS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ACTIVE_CAPTURE_SOURCE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ACTIVE_RELEASE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.CONFLICTING_EVIDENCE;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.DEPLOYED_RELEASE_PROJECTION;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.HISTORICAL_RESIDENT_ALIAS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.HOUSED_ALIAS;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.INVALID_DEPLOYED_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.INVALID_RELEASE_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.ORPHAN_MANAGED_MARKER;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_TARGET_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.RELEASE_RESIDENT_MISMATCH;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNRELATED_NPC;
import static com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.Reason.UNTRUSTED_COMPOSITE;
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
    void mappedEvidenceFailsClosedWhenCompositeIsUntrusted() {
        ResidentRecord housed = resident(
                COOP, PROFILE, SOURCE, SOURCE, null, ResidentState.HOUSED, 0L);
        Fixture fixture = fixture(List.of(housed), List.of(), false);

        ManagedCoopStaleEntityPolicy.Decision decision = fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        );

        assertDecision(decision, SUPPRESS, UNTRUSTED_COMPOSITE);
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
        assertDecision(captureFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, null)
        ), SUPPRESS, ACTIVE_CAPTURE_SOURCE);
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
            ), SUPPRESS, INVALID_RELEASE_MARKER);
        }
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(SOURCE, releaseMarker(RELEASE_ID))
        ), SUPPRESS, RELEASE_TARGET_MISMATCH);

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
        ), SUPPRESS, RELEASE_RESIDENT_MISMATCH);
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
        assertNull(deployedDecision.operationId(),
                "no active operation is invented for finalized resident evidence");
    }

    @Test
    void deployedProjectionWithoutExactManagedReleaseMarkerIsSuppressed() {
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
        ), SUPPRESS, INVALID_DEPLOYED_MARKER);
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(TARGET, recovery)
        ), SUPPRESS, INVALID_DEPLOYED_MARKER);
        assertDecision(fixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(
                        TARGET,
                        releaseMarker("managed-coop-release:not-a-hash")
                )
        ), SUPPRESS, INVALID_DEPLOYED_MARKER);
    }

    @Test
    void conflictingCrossIndexAssignmentsAndOrphanManagedMarkersSuppress() {
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
        ), SUPPRESS, CONFLICTING_EVIDENCE);
        assertDecision(orphanFixture.policy().decide(
                ManagedCoopStaleEntityPolicy.Observation.of(uuid(88L), releaseMarker(RELEASE_ID))
        ), SUPPRESS, ORPHAN_MANAGED_MARKER);
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
        return new Fixture(
                new ManagedCoopStaleEntityPolicy(
                        residentIndex,
                        operationIndex,
                        compositeTrust::get
                ),
                compositeTrust
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
                           AtomicBoolean compositeTrust) {
    }
}
