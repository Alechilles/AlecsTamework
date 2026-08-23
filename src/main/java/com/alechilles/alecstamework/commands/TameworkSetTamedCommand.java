package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Command to set or toggle tamed status on the targeted NPC.
 */
public final class TameworkSetTamedCommand extends AbstractPlayerCommand {

    public TameworkSetTamedCommand() {
        super("tamed", "Set or toggle tamed status of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.raw("Tamed component is not available."));
            return;
        }

        boolean current = false;
        TameworkTamedComponent existing = store.getComponent(candidate.ref, type);
        if (existing != null) {
            current = existing.isTamed();
        }

        String raw = getFirstArg(commandContext);
        Boolean parsed = parseBoolean(raw);
        boolean next = parsed != null ? parsed : !current;

        store.putComponent(candidate.ref, type, new TameworkTamedComponent(next));
        commandContext.sender().sendMessage(Message.raw("Set tamed for NPC " + candidate.npcUuid + " to " + next));
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if ("toggle".equals(value)) {
            return null;
        }
        if ("true".equals(value) || "1".equals(value) || "on".equals(value) || "yes".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "0".equals(value) || "off".equals(value) || "no".equals(value)) {
            return false;
        }
        return null;
    }
}
