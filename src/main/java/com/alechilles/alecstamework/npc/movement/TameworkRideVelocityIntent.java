package com.alechilles.alecstamework.npc.movement;

public final class TameworkRideVelocityIntent {
    private static final double VERTICAL_DEAD_ZONE = 0.25;
    private static final double VERTICAL_DOMINANCE = 1.35;
    private static final double HORIZONTAL_DEAD_ZONE = 0.05;

    private TameworkRideVelocityIntent() {
    }

    public static boolean isVerticalDominant(double worldX, double worldY, double worldZ) {
        double horizontal = Math.sqrt(worldX * worldX + worldZ * worldZ);
        double vertical = Math.abs(worldY);
        return vertical > VERTICAL_DEAD_ZONE && vertical >= horizontal * VERTICAL_DOMINANCE;
    }

    public static double verticalInput(double worldX, double worldY, double worldZ) {
        if (!isVerticalDominant(worldX, worldY, worldZ)) {
            return 0.0;
        }
        return Math.copySign(1.0, worldY);
    }

    public static boolean hasUsableHorizontalIntent(double worldX, double worldZ) {
        return Math.sqrt(worldX * worldX + worldZ * worldZ) > HORIZONTAL_DEAD_ZONE;
    }

}
