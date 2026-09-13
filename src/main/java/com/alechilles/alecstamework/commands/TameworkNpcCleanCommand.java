package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to remove all unowned live NPC entities matching one requested role id.
 */
public final class TameworkNpcCleanCommand extends AbstractWorldCommand {
    public TameworkNpcCleanCommand() {
        super("clean", "server.tamework.commands.npcClean.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        String requestedRole = getArg(commandContext, 2);
        if (requestedRole == null || requestedRole.isBlank()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcClean.usage.tw.npcclean.roleid"));
            return;
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        TameworkNpcRoleResolver.RoleResolution resolution = TameworkNpcRoleResolver.resolveRole(requestedRole.trim(), npcPlugin);
        if (resolution.errorMessage() != null) {
            commandContext.sender().sendMessage(roleResolutionMessage(resolution));
            return;
        }

        String targetRoleId = resolution.roleId();
        if (targetRoleId == null || targetRoleId.isBlank()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcClean.unable.to.resolve.role.id").param("0", String.valueOf(requestedRole)));
            return;
        }

        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        if (ownerType == null || linksType == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcClean.npc.cleanup.is.unavailable.while.companion.ownership"));
            return;
        }

        AtomicInteger removedCount = new AtomicInteger(0);
        AtomicInteger protectedCount = new AtomicInteger(0);
        store.forEachEntityParallel(NPCEntity.getComponentType(), (index, archetypeChunk, commandBuffer) -> {
            NPCEntity npc = archetypeChunk.getComponent(index, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            if (!TameworkNpcRoleResolver.matchesRole(targetRoleId, npc, npcPlugin)) {
                return;
            }
            TameworkOwnerComponent liveOwner = archetypeChunk.getComponent(index, ownerType);
            TameworkCommandLinksComponent liveLinks = archetypeChunk.getComponent(index, linksType);
            if (isProtectedOwnedCompanion(liveOwner, liveLinks)) {
                protectedCount.incrementAndGet();
                return;
            }
            commandBuffer.removeEntity(archetypeChunk.getReferenceTo(index), RemoveReason.REMOVE);
            removedCount.incrementAndGet();
        });

        int removed = removedCount.get();
        if (removed == 0) {
            int protectedNpcCount = protectedCount.get();
            if (protectedNpcCount > 0) {
                commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcClean.no.unowned.npcs.were.removed.for.role").param("0", String.valueOf(targetRoleId)).param("1", String.valueOf(protectedNpcCount)));
                return;
            }
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.npcClean.no.npcs.matched.role").param("0", String.valueOf(targetRoleId)));
            return;
        }
        int protectedNpcCount = protectedCount.get();
        commandContext.sender().sendMessage(protectedNpcCount == 0
                ? Message.translation("server.tamework.commands.npcClean.removed")
                .param("removed", removed).param("role", targetRoleId)
                : Message.translation("server.tamework.commands.npcClean.removedWithProtected")
                .param("removed", removed).param("role", targetRoleId)
                .param("protected", protectedNpcCount));
    }

    @Nonnull
    private static Message roleResolutionMessage(
            @Nonnull TameworkNpcRoleResolver.RoleResolution resolution
    ) {
        String key = resolution.errorKey();
        if (key == null || key.isBlank()) {
            String fallback = resolution.errorMessage();
            return Message.raw(fallback == null ? "" : fallback);
        }
        Message message = Message.translation("server." + key);
        List<String> arguments = resolution.errorArguments();
        for (int index = 0; index < arguments.size(); index++) {
            message.param(Integer.toString(index), arguments.get(index));
        }
        return message;
    }

    private static boolean isProtectedOwnedCompanion(
            @Nullable TameworkOwnerComponent owner,
            @Nullable TameworkCommandLinksComponent links
    ) {
        if (owner != null && owner.getOwnerId() != null) {
            return true;
        }
        return links != null
                && (links.getOwnerId() != null
                || (links.getToolIds() != null && links.getToolIds().length > 0));
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
