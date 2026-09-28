package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import com.hypixel.hytale.server.npc.asset.builder.validators.StringNotEmptyValidator;

/** Configures a centered particle beam that follows native head aiming. */
public final class BuilderActionTameworkBeam extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkBeam";
    private final DoubleHolder range = new DoubleHolder();
    private final DoubleHolder damage = new DoubleHolder();
    private final DoubleHolder damageInterval = new DoubleHolder();
    private final DoubleHolder particleNativeLength = new DoubleHolder();
    private final DoubleHolder originHeight = new DoubleHolder();
    private final DoubleHolder originForward = new DoubleHolder();
    private final DoubleHolder beamRadius = new DoubleHolder();
    private final StringHolder particleSystem = new StringHolder();
    private final StringHolder targetSlot = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkBeam readConfig(JsonElement element) {
        getDouble(element, "Range", range, 30, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Maximum terrain-clipped beam length.", null);
        getDouble(element, "Damage", damage, 8, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.Stable, "Fire damage per pulse; zero creates a harmless effect.", null);
        getDouble(element, "DamageInterval", damageInterval, 0.25, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Seconds between damage pulses, with a minimum of 0.25.", null);
        requireString(element, "ParticleSystem", particleSystem, StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable, "Particle system centered on the beam midpoint.", null);
        getDouble(element, "ParticleNativeLength", particleNativeLength, 32, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Particle length at scale one, used to scale the clipped beam.", null);
        getDouble(element, "OriginHeight", originHeight, 2.25, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.Stable, "Beam origin height above the NPC position.", null);
        getDouble(element, "OriginForward", originForward, 0.7, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.Stable, "Horizontal origin offset along actual head yaw.", null);
        getDouble(element, "BeamRadius", beamRadius, 0.2, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.Stable, "Radius added to player bounds when testing the beam.", null);
        getString(element, "TargetSlot", targetSlot, "LockedTarget", StringNotEmptyValidator.get(),
                BuilderDescriptorState.Stable, "Marked target whose loss stops this beam.", null);
        requireInstructionType(InstructionType.NPCOnlyInstructions);
        return this;
    }

    public double getRange(BuilderSupport support) { return range.get(support.getExecutionContext()); }
    public double getDamage(BuilderSupport support) { return damage.get(support.getExecutionContext()); }
    public double getDamageInterval(BuilderSupport support) { return damageInterval.get(support.getExecutionContext()); }
    public String getParticleSystem(BuilderSupport support) { return particleSystem.get(support.getExecutionContext()); }
    public double getParticleNativeLength(BuilderSupport support) { return particleNativeLength.get(support.getExecutionContext()); }
    public double getOriginHeight(BuilderSupport support) { return originHeight.get(support.getExecutionContext()); }
    public double getOriginForward(BuilderSupport support) { return originForward.get(support.getExecutionContext()); }
    public double getBeamRadius(BuilderSupport support) { return beamRadius.get(support.getExecutionContext()); }
    public int getTargetSlot(BuilderSupport support) {
        return support.getTargetSlot(targetSlot.get(support.getExecutionContext()));
    }

    @Override
    public ActionTameworkBeam build(BuilderSupport support) {
        return new ActionTameworkBeam(this, support);
    }

    @Override
    public String getShortDescription() {
        return "Emits a terrain-clipped beam along native head rotation.";
    }

    @Override
    public String getLongDescription() {
        return "Emits at most ten times per second and damages intersecting players at most four times per second. "
                + "Native head motion controls aiming; losing the marked target or dying stops the action.";
    }
}
