package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopStaleEntityPolicy.MarkerEvidence;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies that scanner output is immutable and managed/vanilla aliases cannot enter selection. */
class ManagedCoopRuntimeCandidateScannerTest {

    @Test
    void unrelatedNpcIsCopiedWhileManagedMarkerAndVanillaResidentFailClosed() {
        ManagedCoopRuntimeCandidateScanner scanner = new ManagedCoopRuntimeCandidateScanner(policy());
        String[] tools = {"tool-a"};
        var unrelated = raw(1, tools, false, null);
        var orphanManaged = raw(2, tools, false, new MarkerEvidence(
                "profile", "managed-coop-capture:deadbeef", "MANAGED_COOP_CAPTURE_SOURCE",
                "world|1|2|3|slot=0", uuid(2), 1L));
        var vanilla = raw(3, tools, true, null);

        ManagedCoopRuntimeCandidateScanner.ScanResult result =
                scanner.filter(List.of(unrelated, orphanManaged, vanilla));
        tools[0] = "mutated";

        assertEquals(ManagedCoopRuntimeCandidateScanner.ScanStatus.COMPLETE, result.status());
        assertEquals(1, result.candidates().size());
        assertEquals(uuid(1), result.candidates().getFirst().npcUuid());
        assertEquals("hen", result.candidates().getFirst().roleId());
        assertEquals("tool-a", result.candidates().getFirst().toolIds()[0]);
        assertNull(result.candidates().getFirst().stableProfileId());
        assertEquals(1, result.suppressed());
        assertEquals(1, result.rejected());
    }

    private static ManagedCoopRuntimeCandidateScanner.RawCandidate raw(
            long id,
            String[] tools,
            boolean vanilla,
            MarkerEvidence marker) {
        return new ManagedCoopRuntimeCandidateScanner.RawCandidate(
                uuid(id), " HEN ", 1.0, 2.0, 3.0,
                null, null, tools, false, vanilla, false, marker);
    }

    private static ManagedCoopStaleEntityPolicy policy() {
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        residents.rebuild(
                ManagedCoopReadResult.loaded(List.of()),
                ManagedCoopReadResult.loaded(List.of()));
        ManagedCoopLifecycleOperationIndex operations = new ManagedCoopLifecycleOperationIndex();
        operations.rebuild(ManagedCoopReadResult.loaded(List.of()));
        return new ManagedCoopStaleEntityPolicy(residents, operations, () -> true);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
