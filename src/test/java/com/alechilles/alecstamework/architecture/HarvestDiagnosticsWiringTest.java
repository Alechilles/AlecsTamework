package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Guards command, default-config, and logger gating for harvest diagnostics.
 */
class HarvestDiagnosticsWiringTest {
    private static final Path COMMAND_ROOT = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "commands", "TameworkCommandRoot.java"
    );
    private static final Path DEFAULT_DEBUG_CONFIG = Paths.get(
            "src", "main", "resources", "Server", "Tamework", "Debug", "TwDebugDefault.json"
    );
    private static final Path TAMEWORK_PLUGIN = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework", "Tamework.java"
    );
    private static final Path INTERACT_EFFECTS = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "TameworkInteractEffects.java"
    );
    private static final Path HARVEST_ALARM_ACTION = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "ActionTameworkHarvestAlarm.java"
    );

    @Test
    void rootCommandRegistersHarvestDebugCommand() throws IOException {
        String content = Files.readString(COMMAND_ROOT, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("addSubCommand(new TameworkDebugHarvestCommand());"),
                "/tw must register the debugharvest command."
        );
    }

    @Test
    void defaultDebugConfigKeepsHarvestDiagnosticsDisabled() throws IOException {
        String content = Files.readString(DEFAULT_DEBUG_CONFIG, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("\"Harvest\": false"),
                "Harvest diagnostics must be available but disabled by default."
        );
    }

    @Test
    void debugDefaultsApplyHarvestToggle() throws IOException {
        String content = Files.readString(TAMEWORK_PLUGIN, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("setDebugHarvestEnabled(commands.isHarvest());"),
                "Debug defaults must apply the harvest diagnostics toggle."
        );
        assertTrue(
                content.contains("isDebugHarvestEnabled()"),
                "Tamework must expose harvest diagnostics state to log emitters."
        );
    }

    @Test
    void harvestDebugLogsRequireHarvestToggle() throws IOException {
        String interactContent = Files.readString(INTERACT_EFFECTS, StandardCharsets.UTF_8);
        String alarmContent = Files.readString(HARVEST_ALARM_ACTION, StandardCharsets.UTF_8);

        assertTrue(
                interactContent.contains("instance.isDebugHarvestEnabled()"),
                "Optimized interaction harvest diagnostics must be gated by /tw debugharvest."
        );
        assertTrue(
                alarmContent.contains("!instance.isDebugHarvestEnabled()"),
                "Harvest alarm cooldown diagnostics must be gated by /tw debugharvest."
        );
    }
}
