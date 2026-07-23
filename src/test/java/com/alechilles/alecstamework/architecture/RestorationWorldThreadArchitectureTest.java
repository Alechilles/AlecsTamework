package com.alechilles.alecstamework.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source gate for current-world re-resolution at the replacement restoration boundary. */
class RestorationWorldThreadArchitectureTest {
    @Test
    void boundaryReResolvesWorldAndStoreInsideWorldExecute()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/companion/"
                        + "restoration/runtime/"
                        + "HytaleCompanionRestorationBoundary.java"
        ));

        assertTrue(source.contains("scheduled.execute(() ->"));
        assertTrue(source.contains(
                "World current = findWorld(request.targetWorldKey())"
        ));
        assertTrue(source.contains(
                "current.getEntityStore().getStore()"
        ));
        assertFalse(source.contains("PlayerRef.getComponent"));
        assertFalse(source.contains("Universe.get().getPlayers"));
        assertFalse(source.contains("store.putComponent"));
        assertFalse(source.contains("store.removeComponent"));
    }
}
