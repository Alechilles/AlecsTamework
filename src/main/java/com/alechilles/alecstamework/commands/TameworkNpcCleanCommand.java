package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import com.alechilles.alecstamework.ownership.OwnerPopulationIndex;
import com.alechilles.alecstamework.ownership.OwnerPopulationRuntime;
import com.alechilles.alecstamework.integration.claims.ClaimOccupancyIndex;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to remove all unowned live NPC entities matching one requested role id.
 */
public final class TameworkNpcCleanCommand extends AbstractWorldCommand {
    public TameworkNpcCleanCommand() {
        super("npcclean", "Remove all unowned NPCs matching a specific role id.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
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

        NpcCleanOwnershipGuard ownershipGuard = resolveOwnershipGuard();
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        ComponentType<EntityStore, TameworkCommandLinksComponent> linksType =
                TameworkCommandLinksComponent.getComponentType();
        ComponentType<EntityStore, TameworkNpcNameComponent> nameType =
                TameworkNpcNameComponent.getComponentType();
        ComponentType<EntityStore, UUIDComponent> uuidType = UUIDComponent.getComponentType();
        if (ownershipGuard == null || ownerType == null || linksType == null
                || nameType == null || uuidType == null) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC cleanup is unavailable while companion ownership data is unavailable."
            ));
            return;
        }
        if (!ownershipGuard.readyForDestructiveCleanup()) {
            commandContext.sender().sendMessage(Message.raw(
                    "NPC cleanup is unavailable until companion population reconciliation is ready."
            ));
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
            TameworkNpcNameComponent liveName = archetypeChunk.getComponent(index, nameType);
            UUIDComponent identity = archetypeChunk.getComponent(index, uuidType);
            UUID npcUuid = identity == null ? null : identity.getUuid();
            if (ownershipGuard.isProtectedOwnedCompanion(npcUuid, liveOwner, liveLinks, liveName)) {
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
                commandContext.sender().sendMessage(Message.raw(
                        "No unowned NPCs were removed for role '" + targetRoleId
                                + "'. Skipped " + protectedNpcCount + " owned companion(s)."
                ));
                return;
            }
            commandContext.sender().sendMessage(Message.raw(
                    "No NPCs matched role '" + targetRoleId + "'."
            ));
            return;
        }
        int protectedNpcCount = protectedCount.get();
        String skipped = protectedNpcCount == 0
                ? ""
                : " Skipped " + protectedNpcCount + " owned companion(s).";
        commandContext.sender().sendMessage(Message.raw(
                "Removed " + removed + " unowned NPC(s) with role '" + targetRoleId + "'." + skipped
        ));
    }

    @Nullable
    private static NpcCleanOwnershipGuard resolveOwnershipGuard() {
        Tamework plugin = Tamework.getInstance();
        CompanionIdentityResolver identityResolver = plugin == null
                ? null
                : plugin.getCompanionIdentityResolver();
        OwnerPopulationIndex populationIndex = plugin == null
                ? null
                : plugin.getOwnerPopulationIndex();
        OwnerPopulationRuntime runtime = plugin == null ? null : plugin.getOwnerPopulationRuntime();
        ClaimOccupancyIndex claimIndex = runtime == null ? null : runtime.claimOccupancyIndex();
        return identityResolver == null || populationIndex == null || claimIndex == null
                ? null
                : new NpcCleanOwnershipGuard(identityResolver, populationIndex, claimIndex);
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
