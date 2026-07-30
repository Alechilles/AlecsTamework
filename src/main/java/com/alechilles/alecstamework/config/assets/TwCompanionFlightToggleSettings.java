package com.alechilles.alecstamework.config.assets;

/** Per-role hook capability for switching a companion between ground and flight. */
public final class TwCompanionFlightToggleSettings {
    private boolean enabled;
    private String hookId = "";

    public boolean isEnabled() {
        return enabled;
    }

    public String getHookId() {
        return hookId;
    }

    public boolean isConfigured() {
        return enabled && !hookId.isBlank();
    }

    TwCompanionFlightToggleSettings copy() {
        TwCompanionFlightToggleSettings copy =
                new TwCompanionFlightToggleSettings();
        copy.enabled = enabled;
        copy.hookId = hookId;
        return copy;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setHookId(String hookId) {
        this.hookId = hookId == null ? "" : hookId.trim();
    }
}
