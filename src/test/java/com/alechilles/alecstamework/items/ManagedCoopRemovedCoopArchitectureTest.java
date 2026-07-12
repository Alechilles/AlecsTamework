package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guard for the v5-only removed-coop boundary and deferred callback safety. */
class ManagedCoopRemovedCoopArchitectureTest {

    @Test
    void reconciliationUsesOnlyV5DispatcherAndNeverVanillaOccupancy() throws Exception {
        String source = source("ManagedCoopRemovedCoopReconciler.java");

        assertTrue(source.contains("ManagedCoopRuntimeOperationDispatcher"));
        assertTrue(source.contains("AuthorityState.DISABLED"));
        assertTrue(source.contains("dispatcher::release"));
        assertFalse(source.contains("CommandLinkedNpcCoopService"));
        assertFalse(source.contains("CoopResidentSlotResolver"));
        assertFalse(source.contains("tryPutResident"));
        assertFalse(source.contains("tryPutWildResidentFromWild"));
        assertFalse(source.contains("ensureSpawnResidentsInWorld"));
        assertFalse(source.contains("ensureNoResidentsInWorld"));
    }

    @Test
    void persistenceContinuationDoesNotCaptureWorldStoreOrBlockReferences() throws Exception {
        String source = source("ManagedCoopRemovedCoopReconciler.java");
        String callback = between(
                source,
                "transition.handle((result, failure) -> {",
                "}).whenComplete((ignored, failure)");

        assertFalse(callback.contains("world"));
        assertFalse(callback.contains("chunkStore"));
        assertFalse(callback.contains("blockRef"));
        assertFalse(callback.contains("ManagedCoopContext"));
        assertTrue(callback.contains("currentDisabledAuthority"));
        assertTrue(callback.contains("dispatchDisabled"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items", fileName));
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0 && endIndex > startIndex,
                "expected callback markers in source");
        return source.substring(startIndex, endIndex);
    }
}
