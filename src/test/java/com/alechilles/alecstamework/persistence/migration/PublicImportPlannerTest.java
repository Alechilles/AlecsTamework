package com.alechilles.alecstamework.persistence.migration;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure mapping tests for public evidence, lifecycle resolution, and quarantine. */
class PublicImportPlannerTest {
    private static final LegacySourceFingerprint FINGERPRINT =
            new LegacySourceFingerprint("a".repeat(64), 10, -100);

    @TempDir
    Path tempDir;

    @Test
    void representativeV4MapsToOneConsistentCanonicalLifecyclePerProfile() throws Exception {
        PublicImportPlan plan = plan("public-v4-representative.sql", 4);
        Map<String, PublicImportPlan.Lifecycle> lifecycle = plan.lifecycles().stream()
                .collect(Collectors.toMap(PublicImportPlan.Lifecycle::profileId, value -> value));

        assertEquals(6, plan.profiles().size());
        assertEquals(7, plan.aliases().size());
        assertEquals(2, plan.toolLinks().size());
        assertEquals(4, plan.snapshots().size());
        assertEquals(1, plan.extensionData().size());
        assertEquals(1, plan.coopSlots().size());
        assertEquals(1, plan.coopResidencies().size());
        assertEquals(6, plan.lifecycles().size());
        assertTrue(plan.incidents().isEmpty());

        assertEquals("UNRESOLVED", lifecycle.get(profile(1)).state());
        assertEquals("CAPTURED", lifecycle.get(profile(2)).state());
        assertEquals("DEAD_REVIVABLE", lifecycle.get(profile(3)).state());
        assertEquals("LOST", lifecycle.get(profile(4)).state());
        assertEquals("COOP", lifecycle.get(profile(5)).state());
        assertEquals("UNRESOLVED", lifecycle.get(profile(6)).state());
        assertEquals(-5_000, lifecycle.get(profile(1)).changedAtMs());
        assertEquals("Active Ω", plan.profiles().getFirst().displayName());
        assertEquals(-62135596800000L, plan.profiles().getFirst().createdAtMs());
        assertEquals(64, plan.profiles().getFirst().metadataHash().length());
        assertTrue(plan.snapshots().stream().allMatch(PublicImportPlan.Snapshot::current));
    }

    /**
     * Protects the copied-save failure where released rows retained by v2.16.1 were mistaken for
     * simultaneous current coop residency.
     */
    @Test
    void retainedReleasedCoopHistoryIsInactiveEvidenceNotCurrentResidency() throws Exception {
        PublicImportPlan plan = plan("public-v4-coop-history.sql", 4);
        Map<String, PublicImportPlan.Lifecycle> lifecycle = plan.lifecycles().stream()
                .collect(Collectors.toMap(PublicImportPlan.Lifecycle::profileId, value -> value));

        assertEquals(3, plan.coopSlots().size());
        assertEquals(1, plan.coopResidencies().size());
        assertTrue(plan.incidents().isEmpty());
        assertEquals("COOP", lifecycle.get(profile(5)).state());
        assertEquals("UNRESOLVED", lifecycle.get(profile(1)).state());
        assertEquals(3, plan.snapshots().stream()
                .filter(snapshot -> snapshot.kind().equals("coop"))
                .count());
        assertEquals(1, plan.snapshots().stream()
                .filter(snapshot -> snapshot.kind().equals("coop") && snapshot.current())
                .count());
    }

    @Test
    void coopStateWithoutPositiveHousedEvidenceRemainsQuarantined() throws Exception {
        LegacyPublicData source = read("public-v4-representative.sql", 4);
        LegacyPublicData.CoopSlot row = source.coopSlots().getFirst();
        LegacyPublicData.CoopSlot released = new LegacyPublicData.CoopSlot(
                row.worldName(), row.coopId(), row.x(), row.y(), row.z(),
                row.residentSlot(), row.profileId(), null, row.housedNpcUuid(),
                row.capturedAtMs(), 300, 300, row.stateSnapshotJson()
        );

        PublicImportPlan plan = new PublicImportPlanner().plan(
                withCoopSlots(source, List.of(released)), FINGERPRINT, -500
        );

        assertEquals(1, plan.incidents().size());
        assertEquals("COOP_EVIDENCE_INCOMPLETE",
                plan.incidents().getFirst().reasonCode());
        assertTrue(plan.coopResidencies().isEmpty());
        assertFalse(plan.snapshots().stream()
                .filter(snapshot -> snapshot.kind().equals("coop"))
                .anyMatch(PublicImportPlan.Snapshot::current));
    }

    @Test
    void multipleCurrentHousedRowsStillFailClosed() throws Exception {
        LegacyPublicData source = read("public-v4-representative.sql", 4);
        ArrayList<LegacyPublicData.CoopSlot> rows = new ArrayList<>(source.coopSlots());
        rows.add(new LegacyPublicData.CoopSlot(
                "world-a", "second-current-coop", 30, 64, 40, 0,
                profile(5), "00000000-0000-0000-0000-000000000055", null,
                300, 0, 300, "{\"version\":\"1\",\"current\":true}"
        ));

        PublicImportPlan plan = new PublicImportPlanner().plan(
                withCoopSlots(source, rows), FINGERPRINT, -500
        );

        assertEquals(1, plan.incidents().size());
        assertEquals("MULTIPLE_COOP_SLOTS",
                plan.incidents().getFirst().reasonCode());
        assertTrue(plan.coopResidencies().isEmpty());
        assertFalse(plan.snapshots().stream()
                .filter(snapshot -> snapshot.kind().equals("coop"))
                .anyMatch(PublicImportPlan.Snapshot::current));
    }

    @Test
    void housedRowWithoutProfileRefusesWholeImport() throws Exception {
        LegacyPublicData source = read("public-v4-representative.sql", 4);
        LegacyPublicData.CoopSlot row = source.coopSlots().getFirst();
        LegacyPublicData.CoopSlot orphaned = new LegacyPublicData.CoopSlot(
                row.worldName(), row.coopId(), row.x(), row.y(), row.z(),
                row.residentSlot(), null, row.housedNpcUuid(), null,
                row.capturedAtMs(), row.releasedAtMs(), row.updatedAtMs(),
                row.stateSnapshotJson()
        );

        PublicImportException failure = assertThrows(
                PublicImportException.class,
                () -> new PublicImportPlanner().plan(
                        withCoopSlots(source, List.of(orphaned)), FINGERPRINT, -500
                )
        );

        assertEquals("COOP_PROFILE_MISSING", failure.code());
    }

    @Test
    void legacyStaleFlagSelectsNewestCompleteLifecycleEvidence()
            throws Exception {
        PublicImportPlan plan = plan("public-v4-conflicting-flags.sql", 4);

        assertTrue(plan.incidents().isEmpty());
        assertEquals("DEAD_REVIVABLE", plan.lifecycles().getFirst().state());
        assertEquals("NONE", plan.lifecycles().getFirst().locationKind());
        assertEquals(2, plan.snapshots().size());
        assertEquals(1, plan.snapshots().stream()
                .filter(PublicImportPlan.Snapshot::current)
                .count());
        assertTrue(plan.snapshots().stream()
                .anyMatch(snapshot -> snapshot.current()
                        && "death".equals(snapshot.kind())));
    }

    @Test
    void equalTimestampLifecycleFlagsRemainQuarantined() throws Exception {
        LegacyPublicData source = read(
                "public-v4-conflicting-flags.sql", 4
        );
        List<LegacyPublicData.Snapshot> tied = source.snapshots().stream()
                .map(snapshot -> "capture".equals(snapshot.kind())
                        ? new LegacyPublicData.Snapshot(
                        snapshot.sourceSnapshotId(),
                        snapshot.profileId(),
                        snapshot.kind(),
                        snapshot.sourceRevision(),
                        snapshot.payloadJson(),
                        snapshot.active(),
                        220
                ) : snapshot)
                .toList();
        LegacyPublicData ambiguous = new LegacyPublicData(
                source.profiles(), source.aliases(), source.toolLinks(), tied,
                source.coopSlots(), source.profileStates(),
                source.extensionData()
        );

        PublicImportPlan plan = new PublicImportPlanner().plan(
                ambiguous, FINGERPRINT, -500
        );

        assertEquals(1, plan.incidents().size());
        assertEquals("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS",
                plan.incidents().getFirst().reasonCode());
        assertEquals("UNRESOLVED", plan.lifecycles().getFirst().state());
        assertFalse(plan.snapshots().stream()
                .anyMatch(PublicImportPlan.Snapshot::current));
        assertTrue(plan.incidents().getFirst().evidenceJson()
                .contains("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS"));
    }

    @Test
    void newestFlagCannotOverrideAnotherFlagWithMissingEvidence()
            throws Exception {
        LegacyPublicData source = read(
                "public-v4-conflicting-flags.sql", 4
        );
        List<LegacyPublicData.Snapshot> incomplete = source.snapshots().stream()
                .filter(snapshot -> !"capture".equals(snapshot.kind()))
                .toList();
        LegacyPublicData ambiguous = new LegacyPublicData(
                source.profiles(),
                source.aliases(),
                source.toolLinks(),
                incomplete,
                source.coopSlots(),
                source.profileStates(),
                source.extensionData()
        );

        PublicImportPlan plan = new PublicImportPlanner().plan(
                ambiguous, FINGERPRINT, -500
        );

        assertEquals("UNRESOLVED", plan.lifecycles().getFirst().state());
        assertEquals(
                "MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS",
                plan.incidents().getFirst().reasonCode()
        );
        assertFalse(plan.snapshots().stream()
                .anyMatch(PublicImportPlan.Snapshot::current));
    }

    @Test
    void emptyV2ProducesNoSyntheticRows() throws Exception {
        PublicImportPlan plan = plan("public-v2-empty.sql", 2);

        assertTrue(plan.profiles().isEmpty());
        assertTrue(plan.lifecycles().isEmpty());
        assertTrue(plan.snapshots().isEmpty());
        assertTrue(plan.incidents().isEmpty());
    }

    @Test
    void releasedSnapshotRevisionCountersNormalizeWithoutChangingIdentityOrContentEvidence()
            throws Exception {
        LegacyPublicData source = read("public-v4-representative.sql", 4);
        PublicImportPlan plan = new PublicImportPlanner().plan(source, FINGERPRINT, -500);
        Map<String, PublicImportPlan.Snapshot> targets = plan.snapshots().stream()
                .filter(snapshot -> !snapshot.kind().equals("coop"))
                .collect(Collectors.toMap(PublicImportPlan.Snapshot::kind, value -> value));

        for (LegacyPublicData.Snapshot snapshot : source.snapshots()) {
            PublicImportPlan.Snapshot target = targets.get(snapshot.kind());
            assertTrue(snapshot.sourceRevision() > 1, snapshot.kind());
            assertEquals(1, target.payloadVersion(), snapshot.kind());
            assertEquals(
                    PublicImportPlanningSupport.deterministicId(
                            FINGERPRINT.snapshotSha256(),
                            "snapshot:" + snapshot.sourceSnapshotId()
                    ),
                    target.snapshotId(),
                    snapshot.kind()
            );
            assertEquals(snapshot.payloadJson(), target.payloadJson(), snapshot.kind());
            assertEquals(
                    PublicImportPlanningSupport.sha256(snapshot.payloadJson()),
                    target.payloadHash(),
                    snapshot.kind()
            );
        }
    }

    @Test
    void unknownSnapshotKindKeepsItsSourceRevisionInsteadOfBeingSilentlyRelabeled()
            throws Exception {
        LegacyPublicData source = read("public-v4-representative.sql", 4);
        ArrayList<LegacyPublicData.Snapshot> snapshots = new ArrayList<>(source.snapshots());
        snapshots.add(new LegacyPublicData.Snapshot(
                99,
                profile(1),
                "future-kind",
                7,
                "{\"future\":true}",
                0,
                -200
        ));
        LegacyPublicData expanded = new LegacyPublicData(
                source.profiles(), source.aliases(), source.toolLinks(), snapshots,
                source.coopSlots(), source.profileStates(), source.extensionData()
        );

        PublicImportPlan plan = new PublicImportPlanner().plan(expanded, FINGERPRINT, -500);

        PublicImportPlan.Snapshot future = plan.snapshots().stream()
                .filter(snapshot -> snapshot.kind().equals("future-kind"))
                .findFirst()
                .orElseThrow();
        assertEquals(7, future.payloadVersion());
        assertEquals("{\"future\":true}", future.payloadJson());
    }

    private PublicImportPlan plan(String resource, int version) throws Exception {
        return new PublicImportPlanner().plan(read(resource, version), FINGERPRINT, -500);
    }

    private LegacyPublicData read(String resource, int version) throws Exception {
        Path source = tempDir.resolve(resource + ".sqlite");
        PersistenceConsolidationFixtureDatabase.materialize(resource, source);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            return new LegacyPublicDataReader().read(connection, version);
        }
    }

    private LegacyPublicData withCoopSlots(
            LegacyPublicData source,
            List<LegacyPublicData.CoopSlot> slots
    ) {
        return new LegacyPublicData(
                source.profiles(), source.aliases(), source.toolLinks(), source.snapshots(),
                slots, source.profileStates(), source.extensionData()
        );
    }

    private String profile(int suffix) {
        return "20000000-0000-0000-0000-%012d".formatted(suffix);
    }
}
