package com.alechilles.alecstamework.avatarflight;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AvatarFlightClientFlightProbeArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "avatarflight",
            "AvatarFlightClientFlightProbe.java"
    );

    @Test
    void probeUsesMovementCapabilityAndFlyingStateWithoutCreativeMode() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("MovementManager"),
                "client flight probe must update MovementManager rather than changing game mode");
        assertTrue(source.contains(".canFly = true"),
                "client flight probe must explicitly enable the client canFly movement setting");
        assertTrue(source.contains("Player.applyMovementStates(ref, new SavedMovementStates(true)"),
                "client flight probe must put the client into flying movement state");
        assertTrue(source.contains("new SavedMovementStates(snapshot.flying())"),
                "client flight probe must restore the saved flying state");
        assertTrue(source.contains(".canFly = snapshot.canFly()"),
                "client flight probe must restore the saved canFly movement setting");
    }
}
