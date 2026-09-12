package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.BooleanHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleRangeValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import javax.annotation.Nonnull;

/** Builds ground-only flock steering that places followers in loose slots behind their leader. */
public final class BuilderBodyMotionTameworkGroundFormation extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkGroundFormation";

    private final DoubleHolder spacing = new DoubleHolder();
    private final DoubleHolder slotTolerance = new DoubleHolder();
    private final DoubleHolder tightness = new DoubleHolder();
    private final DoubleHolder relativeSpeed = new DoubleHolder();
    private final DoubleHolder homeRange = new DoubleHolder();
    private final BooleanHolder lead = new BooleanHolder();

    @Nonnull
    @Override
    public BuilderBodyMotionTameworkGroundFormation readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getBoolean(data, "Lead", lead, false, BuilderDescriptorState.WorkInProgress,
                "Hold an initial travel heading instead of following a flock slot.", null);
        getDouble(data, "HomeRange", homeRange, 0.0, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.WorkInProgress,
                "Start journeys toward the leash point beyond this distance; zero disables the home bias.", null);
        getDouble(data, "Spacing", spacing, 5.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress,
                "Distance between neighboring ground formation slots.", null);
        getDouble(data, "SlotTolerance", slotTolerance, 0.0, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.WorkInProgress,
                "Allowed slot drift in blocks; zero uses 30 percent of spacing.", null);
        getDouble(data, "Tightness", tightness, 0.35,
                DoubleRangeValidator.fromExclToIncl(0.0, 1.0),
                BuilderDescriptorState.WorkInProgress,
                "How strongly followers correct toward their assigned slot.", null);
        getDouble(data, "RelativeSpeed", relativeSpeed, 0.25,
                DoubleRangeValidator.fromExclToIncl(0.0, 1.0),
                BuilderDescriptorState.WorkInProgress,
                "Leader walking speed, or maximum follower catch-up speed, relative to maximum walking speed.", null);
        return this;
    }

    @Nonnull
    @Override
    public BodyMotionTameworkGroundFormation build(@Nonnull BuilderSupport builderSupport) {
        return new BodyMotionTameworkGroundFormation(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Maintain a loose ground formation behind the native flock leader.";
    }

    @Nonnull
    @Override
    public String getLongDescription() {
        return getShortDescription();
    }

    @Nonnull
    @Override
    public BuilderDescriptorState getBuilderDescriptorState() {
        return BuilderDescriptorState.WorkInProgress;
    }

    double getSpacing(@Nonnull BuilderSupport support) {
        return spacing.get(support.getExecutionContext());
    }

    double getSlotTolerance(@Nonnull BuilderSupport support) {
        return slotTolerance.get(support.getExecutionContext());
    }

    double getTightness(@Nonnull BuilderSupport support) {
        return tightness.get(support.getExecutionContext());
    }

    double getRelativeSpeed(@Nonnull BuilderSupport support) {
        return relativeSpeed.get(support.getExecutionContext());
    }

    double getHomeRange(@Nonnull BuilderSupport support) {
        return homeRange.get(support.getExecutionContext());
    }

    boolean isLead(@Nonnull BuilderSupport support) {
        return lead.get(support.getExecutionContext());
    }
}
