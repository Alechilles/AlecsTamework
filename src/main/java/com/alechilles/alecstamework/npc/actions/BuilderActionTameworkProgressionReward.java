package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;

/**
 * Builder for ActionTameworkProgressionReward.
 */
public final class BuilderActionTameworkProgressionReward extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkProgressionReward";
    private final DoubleHolder happiness = new DoubleHolder();
    private final DoubleHolder xp = new DoubleHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkProgressionReward readConfig(JsonElement element) {
        getDouble(element, "Happiness", happiness, 0.0, null,
                BuilderDescriptorState.Stable,
                "Happiness added at once; negative values lower it. Needs an enabled happiness config.", null);
        getDouble(element, "Xp", xp, 0.0, DoubleSingleValidator.greaterEqual0(),
                BuilderDescriptorState.Stable,
                "Companion XP awarded. Needs an enabled leveling config for the role.", null);
        requireInstructionType(InstructionType.NPCOnlyInstructions);
        return this;
    }

    public double getHappiness(BuilderSupport support) {
        return happiness.get(support.getExecutionContext());
    }

    public double getXp(BuilderSupport support) {
        return xp.get(support.getExecutionContext());
    }

    @Override
    public ActionTameworkProgressionReward build(BuilderSupport support) {
        return new ActionTameworkProgressionReward(this, support);
    }

    @Override
    public String getShortDescription() {
        return "Grants happiness and companion XP to this NPC.";
    }

    @Override
    public String getLongDescription() {
        return "Adds an immediate happiness change and awards companion XP to the NPC running the instruction. "
                + "Each part does nothing when its amount is zero or the role has no matching progression config. "
                + "Gate repeats with a timer or alarm in the role.";
    }
}
