package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Command to display tamed status for the targeted NPC.
 */
public final class TameworkGetTamedCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkGetTamedCommand() {
        super("tamed", "server.tamework.commands.getTamed.description",
                "server.tamework.commands.getTamed.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);

        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getTamed.tamed.for.npc.is").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(tamed)));
    }
}
