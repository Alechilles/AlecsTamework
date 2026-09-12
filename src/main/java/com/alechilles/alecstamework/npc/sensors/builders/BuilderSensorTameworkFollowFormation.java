package com.alechilles.alecstamework.npc.sensors.builders;

import com.alechilles.alecstamework.npc.sensors.SensorTameworkFollowFormation;
import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;
import com.hypixel.hytale.server.npc.instructions.Sensor;
import javax.annotation.Nonnull;

/** A position target in the verified owner's native flock. */
public final class BuilderSensorTameworkFollowFormation extends TameworkSensorBuilderBase {
    public static final String BUILDER_ID = "TameworkFollowFormation";
    private final StringHolder targetSlot = new StringHolder();
    private final DoubleHolder range = new DoubleHolder();
    private final DoubleHolder spacing = new DoubleHolder();
    private final DoubleHolder altitude = new DoubleHolder();

    @Override public String getBuilderId() { return BUILDER_ID; }
    @Nonnull @Override public Sensor build(@Nonnull BuilderSupport support) {
        return new SensorTameworkFollowFormation(this, support.getTargetSlot(targetSlot.get(support.getExecutionContext())),
                range.get(support.getExecutionContext()), spacing.get(support.getExecutionContext()),
                altitude.get(support.getExecutionContext()));
    }
    @Nonnull @Override public BuilderSensorTameworkFollowFormation readConfig(@Nonnull JsonElement data) {
        getString(data, "TargetSlot", targetSlot, "MasterTarget", StringNotEmptyValidator.get(),
                BuilderDescriptorState.WorkInProgress, "Owner target slot.", null);
        getDouble(data, "Range", range, 25, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Owner distance beyond which ordinary catch-up takes over.", null);
        getDouble(data, "Spacing", spacing, 4, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress, "Minimum formation spacing; grows for larger companions.", null);
        getDouble(data, "Altitude", altitude, 5, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.WorkInProgress, "Flying slot height above the owner; ignored while walking.", null);
        return this;
    }
    @Nonnull @Override public String getShortDescription() {
        return "Provide a stable formation position while a tamed companion follows its owner.";
    }
    @Nonnull @Override public String getLongDescription() { return getShortDescription(); }
}
