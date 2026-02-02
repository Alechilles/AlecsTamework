package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class TameworkSetOwnerCommand extends AbstractPlayerCommand {
    public TameworkSetOwnerCommand() {
        super("setowner", "Set owner of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            commandContext.sender().sendMessage(Message.raw("No player context."));
            return;
        }

        String raw = getFirstArg(commandContext);
        UUID newOwner = parseOwner(raw, player.getUuid());
        if (newOwner == null && raw != null && !raw.isBlank() && !isClear(raw)) {
            commandContext.sender().sendMessage(Message.raw("Invalid UUID. Use 'clear' or a valid UUID."));
            return;
        }

        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        String ownerName = null;
        if (newOwner != null && newOwner.equals(player.getUuid())) {
            ownerName = player.getDisplayName();
        }

        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type != null) {
            store.putComponent(candidate.ref, type, new TameworkOwnerComponent(newOwner, ownerName));
        }

        String ownerText = newOwner == null ? "null" : newOwner.toString();
        commandContext.sender().sendMessage(Message.raw("Set owner for NPC " + candidate.npcUuid + " to " + ownerText));
    }

    private static UUID parseOwner(String raw, UUID defaultOwner) {
        if (raw == null || raw.isBlank()) {
            return defaultOwner;
        }
        if (isClear(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String getFirstArg(CommandContext commandContext) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\s+");
        if (tokens.length < 3) {
            return null;
        }
        return tokens[2];
    }

    private static boolean isClear(String raw) {
        return "clear".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw);
    }
}
