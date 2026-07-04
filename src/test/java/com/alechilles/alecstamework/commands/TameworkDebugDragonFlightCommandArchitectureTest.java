package com.alechilles.alecstamework.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TameworkDebugDragonFlightCommandArchitectureTest {
    private static final Path SOURCE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "commands",
            "TameworkDebugDragonFlightCommand.java"
    );

    @Test
    void flightProbeCommandEnablesClientFlightAndInputLoggingTogether() throws Exception {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        assertTrue(source.contains("AvatarFlightClientFlightProbe"),
                "debugdragonflight must expose the non-creative client flight probe");
        assertTrue(source.contains("\"flightprobe\""),
                "debugdragonflight must include a flightprobe action");
        assertTrue(source.contains("PlayerInputDebugProbe.enable(playerUuid)"),
                "flightprobe on must enable packet/input logging for the player");
        assertTrue(source.contains("PlayerInputDebugProbe.disable(playerUuid)"),
                "flightprobe off must disable packet/input logging for the player");
    }
}
