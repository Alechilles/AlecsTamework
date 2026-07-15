package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.ManagedCoopCaptureCoordinator.CaptureAttempt;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacement;
import com.alechilles.alecstamework.items.ManagedCoopOccupancyService.CapturePlacementStatus;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopCaptureClaimValidator;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for capture ordering and immutable durable-attempt construction. */
class ManagedCoopCaptureRuntimeAdapterTest {
    private static final UUID SOURCE = new UUID(0L, 1L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void sourceOrdersCapacityCancellationSnapshotAndPersistence() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/ManagedCoopCaptureRuntimeAdapter.java"
        )).replace("\r\n", "\n");

        int capacity = source.indexOf("occupancy.resolveCapturePlacement(");
        int cancellation = source.indexOf("cancelForCapturedParentDurably(");
        int continuation = source.indexOf(
                "private CompletableFuture<CaptureOutcome> continueCapture(");
        int snapshot = source.indexOf(
                "captureSnapshotForManagedCoopPersistence(", continuation);
        int persistence = source.indexOf("captureGateway.coordinate(attempt)");

        assertTrue(capacity >= 0 && capacity < cancellation);
        assertTrue(cancellation < continuation && continuation < snapshot);
        assertTrue(snapshot < persistence);
        assertTrue(source.contains("store.assertThread()"));
        assertTrue(source.contains("LeaseBoundWorldDispatcher.execute("));
        assertTrue(source.contains("withCaptureFence("));
        assertTrue(source.contains("breedingCancellation.releaseCaptureFence("));
        assertTrue(source.contains("outcome.isRetirementReady()"));
        assertFalse(source.contains(".join()"));
        assertFalse(source.contains("Universe"));
    }

    @Test
    void buildsHashedAttemptFromExactPostCancellationSnapshot() throws Exception {
        ManagedCoopContext context = context();
        ManagedCoopCaptureRuntimeAdapter.Candidate candidate =
                new ManagedCoopCaptureRuntimeAdapter.Candidate(
                        SOURCE, "Mob_Chicken", 1.0, 1.0,
                        null, "Chicken", new String[]{"tool-a"}, "profile-a");
        CoopResidentStateSnapshot snapshot = snapshot("coop_chicken", 0, "mob_chicken");
        ManagedCoopCaptureRuntimeAdapter adapter = adapter();

        CaptureAttempt attempt = adapter.buildAttempt(context, placement(0, 0L), candidate, snapshot);

        assertEquals(AUTHORITY, attempt.authorityKey());
        assertEquals(SOURCE, attempt.sourceNpcUuid());
        assertEquals(0, attempt.residentSlot());
        assertEquals(0L, attempt.expectedResidentGeneration());
        assertEquals(snapshot.capturedAtMs(), attempt.capturedAtMs());
        assertEquals(
                ManagedCoopCaptureClaimValidator.snapshotSha256(attempt.snapshotJson()),
                attempt.snapshotHash()
        );
        assertTrue(attempt.snapshotJson().contains("\"coopId\":\"coop_chicken\""));
        assertTrue(attempt.snapshotJson().contains("\"residentSlot\":0"));
        assertTrue(attempt.snapshotJson().contains("\"roleId\":\"mob_chicken\""));
    }

    @Test
    void candidateAndAttemptDefensivelyCopyToolIds() throws Exception {
        String[] tools = {"tool-a"};
        ManagedCoopCaptureRuntimeAdapter.Candidate candidate =
                new ManagedCoopCaptureRuntimeAdapter.Candidate(
                        SOURCE, "mob_chicken", 1.0, 1.0, null, null, tools, null);
        tools[0] = "mutated";
        CaptureAttempt attempt = adapter().buildAttempt(
                context(), placement(0, 0L), candidate, snapshot("coop_chicken", 0, "mob_chicken"));

        String[] returned = attempt.toolIds();
        returned[0] = "changed";
        assertEquals("tool-a", candidate.toolIds()[0]);
        assertEquals("tool-a", attempt.toolIds()[0]);
    }

    @Test
    void recaptureAttemptCarriesCommittedResidentGeneration() throws Exception {
        ManagedCoopCaptureRuntimeAdapter.Candidate candidate =
                new ManagedCoopCaptureRuntimeAdapter.Candidate(
                        SOURCE, "mob_chicken", 1.0, 1.0,
                        null, null, new String[0], "profile-a");

        CaptureAttempt attempt = adapter().buildAttempt(
                context(),
                new CapturePlacement(
                        CapturePlacementStatus.RECAPTURE, 0, 8L, "legacy-resident", null),
                candidate,
                snapshot("coop_chicken", 0, "mob_chicken")
        );

        assertEquals(8L, attempt.expectedResidentGeneration());
        assertEquals("legacy-resident", attempt.existingResidentId());
    }

    @Test
    void mismatchedUuidCoopSlotOrRoleFailsBeforePersistenceAttempt() throws Exception {
        ManagedCoopCaptureRuntimeAdapter adapter = adapter();
        ManagedCoopCaptureRuntimeAdapter.Candidate candidate =
                new ManagedCoopCaptureRuntimeAdapter.Candidate(
                        SOURCE, "mob_chicken", 1.0, 1.0,
                        null, null, new String[0], null);

        assertThrows(IllegalArgumentException.class, () -> adapter.buildAttempt(
                context(), placement(0, 0L), candidate,
                new CoopResidentStateSnapshot(
                        new UUID(0L, 99L), "coop_chicken", 0, "mob_chicken",
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, -100L)));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildAttempt(
                context(), placement(0, 0L), candidate, snapshot("coop_duck", 0, "mob_chicken")));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildAttempt(
                context(), placement(0, 0L), candidate, snapshot("coop_chicken", 1, "mob_chicken")));
        assertThrows(IllegalArgumentException.class, () -> adapter.buildAttempt(
                context(), placement(0, 0L), candidate, snapshot("coop_chicken", 0, "mob_duck")));
    }

    private ManagedCoopCaptureRuntimeAdapter adapter() {
        return new ManagedCoopCaptureRuntimeAdapter(
                new ManagedCoopOccupancyService(new ManagedCoopResidentIndex()),
                new com.alechilles.alecstamework.npc.actions.BreedingCaptureCancellationService(),
                new CoopResidentStateSnapshotService(),
                new CoopResidentStateSnapshotCodec(),
                attempt -> {
                    throw new AssertionError("factory test must not persist");
                }
        );
    }

    private CoopResidentStateSnapshot snapshot(String coopId, int slot, String roleId) {
        return new CoopResidentStateSnapshot(
                SOURCE, coopId, slot, roleId,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, -100L
        );
    }

    private CapturePlacement placement(int slot, long generation) {
        return new CapturePlacement(
                CapturePlacementStatus.NEW_SLOT, slot, generation, null, null);
    }

    private ManagedCoopContext context() throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", "Coop_Config");
        set(config, "enabled", true);
        set(config, "coopId", "coop_chicken");
        set(config.getLifecycleRules(), "maxResidents", 3);
        return new ManagedCoopContext(AUTHORITY, "coop_chicken", 0, config, null);
    }

    private void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
