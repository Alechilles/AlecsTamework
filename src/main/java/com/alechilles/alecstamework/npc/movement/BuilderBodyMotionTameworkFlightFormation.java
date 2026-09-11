package com.alechilles.alecstamework.npc.movement;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.EnumHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleRangeValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderBodyMotionBase;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/** Builds autonomous flight steering that holds a follower in a flock formation slot. */
public final class BuilderBodyMotionTameworkFlightFormation extends BuilderBodyMotionBase {
    public static final String BUILDER_ID = "TameworkFlightFormation";

    private final EnumHolder<Formation> formation = new EnumHolder<>();
    private final DoubleHolder spacing = new DoubleHolder();
    private final DoubleHolder tightness = new DoubleHolder();
    private final DoubleHolder relativeSpeed = new DoubleHolder();

    @Nonnull
    @Override
    public BuilderBodyMotionTameworkFlightFormation readConfig(@Nonnull JsonElement data) {
        super.readConfig(data);
        getEnum(data, "Formation", formation, Formation.class, Formation.NONE,
                BuilderDescriptorState.WorkInProgress,
                "Flight formation: None, Loose, or Chevron.", null);
        getDouble(data, "Spacing", spacing, 3.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.WorkInProgress,
                "Distance between neighboring formation slots.", null);
        getDouble(data, "Tightness", tightness, 0.6, DoubleRangeValidator.fromExclToIncl(0.0, 1.0),
                BuilderDescriptorState.WorkInProgress,
                "How strongly followers correct toward their assigned slot.", null);
        getDouble(data, "RelativeSpeed", relativeSpeed, 0.8, DoubleRangeValidator.fromExclToIncl(0.0, 1.0),
                BuilderDescriptorState.WorkInProgress,
                "Maximum additive catch-up speed used for formation position correction, relative to maximum flight speed.", null);
        return this;
    }

    @Nonnull
    @Override
    public BodyMotionTameworkFlightFormation build(@Nonnull BuilderSupport builderSupport) {
        return new BodyMotionTameworkFlightFormation(this, builderSupport);
    }

    @Nonnull
    @Override
    public String getShortDescription() {
        return "Maintain a loose or chevron flight formation behind the native flock leader.";
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

    Formation getFormation(@Nonnull BuilderSupport support) {
        return formation.get(support.getExecutionContext());
    }

    double getSpacing(@Nonnull BuilderSupport support) {
        return spacing.get(support.getExecutionContext());
    }

    double getTightness(@Nonnull BuilderSupport support) {
        return tightness.get(support.getExecutionContext());
    }

    double getRelativeSpeed(@Nonnull BuilderSupport support) {
        return relativeSpeed.get(support.getExecutionContext());
    }

    public enum Formation implements Supplier<String> {
        NONE("None"),
        LOOSE("Loose"),
        CHEVRON("Chevron");

        private final String name;

        Formation(String name) {
            this.name = name;
        }

        @Override
        public String get() {
            return name;
        }
    }
}
