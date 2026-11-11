package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

/**
 * Set Home and Return Home for the companion card, without a command item.
 *
 * <p>The home lives on the companion's {@link TameworkCommandLinksComponent}, which is saved with its body, so the
 * card only needs the companion. Command items that already link the companion also cache its home for Return Home
 * while it is unloaded; Set Home refreshes those hotbar records so a later Return Home from the item uses the new
 * home. Callers run on the world thread from a page event (not an ECS system) after checking ownership and generic
 * authority.
 */
final class CompanionCardHomeActions {
    private final CommandMenuMoveService moves;
    private final CommandLinkMutationService links;
    private final CommandFeedbackService feedback;
    private final CommandNpcNameResolver names;

    CompanionCardHomeActions(CommandMenuMoveService moves, CommandLinkMutationService links,
                             CommandFeedbackService feedback, CommandNpcNameResolver names) {
        this.moves = moves;
        this.links = links;
        this.feedback = feedback;
        this.names = names;
    }

    void setHome(Player player, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (npc == null || npc.getUuid() == null || transform == null) {
            feedback.showWarningKey(player, "tamework.ui.notifications.command.setHome.positionUnavailable");
            return;
        }
        TameworkCommandLinksComponent current =
                store.getComponent(npcRef, TameworkCommandLinksComponent.getComponentType());
        // The links owner is the companion's owner, also when a player with shared access sets the home.
        com.alechilles.alecstamework.npc.components.TameworkOwnerComponent owner = store.getComponent(
                npcRef, com.alechilles.alecstamework.npc.components.TameworkOwnerComponent.getComponentType());
        java.util.UUID linksOwner = owner != null && owner.getOwnerId() != null
                ? owner.getOwnerId() : player.getUuid();
        TameworkCommandLinksComponent next = current == null
                ? new TameworkCommandLinksComponent(linksOwner, new String[0]) : current.clone();
        if (next.getOwnerId() == null) {
            next.setOwnerId(linksOwner);
        }
        Vector3d home = new Vector3d(transform.getPosition());
        next.setHomePosition(home);
        store.putComponent(npcRef, TameworkCommandLinksComponent.getComponentType(), next);
        refreshLinkedItems(player, npcRef, store, npc, home);
        feedback.showSuccessKey(player, "tamework.ui.notifications.command.setHome.success",
                names.resolveNpcDisplayName(npcRef, store, npc));
    }

    void returnHome(Player player, Ref<EntityStore> playerRef, Ref<EntityStore> npcRef, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc != null) {
            moves.returnHomeLoaded(player, playerRef, store, npcRef, npc, CompanionCardPageService.CARD_SOURCE_ID);
        }
    }

    /** Updates the cached home on hotbar command items that already link this companion; links nothing new. */
    private void refreshLinkedItems(Player player, Ref<EntityStore> npcRef, Store<EntityStore> store,
                                    NPCEntity npc, Vector3d home) {
        Inventory inventory = player.getInventory();
        ItemContainer hotbar = inventory == null ? null : inventory.getHotbar();
        World world = player.getWorld();
        if (hotbar == null || world == null) {
            return;
        }
        for (short slot = 0; slot < hotbar.getCapacity(); slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()
                    || stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING) == null
                    || links.findLinkedNpcRecord(links.readLinkedNpcRecords(stack), npc.getUuid()) == null) {
                continue;
            }
            hotbar.setItemStackForSlot(slot, links.upsertLinkedNpcRecord(stack, npc.getUuid(), home,
                    world.getName(), home, names.resolveNpcDisplayNameFromComponents(npcRef, store),
                    names.resolveNpcNameKey(npc), names.resolveNpcRoleId(npc)));
        }
    }
}
