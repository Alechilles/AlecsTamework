package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source gate for the one current-world dispatcher shared by replacement live operations. */
class RestorationWorldThreadArchitectureTest {
    @Test
    void sharedDispatcherReResolvesWorldAndStoreInsideWorldExecute()
            throws Exception {
        String dispatcher = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/persistence/"
                        + "runtime/HytaleWorldOperationDispatcher.java"
        ));
        String restoration = source(
                "restoration/runtime/HytaleCompanionRestorationBoundary.java"
        );
        String capture = source(
                "coop/runtime/HytaleCompanionCoopCaptureBoundary.java"
        );
        String release = source(
                "coop/runtime/HytaleCompanionCoopReleaseBoundary.java"
        );

        assertTrue(dispatcher.contains("scheduled.execute(() ->"));
        assertTrue(dispatcher.contains("World current = findWorld(worldKey)"));
        assertTrue(dispatcher.contains(
                "current.getEntityStore().getStore()"
        ));
        assertTrue(restoration.contains("dispatcher.applyOrResolve("));
        assertTrue(capture.contains("scheduled.execute(() ->"));
        assertTrue(capture.contains("World current = findWorld(worldKey)"));
        assertTrue(capture.contains("current.getEntityStore().getStore()"));
        assertTrue(release.contains("dispatcher.applyOrResolve("));
        String all = dispatcher + restoration + capture + release;
        assertFalse(all.contains("PlayerRef.getComponent"));
        assertFalse(all.contains("Universe.get().getPlayers"));
        assertFalse(all.contains("store.putComponent"));
        assertFalse(all.contains("store.removeComponent"));
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/companion/"
                        + relativePath
        ));
    }
}
