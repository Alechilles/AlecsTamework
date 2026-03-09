package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.MembershipMode;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/**
 * Reads and writes command-panel preferences stored per command tool.
 */
final class CommandPanelPreferenceService {
    static final int PANEL_SCHEMA_VERSION = 1;
    private static final double DEFAULT_NEARBY_RADIUS = 24.0;
    private static final double MIN_NEARBY_RADIUS = 8.0;
    private static final double MAX_NEARBY_RADIUS = 96.0;
    private static final double NEARBY_RADIUS_STEP = 4.0;

    enum PanelMode {
        LinkedMode,
        NearbyMode;

        static PanelMode fromMetadata(String raw) {
            if (raw == null || raw.isBlank()) {
                return LinkedMode;
            }
            for (PanelMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return LinkedMode;
        }
    }

    @Nullable
    PanelMode readPanelModeOverride(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String raw = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_PANEL_MODE, Codec.STRING);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return PanelMode.fromMetadata(raw);
    }

    PanelMode resolveEffectivePanelMode(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        PanelMode override = readPanelModeOverride(stack);
        if (override != null) {
            return override;
        }
        MembershipMode fallbackMembership = config != null && config.getMembershipMode() != null
                ? config.getMembershipMode()
                : MembershipMode.LinkedOnly;
        return switch (fallbackMembership) {
            case OwnerScope -> PanelMode.NearbyMode;
            case LinkedOnly, MasterTarget, LinkedOrMasterTarget -> PanelMode.LinkedMode;
        };
    }

    MembershipMode resolveRecipientMembershipMode(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        PanelMode override = readPanelModeOverride(stack);
        if (override == PanelMode.NearbyMode) {
            return MembershipMode.OwnerScope;
        }
        if (override == PanelMode.LinkedMode) {
            return MembershipMode.LinkedOnly;
        }
        return config != null && config.getMembershipMode() != null
                ? config.getMembershipMode()
                : MembershipMode.LinkedOnly;
    }

    ItemStack togglePanelMode(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelMode current = resolveEffectivePanelMode(stack, config);
        PanelMode next = current == PanelMode.LinkedMode ? PanelMode.NearbyMode : PanelMode.LinkedMode;
        return setPanelMode(stack, next);
    }

    ItemStack setPanelMode(@Nullable ItemStack stack, PanelMode mode) {
        if (stack == null || stack.isEmpty() || mode == null) {
            return stack;
        }
        ItemStack updated = stack.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_SCHEMA_VERSION,
                Codec.INTEGER,
                PANEL_SCHEMA_VERSION
        );
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_MODE, Codec.STRING, mode.name());
    }

    double resolveNearbyRadius(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        double fallback = resolveConfiguredRadius(config);
        if (stack == null || stack.isEmpty()) {
            return fallback;
        }
        Double raw = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_PANEL_RADIUS, Codec.DOUBLE);
        if (raw == null || !Double.isFinite(raw)) {
            return fallback;
        }
        return clampToStep(raw);
    }

    ItemStack stepNearbyRadius(@Nullable ItemStack stack,
                               @Nullable TwCommandItemConfig config,
                               boolean increase) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        double current = resolveNearbyRadius(stack, config);
        double next = current + (increase ? NEARBY_RADIUS_STEP : -NEARBY_RADIUS_STEP);
        next = clampToStep(next);
        ItemStack updated = stack.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_SCHEMA_VERSION,
                Codec.INTEGER,
                PANEL_SCHEMA_VERSION
        );
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_RADIUS, Codec.DOUBLE, next);
    }

    String resolveModeLabel(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        PanelMode mode = resolveEffectivePanelMode(stack, config);
        return mode == PanelMode.NearbyMode ? "Mode: Nearby" : "Mode: Linked";
    }

    String resolveRadiusLabel(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        int radius = (int) Math.round(resolveNearbyRadius(stack, config));
        return "Radius: " + radius + "m";
    }

    private double resolveConfiguredRadius(@Nullable TwCommandItemConfig config) {
        if (config == null) {
            return DEFAULT_NEARBY_RADIUS;
        }
        double configRadius = config.getRadius();
        if (!Double.isFinite(configRadius) || configRadius <= 0.0) {
            return DEFAULT_NEARBY_RADIUS;
        }
        return clampToStep(configRadius);
    }

    private double clampToStep(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_NEARBY_RADIUS;
        }
        double clamped = Math.max(MIN_NEARBY_RADIUS, Math.min(MAX_NEARBY_RADIUS, value));
        double stepped = Math.round(clamped / NEARBY_RADIUS_STEP) * NEARBY_RADIUS_STEP;
        return Math.max(MIN_NEARBY_RADIUS, Math.min(MAX_NEARBY_RADIUS, stepped));
    }
}
