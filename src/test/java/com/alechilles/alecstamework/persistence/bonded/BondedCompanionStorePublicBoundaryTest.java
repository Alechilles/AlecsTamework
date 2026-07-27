package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the complete adapter-neutral public bonded storage boundary. */
class BondedCompanionStorePublicBoundaryTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID NPC =
            UUID.fromString("20000000-0000-0000-0000-000000000001");

    @TempDir Path tempDir;
    private BondedCompanionStore store;

    @BeforeEach
    void setUp() {
        Path database = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(new BondedCompanionSchemaManager(database, () -> -20_000L)
                .initialize().availability().available());
        store = new SqliteBondedCompanionDatabase(database);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.createProfile(operation("create", '1',
                        BondedCompanionOperation.Type.PROVISION, "profile-a", 10_000L),
                        profile()).code());
    }

    @Test
    void snapshotUpdateAndLeaseReleaseRemainAtomicPublicOperations() {
        BondedCompanionPayload updated = payload("updated-snapshot");
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.updateSnapshot(operation("snapshot", '2',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                        0, updated, -9_000L).code());
        assertEquals(updated, store.findProfile(OWNER, "roster-a", "profile-a")
                .orElseThrow().snapshot());

        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.acquireLease(operation("summon", '3',
                        BondedCompanionOperation.Type.SUMMON, "profile-a", 10_000L),
                        1, lease(0L)).code());
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.releaseLease(operation("release", '4',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                        2, "lease-a", -8_000L).code());

        assertTrue(store.findActiveLeases(OWNER, "roster-a").isEmpty());
        assertEquals(BondedCompanionState.STORED,
                store.findProfile(OWNER, "roster-a", "profile-a")
                        .orElseThrow().state());
    }

    @Test
    void extensionCompareAndSetUsesOpaquePayloadAndTypedRevision() {
        var initial = new BondedCompanionRecord.ExtensionData(
                "profile-a", "example:stats", payload("xp=1"), 0, -9_000L);
        var changed = new BondedCompanionRecord.ExtensionData(
                "profile-a", "example:stats", payload("xp=2"), 1, -8_000L);

        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.compareAndSetExtensionData(operation("extension-1", '5',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                        initial, -1).code());
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.compareAndSetExtensionData(operation("extension-2", '6',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                        changed, 0).code());

        assertEquals(changed, store.findExtensionData(
                OWNER, "roster-a", "profile-a", "example:stats").orElseThrow());
    }

    @Test
    void finiteLeaseExpiryQueryPreservesSignedWorldTime() {
        store.acquireLease(operation("summon", '7',
                        BondedCompanionOperation.Type.SUMMON, "profile-a", 10_000L),
                0, lease(-8_000L));

        assertEquals(1, store.findExpiredLeases(-7_000L, 10).size());
        assertTrue(store.findExpiredLeases(-9_000L, 10).isEmpty());
    }

    @Test
    void cleanupEnqueueListAndPruneStayOwnerScopedAndBounded() {
        BondedCompanionRecord.Cleanup cleanup = new BondedCompanionRecord.Cleanup(
                "cleanup-a", OWNER, "roster-a", "profile-a", null,
                BondedCompanionRecord.CleanupTarget.PROJECTION, NPC,
                "world-a", "stale-projection",
                BondedCompanionRecord.CleanupState.COMPLETED,
                1, -8_000L, -9_000L, -1L);

        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.enqueueCleanup(operation("cleanup", '8',
                        BondedCompanionOperation.Type.CLEANUP, "profile-a", 10_000L),
                        cleanup).code());
        assertEquals(java.util.List.of(cleanup),
                store.listCleanup(OWNER, "roster-a", 10));
        assertEquals(1, store.pruneCleanup(0L, 10));
        assertTrue(store.listCleanup(OWNER, "roster-a", 10).isEmpty());
    }

    @Test
    void operationPruningIsBoundedWithoutDeletingDomainRecords() {
        assertEquals(1, store.pruneOperations(20_000L, 1));
        assertTrue(store.findProfile(OWNER, "roster-a", "profile-a").isPresent());
    }

    @Test
    void finiteFamilyCapacityCannotFallBackToAnUncheckedDefaultMutation()
            throws Exception {
        assertFalse(BondedCompanionStore.class.getMethod(
                "createProfile",
                BondedCompanionOperation.class,
                BondedCompanionRecord.Profile.class,
                int.class
        ).isDefault());
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                "profile-a", OWNER, "roster-a", "family:wolf", "role:companion",
                BondedCompanionState.STORED, 0, payload("initial-snapshot"),
                -10_000L, -10_000L, Map.of("policy", "unlimited"),
                "Wolf", "Wolf", "Female", null, 0L, 0L, null, null);
    }

    private BondedCompanionRecord.Lease lease(long expiresAtMs) {
        return new BondedCompanionRecord.Lease(
                "profile-a", "lease-a", NPC, "world-a", -9_000L,
                expiresAtMs, BondedCompanionRecord.ProjectionState.LIVE);
    }

    private BondedCompanionPayload payload(String value) {
        return BondedCompanionPayload.of(value.getBytes(StandardCharsets.UTF_8));
    }

    private BondedCompanionOperation operation(
            String key, char hash, BondedCompanionOperation.Type type,
            String profileId, long retainedUntilMs) {
        return new BondedCompanionOperation(
                "public-boundary", key, String.valueOf(hash).repeat(64),
                OWNER, "roster-a", profileId, type, -10_000L, retainedUntilMs);
    }
}
