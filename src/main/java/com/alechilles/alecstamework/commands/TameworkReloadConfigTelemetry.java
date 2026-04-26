package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.config.overrides.TwConfigOverrideManager;
import javax.annotation.Nonnull;

final class TameworkReloadConfigTelemetry {
    private TameworkReloadConfigTelemetry() {
    }

    @Nonnull
    static String reloadResultLabel(@Nonnull TwConfigOverrideManager.ReloadResult reloadResult) {
        return reloadResult.hasErrors() ? "partial" : "success";
    }
}
