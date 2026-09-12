package com.alechilles.alecstamework.npc.movement;

import org.joml.Vector3d;

/** Pure shared group geometry. The player is a destination, never a slot or an orientation. */
public final class CompanionFollowFormation {
    private final Vector3d center = new Vector3d();
    private double[] offsetX = new double[0];
    private double[] offsetZ = new double[0];
    private int count;
    private double spacing;
    private double range;
    private double stoppingDistance;
    private boolean initialized;

    /** Membership/size refresh at the service's half-second cadence. */
    public void configure(Vector3d groupPosition, int slotCount, double requestedSpacing, double recoveryRange) {
        if (!initialized) {
            center.set(groupPosition.x, 0, groupPosition.z);
            initialized = true;
        }
        int size = Math.max(1, slotCount);
        if (size == count && requestedSpacing == spacing && recoveryRange == range) return;
        count = size;
        spacing = requestedSpacing;
        range = recoveryRange;
        if (offsetX.length < count) {
            offsetX = new double[count];
            offsetZ = new double[count];
        }
        int columns = (int) Math.ceil(Math.sqrt(count));
        double meanX = 0, meanZ = 0;
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            offsetX[i] = (i % columns + (row % 2) * 0.5) * spacing;
            offsetZ[i] = row * spacing * Math.sqrt(3) / 2;
            meanX += offsetX[i];
            meanZ += offsetZ[i];
        }
        meanX /= count;
        meanZ /= count;
        double radius = 0;
        for (int i = 0; i < count; i++) {
            offsetX[i] -= meanX;
            offsetZ[i] -= meanZ;
            radius = Math.max(radius, Math.hypot(offsetX[i], offsetZ[i]));
        }
        // Keep the whole group inside its normal follow recovery envelope. Huge groups
        // may compress, as they did in the prototype; normal groups retain full spacing.
        double scale = radius > range * 0.3 ? range * 0.3 / radius : 1;
        for (int i = 0; i < count; i++) {
            offsetX[i] *= scale;
            offsetZ[i] *= scale;
        }
        stoppingDistance = Math.min(radius * scale + spacing * 1.25, range * 0.55);
    }

    /** Translates all slots equally toward the player only when the group is too far away. */
    public Vector3d target(Vector3d player, int slot, Vector3d output) {
        if (!initialized || slot < 0 || slot >= count) return output.set(player);
        double dx = player.x - center.x;
        double dz = player.z - center.z;
        double distance = Math.hypot(dx, dz);
        if (distance > stoppingDistance) {
            double fraction = (distance - stoppingDistance) / distance;
            center.x += dx * fraction;
            center.z += dz * fraction;
        }
        return output.set(center.x + offsetX[slot], 0, center.z + offsetZ[slot]);
    }
}