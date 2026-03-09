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
    private static final int MAX_FILTER_TEXT_LENGTH = 40;

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

    enum PanelSort {
        Default,
        Name,
        Species,
        Group;

        static PanelSort fromMetadata(String raw) {
            if (raw == null || raw.isBlank()) {
                return Default;
            }
            for (PanelSort mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return Default;
        }
    }

    enum PanelFilterMode {
        None,
        Name,
        Species,
        Group;

        static PanelFilterMode fromMetadata(String raw) {
            if (raw == null || raw.isBlank()) {
                return None;
            }
            for (PanelFilterMode mode : values()) {
                if (mode.name().equalsIgnoreCase(raw.trim())) {
                    return mode;
                }
            }
            return None;
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
        ItemStack updated = withSchemaVersion(stack);
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_MODE, Codec.STRING, mode.name());
    }

    ItemStack setPanelMode(@Nullable ItemStack stack, @Nullable String rawMode) {
        return setPanelMode(stack, PanelMode.fromMetadata(rawMode));
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
        ItemStack updated = withSchemaVersion(stack);
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_RADIUS, Codec.DOUBLE, next);
    }

    String resolveModeLabel(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        PanelMode mode = resolveEffectivePanelMode(stack, config);
        return mode == PanelMode.NearbyMode ? "Mode: Nearby" : "Mode: Linked";
    }

    String resolveModeValue(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        return resolveEffectivePanelMode(stack, config).name();
    }

    String resolveRadiusLabel(@Nullable ItemStack stack, @Nullable TwCommandItemConfig config) {
        int radius = (int) Math.round(resolveNearbyRadius(stack, config));
        return "Radius: " + radius + "m";
    }

    PanelSort resolveSort(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return PanelSort.Default;
        }
        String raw = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_PANEL_SORT, Codec.STRING);
        return PanelSort.fromMetadata(raw);
    }

    String resolveSortValue(@Nullable ItemStack stack) {
        return resolveSort(stack).name();
    }

    ItemStack cycleSort(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelSort current = resolveSort(stack);
        PanelSort[] values = PanelSort.values();
        int nextIndex = (current.ordinal() + 1) % values.length;
        ItemStack updated = withSchemaVersion(stack);
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_SORT, Codec.STRING, values[nextIndex].name());
    }

    ItemStack setSort(@Nullable ItemStack stack, @Nullable String rawSort) {
        return setSort(stack, PanelSort.fromMetadata(rawSort));
    }

    ItemStack setSort(@Nullable ItemStack stack, @Nullable PanelSort sort) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelSort normalized = sort != null ? sort : PanelSort.Default;
        ItemStack updated = withSchemaVersion(stack);
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_SORT, Codec.STRING, normalized.name());
    }

    String resolveSortLabel(@Nullable ItemStack stack) {
        PanelSort sort = resolveSort(stack);
        return "Sort: " + sort.name();
    }

    String resolveNameFilter(@Nullable ItemStack stack) {
        return readFilterValue(stack, TameworkMetadataKeys.COMMAND_PANEL_FILTER_NAME);
    }

    String resolveSpeciesFilter(@Nullable ItemStack stack) {
        return readFilterValue(stack, TameworkMetadataKeys.COMMAND_PANEL_FILTER_SPECIES);
    }

    String resolveGroupFilter(@Nullable ItemStack stack) {
        return readFilterValue(stack, TameworkMetadataKeys.COMMAND_PANEL_FILTER_GROUP);
    }

    PanelFilterMode resolveFilterMode(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return PanelFilterMode.None;
        }
        String raw = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_PANEL_FILTER_MODE, Codec.STRING);
        PanelFilterMode stored = PanelFilterMode.fromMetadata(raw);
        if (stored != PanelFilterMode.None) {
            return stored;
        }
        if (!isBlank(resolveNameFilter(stack))) {
            return PanelFilterMode.Name;
        }
        if (!isBlank(resolveSpeciesFilter(stack))) {
            return PanelFilterMode.Species;
        }
        if (!isBlank(resolveGroupFilter(stack))) {
            return PanelFilterMode.Group;
        }
        return PanelFilterMode.None;
    }

    String resolveFilterModeValue(@Nullable ItemStack stack) {
        return resolveFilterMode(stack).name();
    }

    ItemStack setFilterMode(@Nullable ItemStack stack, @Nullable String rawMode) {
        return setFilterMode(stack, PanelFilterMode.fromMetadata(rawMode));
    }

    ItemStack setFilterMode(@Nullable ItemStack stack, @Nullable PanelFilterMode mode) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelFilterMode normalized = mode != null ? mode : PanelFilterMode.None;
        String preserved = switch (normalized) {
            case Name -> resolveNameFilter(stack);
            case Species -> resolveSpeciesFilter(stack);
            case Group -> resolveGroupFilter(stack);
            case None -> "";
        };
        return setSingleFilter(stack, normalized, preserved);
    }

    String resolveSelectedFilterInput(@Nullable ItemStack stack) {
        PanelFilterMode mode = resolveFilterMode(stack);
        return switch (mode) {
            case Name -> resolveNameFilter(stack);
            case Species -> resolveSpeciesFilter(stack);
            case Group -> resolveGroupFilter(stack);
            case None -> "";
        };
    }

    ItemStack applySelectedFilterText(@Nullable ItemStack stack, @Nullable String value) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelFilterMode mode = resolveFilterMode(stack);
        return setSingleFilter(stack, mode, value);
    }

    boolean hasActiveFilters(@Nullable ItemStack stack) {
        return !isBlank(resolveNameFilter(stack))
                || !isBlank(resolveSpeciesFilter(stack))
                || !isBlank(resolveGroupFilter(stack));
    }

    String resolveFilterSummaryLabel(@Nullable ItemStack stack) {
        int active = 0;
        if (!isBlank(resolveNameFilter(stack))) {
            active++;
        }
        if (!isBlank(resolveSpeciesFilter(stack))) {
            active++;
        }
        if (!isBlank(resolveGroupFilter(stack))) {
            active++;
        }
        return active <= 0 ? "Filters: none" : "Filters: " + active + " active";
    }

    ItemStack clearFilters(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        ItemStack updated = withSchemaVersion(stack);
        updated = updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_FILTER_MODE, Codec.STRING, PanelFilterMode.None.name());
        updated = updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_FILTER_NAME, Codec.STRING, "");
        updated = updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_FILTER_SPECIES, Codec.STRING, "");
        return updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_FILTER_GROUP, Codec.STRING, "");
    }

    ItemStack setNameFilter(@Nullable ItemStack stack, @Nullable String value) {
        return setSingleFilter(stack, PanelFilterMode.Name, value);
    }

    ItemStack setSpeciesFilter(@Nullable ItemStack stack, @Nullable String value) {
        return setSingleFilter(stack, PanelFilterMode.Species, value);
    }

    ItemStack setGroupFilter(@Nullable ItemStack stack, @Nullable String value) {
        return setSingleFilter(stack, PanelFilterMode.Group, value);
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

    private String readFilterValue(@Nullable ItemStack stack, String key) {
        if (stack == null || stack.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        String raw = stack.getFromMetadataOrNull(key, Codec.STRING);
        return normalizeFilterText(raw);
    }

    private ItemStack setSingleFilter(@Nullable ItemStack stack,
                                      @Nullable PanelFilterMode mode,
                                      @Nullable String value) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        PanelFilterMode normalizedMode = mode != null ? mode : PanelFilterMode.None;
        String normalizedValue = normalizeFilterText(value);
        ItemStack updated = withSchemaVersion(stack);
        updated = updated.withMetadata(TameworkMetadataKeys.COMMAND_PANEL_FILTER_MODE, Codec.STRING, normalizedMode.name());
        updated = updated.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_FILTER_NAME,
                Codec.STRING,
                normalizedMode == PanelFilterMode.Name ? normalizedValue : ""
        );
        updated = updated.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_FILTER_SPECIES,
                Codec.STRING,
                normalizedMode == PanelFilterMode.Species ? normalizedValue : ""
        );
        return updated.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_FILTER_GROUP,
                Codec.STRING,
                normalizedMode == PanelFilterMode.Group ? normalizedValue : ""
        );
    }

    private ItemStack withSchemaVersion(ItemStack stack) {
        return stack.withMetadata(
                TameworkMetadataKeys.COMMAND_PANEL_SCHEMA_VERSION,
                Codec.INTEGER,
                PANEL_SCHEMA_VERSION
        );
    }

    private String normalizeFilterText(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_FILTER_TEXT_LENGTH) {
            return trimmed.substring(0, MAX_FILTER_TEXT_LENGTH);
        }
        return trimmed;
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
