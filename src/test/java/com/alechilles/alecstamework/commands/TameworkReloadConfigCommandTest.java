package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkReloadConfigCommandTest {

    @Test
    void reloadResultLabelDistinguishesPartialReloads() {
        assertEquals("success", TameworkReloadConfigTelemetry.reloadResultLabel(TwConfigOverrideManager.ReloadResult.empty()));
        assertEquals("partial", TameworkReloadConfigTelemetry.reloadResultLabel(new TwConfigOverrideManager.ReloadResult(
                1,
                1,
                List.of("Bad override")
        )));
    }
}
