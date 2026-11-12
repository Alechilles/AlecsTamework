package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Command to set or toggle tamed status on the targeted NPC.
 */
public final class TameworkSetTamedCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkSetTamedCommand() {
        super("tamed", "server.tamework.commands.setTamed.description",
                "server.tamework.commands.setTamed.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        ComponentType<EntityStore, TameworkTamedComponent> type = TameworkTamedComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTamed.tamed.component.is.not.available"));
            return;
        }

        boolean current = false;
        TameworkTamedComponent existing = store.getComponent(npcRef, type);
        if (existing != null) {
            current = existing.isTamed();
        }

        String raw = getFirstArg(commandContext.getInputString());
        Boolean next = resolveRequestedState(raw, current);
        if (next == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.invalidToggle"));
            return;
        }

        store.putComponent(npcRef, type, new TameworkTamedComponent(next));
        if (!commandContext.isPlayer()) {
            commandContext.sendMessage(Message.raw("ok entity=" + npc.getUuid() + " tamed=" + current + "->" + next));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTamed.set.tamed.for.npc.to").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(next)));
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
