package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.InstructionType;
import com.hypixel.hytale.server.npc.asset.builder.holder.DoubleHolder;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;
import com.hypixel.hytale.server.npc.asset.builder.validators.DoubleSingleValidator;
import javax.annotation.Nullable;

/** Configures a native boss bar for nearby players while this NPC action runs. */
public final class BuilderActionTameworkBossBar extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkBossBar";

    private final DoubleHolder range = new DoubleHolder();
    private final StringHolder name = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkBossBar readConfig(JsonElement element) {
        getDouble(element, "Range", range, 40.0, DoubleSingleValidator.greater0(),
                BuilderDescriptorState.Stable, "Distance within which players see this NPC's boss bar.", null);
        getString(element, "Name", name, null, null, BuilderDescriptorState.Stable,
                "Optional translation key for the boss bar name. Omit to use the NPC display name.", null);
        requireInstructionType(InstructionType.NPCOnlyInstructions);
        return this;
    }

    public double getRange(BuilderSupport support) {
        return range.get(support.getExecutionContext());
    }

    @Nullable
    public String getName(BuilderSupport support) {
        return name.get(support.getExecutionContext());
    }

    @Override
    public ActionTameworkBossBar build(BuilderSupport support) {
        return new ActionTameworkBossBar(this, support);
    }

    @Override
    public String getShortDescription() {
        return "Shows this NPC's native boss bar to nearby players.";
    }

    @Override
    public String getLongDescription() {
        return "Refreshes nearby player membership while active; native membership expiry hides the bar afterward.";
    }
}
