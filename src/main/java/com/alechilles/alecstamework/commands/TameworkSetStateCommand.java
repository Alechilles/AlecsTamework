package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Sets an NPC's role state by UUID, as a command item would, so the role's state transitions run.
 * Usage: {@code tw debug set state <npc uuid> <State>}. Works from the console.
 */
public final class TameworkSetStateCommand extends AbstractWorldCommand {
    public TameworkSetStateCommand() {
        super("state", "server.tamework.commands.setState.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        String[] args = TameworkCommandInput.argumentsAfter(commandContext.getInputString(), "state");
        UUID uuid = args.length == 2 ? parseUuid(args[0]) : null;
        if (uuid == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setState.usage"));
            return;
        }
        String state = args[1];
        Ref<EntityStore> ref = world.getEntityRef(uuid);
        NPCEntity npc = ref != null && ref.isValid() ? store.getComponent(ref, NPCEntity.getComponentType()) : null;
        if (npc == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setState.npc.not.found")
                    .param("0", uuid.toString()));
            return;
        }
        StateSupport support = NpcSupportAccess.state(npc.getRole(), ref, store);
        if (support == null || support.getStateHelper() == null
                || support.getStateHelper().getStateIndex(state) == StateSupport.NO_STATE) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setState.unknown.state")
                    .param("0", state)
                    .param("1", String.valueOf(npc.getRoleName())));
            return;
        }
        support.setState(ref, state, support.getStateHelper().getDefaultSubState(), store);
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setState.done")
                .param("0", uuid.toString())
                .param("1", state));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
