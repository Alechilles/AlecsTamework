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
        super("gettamed", "Get tamed status of the NPC you are looking at.");
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

        boolean tamed = TamedStateResolver.isTamed(candidate.ref, store);

        commandContext.sender().sendMessage(Message.raw("Tamed for NPC " + candidate.npcUuid + " is " + tamed));
    }
}
