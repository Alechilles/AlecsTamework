package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonElement;
import com.hypixel.hytale.server.npc.asset.builder.BuilderDescriptorState;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.asset.builder.holder.StringHolder;

/**
 * Builder for ActionTameworkDebugMessage.
 */
public final class BuilderActionTameworkDebugMessage extends TameworkActionBuilderBase {
    public static final String BUILDER_ID = "TameworkDebugMessage";

    private final StringHolder message = new StringHolder();

    @Override
    public String getBuilderId() {
        return BUILDER_ID;
    }

    @Override
    public BuilderActionTameworkDebugMessage readConfig(JsonElement element) {
        if (element == null) {
            return this;
        }
        getString(
                element,
                "Message",
                message,
                "",
                null,
                BuilderDescriptorState.Stable,
                "Message to print with the NPC UUID when this action executes.",
                null
        );
        return this;
    }

    public String getMessage(BuilderSupport support) {
        return message.get(support.getExecutionContext());
    }

    public ActionTameworkDebugMessage build(BuilderSupport support) {
        return new ActionTameworkDebugMessage(this, support);
    }

    public String getShortDescription() {
        return "Writes a debug message to the server log with NPC UUID context.";
    }

    public String getLongDescription() {
        return "Custom action that logs an operator-facing debug probe message when triggered.";
    }
}
