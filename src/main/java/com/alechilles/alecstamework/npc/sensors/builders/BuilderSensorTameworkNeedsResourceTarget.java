package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkNeedsResourceTarget;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringArrayHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNullOrNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builder for SensorTameworkNeedsResourceTarget.
 */
public final class BuilderSensorTameworkNeedsResourceTarget extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkNeedsResourceTarget";

    private final StringHolder resourceType = new StringHolder();
    private final StringHolder need = new StringHolder();
    private final DoubleHolder ratioBelow = new DoubleHolder();
    private final DoubleHolder range = new DoubleHolder();
    private final StringArrayHolder itemIds = new StringArrayHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Nonnull
    @Override
    public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkNeedsResourceTarget(this, support);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Finds a nearby needs resource target and exposes its position.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return "Resolves the nearest standable target near water or a food container and exposes it to movement actions.";
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.Stable;
    }

    @Nonnull
    @Override
    public BuilderSensorTameworkNeedsResourceTarget readConfig(@Nonnull JsonElement data) {
        this.requireString(
                data,
                "ResourceType",
                this.resourceType,
                StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Resource kind to search for: Water or FoodContainer.",
                null
        );
        this.getString(
                data,
                "Need",
                this.need,
                null,
                StringNullOrNotEmptyValidator.get(),
                BuilderDescriptorState.Stable,
                "Optional need type gate: Hunger or Thirst.",
                null
        );
        this.getDouble(
                data,
                "RatioBelow",
                this.ratioBelow,
                Double.NaN,
                null,
                BuilderDescriptorState.Stable,
                "Optional normalized need threshold gate [0..1].",
                null
        );
        this.getDouble(
                data,
                "Range",
                this.range,
                12.0,
                null,
                BuilderDescriptorState.Stable,
                "Search radius in blocks.",
                null
        );
        this.getStringArray(
                data,
                "ItemIds",
                this.itemIds,
                null,
                0,
                Integer.MAX_VALUE,
                null,
                BuilderDescriptorState.Stable,
                "Allowed food item ids for FoodContainer mode.",
                null
        );
        return this;
    }

    @Nonnull
    public String getResourceType(@Nonnull BuilderSupport support) {
        return this.resourceType.get(support.getExecutionContext());
    }

    public double getRange(@Nonnull BuilderSupport support) {
        return this.range.get(support.getExecutionContext());
    }

    @Nullable
    public String getNeed(@Nonnull BuilderSupport support) {
        return this.need.get(support.getExecutionContext());
    }

    public double getRatioBelow(@Nonnull BuilderSupport support) {
        return this.ratioBelow.get(support.getExecutionContext());
    }

    @Nonnull
    public String[] getItemIds(@Nonnull BuilderSupport support) {
        String[] value = this.itemIds.get(support.getExecutionContext());
        return value == null ? new String[0] : value;
    }
}
