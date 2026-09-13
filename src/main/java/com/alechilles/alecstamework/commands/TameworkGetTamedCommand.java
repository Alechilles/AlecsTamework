package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.TamedStateResolver;
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
 * Command to display tamed status for the targeted NPC.
 */
public final class TameworkGetTamedCommand extends AbstractPlayerCommand {

    public TameworkGetTamedCommand() {
        super("tamed", "server.tamework.commands.getTamed.description");
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
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTamed.no.npc.found.in.view"));
            return;
        }

        boolean tamed = TamedStateResolver.isTamed(candidate.ref, store);

        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTamed.tamed.for.npc.is").param("0", String.valueOf(candidate.npcUuid)).param("1", String.valueOf(tamed)));
    }
}
