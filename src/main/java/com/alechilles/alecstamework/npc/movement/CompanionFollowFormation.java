package com.alechilles.alecstamework.npc.movement;

import java.util.Arrays;
import org.joml.Vector3d;

/**
 * Pure shared group geometry. The player is a destination, never a slot or an orientation.
 *
 * <p>Each slot supplies its own horizontal body radius and desired gap. Rows pack adjacent
 * animals by their paired radii, so a large companion only widens the slots beside it. The
 * supplied size arrays are copied and cached; unchanged membership snapshots do not rebuild the
 * layout.</p>
 */
public final class CompanionFollowFormation {
    private final Vector3d center = new Vector3d();
    private double[] offsetX = new double[0];
    private double[] offsetZ = new double[0];
    private double[] configuredRadii = new double[0];
    private double[] configuredGaps = new double[0];
    private double[] rowMaxRadius = new double[0];
    private double[] rowMaxGap = new double[0];
    private double[] rowMinX = new double[0];
    private double[] rowMaxX = new double[0];
    private int count;
    private double range;
    private double stoppingDistance;
    private boolean initialized;

    /**
     * Refreshes a compact layout from per-slot radii and gaps at the caller's bounded cadence.
     * A group whose own footprint exceeds its recovery range retains its clearance and may need
     * ordinary follow recovery before every member is inside that range.
     */
    public void configure(Vector3d groupPosition, int slotCount, double[] radii, double[] gaps,
                          double recoveryRange) {
        if (!initialized) {
            center.set(groupPosition.x, 0, groupPosition.z);
            initialized = true;
        }
        int size = Math.max(1, slotCount);
        double safeRange = Math.max(0, recoveryRange);
        if (sameConfiguration(size, radii, gaps, safeRange)) return;

        count = size;
        range = safeRange;
        ensureCapacity(size);
        copySizes(radii, gaps, size);
        rebuildOffsets();
    }

    /** Translates all slots equally toward the player only when the group is too far away. */
    public Vector3d target(Vector3d player, int slot, Vector3d output) {
        if (!initialized || slot < 0 || slot >= count) return output.set(player.x, 0, player.z);
        double dx = player.x - center.x;
        double dz = player.z - center.z;
        double distance = Math.hypot(dx, dz);
        if (distance > stoppingDistance && distance > 0) {
            double fraction = (distance - stoppingDistance) / distance;
            center.x += dx * fraction;
            center.z += dz * fraction;
        }
        return output.set(center.x + offsetX[slot], 0, center.z + offsetZ[slot]);
    }

    private boolean sameConfiguration(int size, double[] radii, double[] gaps, double safeRange) {
        if (size != count || Double.compare(safeRange, range) != 0) return false;
        for (int i = 0; i < size; i++) {
            if (Double.compare(configuredRadii[i], sizeAt(radii, i)) != 0
                    || Double.compare(configuredGaps[i], sizeAt(gaps, i)) != 0) return false;
        }
        return true;
    }

    private void ensureCapacity(int size) {
        if (offsetX.length >= size) return;
        offsetX = Arrays.copyOf(offsetX, size);
        offsetZ = Arrays.copyOf(offsetZ, size);
        configuredRadii = Arrays.copyOf(configuredRadii, size);
        configuredGaps = Arrays.copyOf(configuredGaps, size);
    }

    private void copySizes(double[] radii, double[] gaps, int size) {
        for (int i = 0; i < size; i++) {
            configuredRadii[i] = sizeAt(radii, i);
            configuredGaps[i] = sizeAt(gaps, i);
        }
    }

    private void rebuildOffsets() {
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        ensureRowCapacity(rows);
        Arrays.fill(rowMaxRadius, 0, rows, 0);
        Arrays.fill(rowMaxGap, 0, rows, 0);
        Arrays.fill(rowMinX, 0, rows, Double.POSITIVE_INFINITY);
        Arrays.fill(rowMaxX, 0, rows, Double.NEGATIVE_INFINITY);

        for (int slot = 0; slot < count; slot++) {
            int row = slot / columns;
            int column = slot % columns;
            if (column == 0) {
                offsetX[slot] = 0;
            } else {
                int previous = slot - 1;
                offsetX[slot] = offsetX[previous] + configuredRadii[previous]
                        + configuredRadii[slot]
                        + Math.max(configuredGaps[previous], configuredGaps[slot]);
            }
            rowMaxRadius[row] = Math.max(rowMaxRadius[row], configuredRadii[slot]);
            rowMaxGap[row] = Math.max(rowMaxGap[row], configuredGaps[slot]);
            rowMinX[row] = Math.min(rowMinX[row], offsetX[slot] - configuredRadii[slot]);
            rowMaxX[row] = Math.max(rowMaxX[row], offsetX[slot] + configuredRadii[slot]);
        }

        for (int row = 0; row < rows; row++) {
            double rowCenter = (rowMinX[row] + rowMaxX[row]) / 2;
            int first = row * columns;
            int end = Math.min(first + columns, count);
            for (int slot = first; slot < end; slot++) offsetX[slot] -= rowCenter;
        }

        Arrays.fill(offsetZ, 0, count, 0);
        for (int row = 1; row < rows; row++) {
            int first = row * columns;
            int previousFirst = first - columns;
            offsetZ[first] = offsetZ[previousFirst] + rowMaxRadius[row - 1] + rowMaxRadius[row]
                    + Math.max(rowMaxGap[row - 1], rowMaxGap[row]);
            int end = Math.min(first + columns, count);
            for (int slot = first + 1; slot < end; slot++) offsetZ[slot] = offsetZ[first];
        }

        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < count; slot++) {
            minZ = Math.min(minZ, offsetZ[slot] - configuredRadii[slot]);
            maxZ = Math.max(maxZ, offsetZ[slot] + configuredRadii[slot]);
        }
        double zCenter = (minZ + maxZ) / 2;
        double footprintRadius = 0;
        for (int slot = 0; slot < count; slot++) {
            offsetZ[slot] -= zCenter;
            footprintRadius = Math.max(footprintRadius,
                    Math.hypot(offsetX[slot], offsetZ[slot]) + configuredRadii[slot]);
        }
        double meanDiameterAndGap = 0;
        for (int slot = 0; slot < count; slot++) {
            meanDiameterAndGap += 2 * configuredRadii[slot] + configuredGaps[slot];
        }
        meanDiameterAndGap /= count;
        double normalStandoff = footprintRadius + meanDiameterAndGap * 1.25;
        double maximumStandoff = Math.max(0, range * 0.85 - footprintRadius);
        stoppingDistance = Math.min(normalStandoff, maximumStandoff);
    }

    private void ensureRowCapacity(int rows) {
        if (rowMaxRadius.length >= rows) return;
        rowMaxRadius = Arrays.copyOf(rowMaxRadius, rows);
        rowMaxGap = Arrays.copyOf(rowMaxGap, rows);
        rowMinX = Arrays.copyOf(rowMinX, rows);
        rowMaxX = Arrays.copyOf(rowMaxX, rows);
    }

    private static double sizeAt(double[] values, int slot) {
        if (values == null || slot >= values.length || !Double.isFinite(values[slot])) return 0;
        return Math.max(0, values[slot]);
    }
}
