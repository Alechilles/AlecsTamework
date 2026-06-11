package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkReachableBlockTarget;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builder for SensorTameworkReachableBlockTarget.
 */
public final class BuilderSensorTameworkReachableBlockTarget extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkReachableBlockTarget";

    private final StringHolder blockSet = new StringHolder();
    private final StringHolder blocks = new StringHolder();
    private final StringArrayHolder blockTypes = new StringArrayHolder();
    private final StringHolder label = new StringHolder();
    private final DoubleHolder range = new DoubleHolder();
    private final DoubleHolder verticalRadius = new DoubleHolder();
    private final DoubleHolder approachRadius = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkReachableBlockTarget(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Finds a reachable nearby block target and exposes its approach position.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Scans for matching blocks, projects bounded approach candidates, and path-preflights them before movement.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkReachableBlockTarget readConfig(@Nonnull JsonElement data) {
        this.getString(
                data,
                "BlockSet",
                this.blockSet,
                null,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional block set id to match.",
                null
        );
        this.getString(
                data,
                "Blocks",
                this.blocks,
                null,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional vanilla-style block set alias.",
                null
        );
        this.getStringArray(
                data,
                "BlockTypes",
                this.blockTypes,
                null,
                0,
                Integer.MAX_VALUE,
                null,
                BuilderDescriptorState.Stable,
                "Optional exact block type ids to match.",
                null
        );
        this.getString(
                data,
                "Label",
                this.label,
                "Block",
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Target cache label used for path preflight and temporary rejection.",
                null
        );
        this.getDouble(
                data,
                "Range",
                this.range,
                12.0,
                null,
                BuilderDescriptorState.Stable,
                "Horizontal search radius in blocks.",
                null
        );
        this.getDouble(
                data,
                "VerticalRadius",
                this.verticalRadius,
                4.0,
                null,
                BuilderDescriptorState.Stable,
                "Vertical search radius in blocks.",
                null
        );
        this.getDouble(
                data,
                "ApproachRadius",
                this.approachRadius,
                SensorTameworkReachableBlockTarget.DEFAULT_APPROACH_RADIUS,
                null,
                BuilderDescriptorState.Stable,
                "Maximum projected approach distance around the source block.",
                null
        );
        return this;
    }

    @Nullable
    public String getBlockSet(@Nonnull BuilderSupport support) {
        return firstNonBlank(
                this.blockSet.get(support.getExecutionContext()),
                this.blocks.get(support.getExecutionContext())
        );
    }

    @Nonnull
    public String[] getBlockTypes(@Nonnull BuilderSupport support) {
        String[] value = this.blockTypes.get(support.getExecutionContext());
        return value == null ? new String[0] : value;
    }

    @Nonnull
    public String getLabel(@Nonnull BuilderSupport support) {
        String value = this.label.get(support.getExecutionContext());
        return value == null || value.isBlank() ? "Block" : value;
    }

    public double getRange(@Nonnull BuilderSupport support) {
        return this.range.get(support.getExecutionContext());
    }

    public double getVerticalRadius(@Nonnull BuilderSupport support) {
        return this.verticalRadius.get(support.getExecutionContext());
    }

    public double getApproachRadius(@Nonnull BuilderSupport support) {
        return this.approachRadius.get(support.getExecutionContext());
    }

    @Nullable
    private static String firstNonBlank(@Nullable String primary, @Nullable String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }
}
