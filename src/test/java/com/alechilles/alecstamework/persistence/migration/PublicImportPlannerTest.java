package com.alechilles.alecstamework.persistence.migration;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void mutuallyExclusiveFlagsPreserveEvidenceButCreateNoAuthoritativeSnapshot() throws Exception {
        PublicImportPlan plan = plan("public-v4-conflicting-flags.sql", 4);

        assertEquals(1, plan.incidents().size());
        assertEquals("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS",
                plan.incidents().getFirst().reasonCode());
        assertEquals("UNRESOLVED", plan.lifecycles().getFirst().state());
        assertEquals("UNRESOLVED", plan.lifecycles().getFirst().locationKind());
        assertEquals(plan.incidents().getFirst().incidentId(),
                plan.lifecycles().getFirst().incidentId());
        assertEquals(2, plan.snapshots().size());
        assertFalse(plan.snapshots().stream().anyMatch(PublicImportPlan.Snapshot::current));
        assertTrue(plan.incidents().getFirst().evidenceJson()
                .contains("MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS"));
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

    private String profile(int suffix) {
        return "20000000-0000-0000-0000-%012d".formatted(suffix);
    }
}
