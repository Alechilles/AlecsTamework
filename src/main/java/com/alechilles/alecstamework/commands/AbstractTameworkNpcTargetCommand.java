package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.commands.NPCWorldCommandBase;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import javax.annotation.Nonnull;

/**
 * Base for debug commands that act on one NPC, on the thread of that NPC's world. {@code --entity <uuid>}
 * names the NPC and works from the console (from the console add {@code --world <name>} whenever more than one world is loaded).
 * Without it a player picks the NPC by looking at it, with Tamework's own view targeting, as these
 * commands always have; the console is refused with one line.
 */
abstract class AbstractTameworkNpcTargetCommand extends NPCWorldCommandBase {
    /** The console refusal when no NPC is named. */
    static final String ENTITY_REQUIRED = "refused reason=entity-required option=--entity";

    private final String noTargetKey;

    /** {@code noTargetKey} is the translation sent to a player who looks at no NPC. */
    AbstractTameworkNpcTargetCommand(@Nonnull String name, @Nonnull String description, @Nonnull String noTargetKey) {
        super(name, description);
        this.noTargetKey = noTargetKey;
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull World world, @Nonnull Store<EntityStore> store) {
        if (entityArg.provided(context)) {
            super.execute(context, world, store);
            return;
        }
        Ref<EntityStore> playerRef = context.isPlayer() ? context.senderAsPlayerRef() : null;
        if (playerRef == null || !playerRef.isValid()) {
            context.sendMessage(Message.raw(ENTITY_REQUIRED));
            return;
        }
        // A player who names another world with --world looks at nothing there.
        TameworkCommandTargeting.Candidate candidate = playerRef.getStore() == store
                ? TameworkCommandTargeting.findTargetNpc(store, playerRef)
                : null;
        NPCEntity npc = candidate == null || candidate.ref == null || !candidate.ref.isValid()
                ? null
                : store.getComponent(candidate.ref, NPCEntity.getComponentType());
        if (npc == null) {
            context.sendMessage(Message.translation(noTargetKey));
            return;
        }
        execute(context, npc, world, store, candidate.ref);
    }
}
