package com.alechilles.alecstamework.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards player-safe look targeting for projectile launches. */
class TameworkLaunchProjectileInteractionTest {
    @Test
    void positiveLookTargetDistanceUsesSourceLookBeforeNpcTargetSlotResolution() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/interactions/"
                        + "TameworkLaunchProjectileInteraction.java"));

        assertTrue(source.contains("new KeyedCodec<>(\"LookTargetDistance\""));
        assertTrue(source.contains("resolveLookTargetPosition(sourceLook)"));
        int lookTarget = source.indexOf("resolveLookTargetPosition(sourceLook)");
        int targetRef = source.indexOf("resolveTargetRef(context, sourceRef, commandBuffer)");
        assertTrue(lookTarget >= 0 && targetRef >= 0 && lookTarget < targetRef);
    }
}
