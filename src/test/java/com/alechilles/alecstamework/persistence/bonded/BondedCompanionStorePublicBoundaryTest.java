package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshot;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotCodec;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
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
    void operationPruningIsBoundedWithoutDeletingDomainRecords() {
        assertEquals(1, store.pruneOperations(20_000L, 1));
        assertTrue(store.findProfile(OWNER, "roster-a", "profile-a").isPresent());
    }

    @Test
    void completeSnapshotUpdateIsOwnerScopedAndRevisionFenced() {
        BondedCompanionPayload replacement = completeSnapshotPayload();

        var applied = store.updateSnapshot(operation("talents", '9',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                0L, replacement, -8_000L);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED, applied.code());
        assertEquals(1L, applied.value().revision());
        assertEquals(replacement, applied.value().snapshot());

        var stale = store.updateSnapshot(operation("talents-stale", 'a',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                0L, replacement, -7_000L);
        assertEquals(BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                stale.code());
    }

    @Test
    void permanentDeletionIsOwnerAndRevisionFencedAndCascadesExtensions() {
        var extension = new BondedCompanionRecord.ExtensionData(
                "profile-a", "example:stats", payload("xp=1"), 0, -9_000L);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.compareAndSetExtensionData(operation("extension-delete", '8',
                        BondedCompanionOperation.Type.STORE, "profile-a", 10_000L),
                        extension, -1).code());

        assertEquals(BondedCompanionStoreResult.Code.REVISION_CONFLICT,
                store.deleteProfile(OWNER, "roster-a", "profile-a", 1).code());
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                store.deleteProfile(OWNER, "roster-a", "profile-a", 0).code());
        assertTrue(store.findProfile(OWNER, "roster-a", "profile-a").isEmpty());
        assertTrue(store.findExtensionData(OWNER, "roster-a", "profile-a",
                "example:stats").isEmpty());
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

    private BondedCompanionPayload completeSnapshotPayload() {
        CoopResidentStateSnapshot state = new CoopResidentStateSnapshot(
                NPC, null, -1, "role:companion", null,
                new TameworkOwnerComponent(OWNER, "Owner"),
                new TameworkTamedComponent(true), null, null, null, null,
                null, null, null, null, null, null, null, null, -9_000L);
        String encoded = new BondedCompanionSnapshotCodec().encode(
                BondedCompanionSnapshot.of(state, Map.of()));
        return BondedCompanionPayload.of(encoded.getBytes(StandardCharsets.UTF_8));
    }

    private BondedCompanionOperation operation(
            String key, char hash, BondedCompanionOperation.Type type,
            String profileId, long retainedUntilMs) {
        return new BondedCompanionOperation(
                "public-boundary", key, String.valueOf(hash).repeat(64),
                OWNER, "roster-a", profileId, type, -10_000L, retainedUntilMs);
    }
}
