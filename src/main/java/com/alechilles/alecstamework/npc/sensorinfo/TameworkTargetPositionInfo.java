package com.alechilles.alecstamework.npc.sensorinfo;

import com.hypixel.hytale.server.npc.sensorinfo.ExtraInfoProvider;

/**
 * Extra info payload used by sensors that expose a world-space target position.
 */
public final class TameworkTargetPositionInfo implements ExtraInfoProvider {
    private boolean hasPosition;
    private double targetX;
    private double targetY;
    private double targetZ;

    @Override
    public Class<? extends ExtraInfoProvider> getType() {
        return TameworkTargetPositionInfo.class;
    }

    public void setTarget(double x, double y, double z) {
        this.hasPosition = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    public void clear() {
        this.hasPosition = false;
        this.targetX = 0.0;
        this.targetY = 0.0;
        this.targetZ = 0.0;
    }

    public boolean hasPosition() {
        return hasPosition;
    }

    public double getTargetX() {
        return targetX;
    }

    public double getTargetY() {
        return targetY;
    }

    public double getTargetZ() {
        return targetZ;
    }
}
