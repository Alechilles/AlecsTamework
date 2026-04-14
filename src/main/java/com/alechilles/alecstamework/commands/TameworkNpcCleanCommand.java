package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to remove all live NPC entities matching one requested role id.
 */
public final class TameworkNpcCleanCommand extends AbstractPlayerCommand {
    public TameworkNpcCleanCommand() {
        super("npcclean", "Remove all NPCs matching a specific role id.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        String requestedRole = getArg(commandContext, 2);
        if (requestedRole == null || requestedRole.isBlank()) {
            commandContext.sender().sendMessage(Message.raw("Usage: /tw npcclean <roleId>"));
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        TameworkNpcRoleResolver.RoleResolution resolution = TameworkNpcRoleResolver.resolveRole(requestedRole.trim(), npcPlugin);
        if (resolution.errorMessage() != null) {
            commandContext.sender().sendMessage(Message.raw(resolution.errorMessage()));
            return;
        }

        String targetRoleId = resolution.roleId();
        if (targetRoleId == null || targetRoleId.isBlank()) {
            commandContext.sender().sendMessage(Message.raw("Unable to resolve role id: " + requestedRole));
            return;
        }

        AtomicInteger removedCount = new AtomicInteger(0);
        store.forEachEntityParallel(NPCEntity.getComponentType(), (index, archetypeChunk, commandBuffer) -> {
            NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            if (!TameworkNpcRoleResolver.matchesRole(targetRoleId, npc, npcPlugin)) {
                return;
            }
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            removedCount.incrementAndGet();
        });

        int removed = removedCount.get();
        if (removed == 0) {
            commandContext.sender().sendMessage(Message.raw("No NPCs matched role '" + targetRoleId + "'."));
            return;
        }
        commandContext.sender().sendMessage(Message.raw("Removed " + removed + " NPC(s) with role '" + targetRoleId + "'."));
    }

    @Nullable
    private static String getArg(@Nonnull CommandContext commandContext, int index) {
        String input = commandContext.getInputString();
        if (input == null || input.isBlank()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= index) {
            return null;
        }
        return tokens[index];
    }
}
