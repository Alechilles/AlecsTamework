package com.alechilles.alecstamework.config.assets;

/** Per-role capability and attachment offset for NPC shoulder riding. */
public final class TwCompanionShoulderRideSettings {
    private boolean enabled;
    private double offsetX = 0.32;
    private double offsetY = 1.45;
    private double offsetZ;

    public boolean isEnabled() {
        return enabled;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    public boolean isConfigured() {
        return enabled
                && Double.isFinite(offsetX)
                && Double.isFinite(offsetY)
                && Double.isFinite(offsetZ);
    }

    TwCompanionShoulderRideSettings copy() {
        TwCompanionShoulderRideSettings copy =
                new TwCompanionShoulderRideSettings();
        copy.enabled = enabled;
        copy.offsetX = offsetX;
        copy.offsetY = offsetY;
        copy.offsetZ = offsetZ;
        return copy;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setOffsetX(double offsetX) {
        this.offsetX = offsetX;
    }

    void setOffsetY(double offsetY) {
        this.offsetY = offsetY;
    }

    void setOffsetZ(double offsetZ) {
        this.offsetZ = offsetZ;
    }
}
