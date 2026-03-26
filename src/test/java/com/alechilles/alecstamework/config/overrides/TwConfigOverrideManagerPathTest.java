package com.alechilles.alecstamework.config.overrides;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwConfigOverrideManagerPathTest {

    @Test
    void resolveRelativeServerPathUsesInnermostServerSegment() {
        Path sourcePath = Path.of(
                "C:/Users/22ale/AppData/Roaming/Hytale/install/pre-release/package/game/latest/Server/mods/"
                        + "Alec's Animal Husbandry!/Server/Tamework/Global/TwGlobalConfig_AnimalHusbandry.json"
        );

        Path resolved = TwConfigOverrideManager.resolveRelativeServerPath(
                sourcePath,
                "Tamework/Global",
                "TwGlobalConfig_AnimalHusbandry"
        );

        assertEquals(
                Path.of("Server/Tamework/Global/TwGlobalConfig_AnimalHusbandry.json"),
                resolved
        );
    }

    @Test
    void resolveRelativeServerPathFallsBackWhenSourcePathMissing() {
        Path resolved = TwConfigOverrideManager.resolveRelativeServerPath(
                null,
                "Tamework/Needs",
                "TwNeedsConfig_Default"
        );

        assertEquals(
                Path.of("Server/Tamework/Needs/Config_TwNeedsConfig_Default.json"),
                resolved
        );
    }
}
