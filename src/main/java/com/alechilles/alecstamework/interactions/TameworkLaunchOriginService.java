package com.alechilles.alecstamework.interactions;

import org.joml.Vector3d;

final class TameworkLaunchOriginService {
    private TameworkLaunchOriginService() {
    }

    static Vector3d applyOffset(Vector3d sourcePosition, float sourceYaw, double offsetX, double offsetY, double offsetZ) {
        double sinYaw = Math.sin(sourceYaw);
        double cosYaw = Math.cos(sourceYaw);
        return new Vector3d(
                sourcePosition.x + (offsetX * cosYaw) + (offsetZ * sinYaw),
                sourcePosition.y + offsetY,
                sourcePosition.z - (offsetX * sinYaw) + (offsetZ * cosYaw)
        );
    }
}
