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
        super("tamed", "server.tamework.commands.setTamed.description");
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTamed.no.npc.found.in.view"));
            return;
        }

        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTamed.tamed.component.is.not.available"));
            return;
        }

        boolean current = false;
        TameworkTamedComponent existing = store.getComponent(candidate.ref, type);
        if (existing != null) {
            current = existing.isTamed();
        }

        String raw = getFirstArg(commandContext.getInputString());
        Boolean next = resolveRequestedState(raw, current);
        if (next == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }

        store.putComponent(candidate.ref, type, new TameworkTamedComponent(next));
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTamed.set.tamed.for.npc.to").param("0", String.valueOf(candidate.npcUuid)).param("1", String.valueOf(next)));
    }

    static Boolean resolveRequestedState(String raw, boolean current) {
        Boolean parsed = parseBoolean(raw);
        if (parsed != null) {
            return parsed;
        }
        return TameworkCommandInput.isToggleRequest(raw) ? !current : null;
    }

    static String getFirstArg(String input) {
        return TameworkCommandInput.firstArgument(input, "tamed");
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
