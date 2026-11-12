package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to set or clear owner on the targeted NPC. The owner is a UUID (online or not), an online
 * player's name, or {@code clear}; a player who names no owner becomes the owner. The owner component
 * write goes through the same ownership systems as a tame (companion index and admission).
 */
public final class TameworkSetOwnerCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkSetOwnerCommand() {
        super("owner", "server.tamework.commands.setOwner.description",
                "server.tamework.commands.setOwner.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        boolean console = !commandContext.isPlayer();
        String raw = getFirstArg(commandContext.getInputString());
        UUID newOwner;
        String ownerName = null;
        if (raw == null || raw.isBlank()) {
            if (console) {
                commandContext.sendMessage(Message.raw("refused reason=owner-required usage=<ownerUuid|name|clear>"));
                return;
            }
            // Default: the executing player owns it.
            TameworkCommandPlayers.Named self = TameworkCommandPlayers.resolve(commandContext.sender().getUuid().toString());
            newOwner = commandContext.sender().getUuid();
            ownerName = self != null ? self.name() : null;
        } else if (isClear(raw)) {
            newOwner = null;
        } else {
            TameworkCommandPlayers.Named named = TameworkCommandPlayers.resolve(raw);
            if (named == null) {
                commandContext.sendMessage(console
                        ? Message.raw("refused reason=unknown-owner value=" + raw.trim())
                        : Message.translation("server.tamework.commands.setOwner.invalid.uuid.use.clear.or.a.valid"));
                return;
            }
            newOwner = named.uuid();
            // An offline owner given by UUID stays anonymous until a body sighting or tame names it.
            ownerName = named.name();
        }

        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setOwner.componentUnavailable"));
            return;
        }
        store.putComponent(npcRef, type, new TameworkOwnerComponent(newOwner, ownerName));
        String ownerText = newOwner == null ? "null" : newOwner.toString();
        if (console) {
            commandContext.sendMessage(Message.raw("ok entity=" + npc.getUuid()
                    + " owner=" + (newOwner == null ? "none" : newOwner.toString())));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setOwner.set.owner.for.npc.to").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(ownerText)));
    }

    static String getFirstArg(String input) {
        return TameworkCommandInput.firstArgument(input, "owner");
    }

    private static boolean isClear(String raw) {
        return "clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw);
    }
}
