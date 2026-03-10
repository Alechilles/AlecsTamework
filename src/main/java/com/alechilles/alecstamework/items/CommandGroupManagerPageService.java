package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.ui.TameworkCommandGroupManagerPage;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens and populates the command-group manager page for a specific command tool.
 */
final class CommandGroupManagerPageService {
    private final CommandPanelActionService panelActionService;
    private final CommandGroupService groupService;

    CommandGroupManagerPageService(CommandPanelActionService panelActionService,
                                   CommandGroupService groupService) {
        this.panelActionService = panelActionService;
        this.groupService = groupService != null ? groupService : new CommandGroupService();
    }

    void openGroupManagerPage(Player player, String toolId, Runnable backCallback) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return;
        }
        if (player.getPageManager() == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> playerRef = player.getReference();
        PlayerRef uiPlayerRef = player.getPlayerRef();
        if (store == null || playerRef == null || !playerRef.isValid() || uiPlayerRef == null || !uiPlayerRef.isValid()) {
            return;
        }
        TameworkCommandGroupManagerPage page = new TameworkCommandGroupManagerPage(
                uiPlayerRef,
                () -> resolveGroupEntriesForTool(player, toolId),
                (name, colorHex) -> panelActionService.applyCreateGroup(player, toolId, name, colorHex),
                (groupId, name) -> panelActionService.applyRenameGroup(player, toolId, groupId, name),
                (groupId, colorHex) -> panelActionService.applyRecolorGroup(player, toolId, groupId, colorHex),
                groupId -> panelActionService.applyDeleteGroup(player, toolId, groupId),
                backCallback != null ? backCallback : () -> { },
                () -> { }
        );
        player.getPageManager().openCustomPage(playerRef, store, page);
    }

    private List<TameworkCommandGroupManagerPage.GroupEntry> resolveGroupEntriesForTool(Player player, String toolId) {
        if (player == null || toolId == null || toolId.isBlank()) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        if (inventory == null || inventory.getHotbar() == null) {
            return List.of();
        }
        ItemContainer hotbar = inventory.getHotbar();
        short capacity = hotbar.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = hotbar.getItemStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String stackToolId = stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
            if (stackToolId == null || !stackToolId.equals(toolId)) {
                continue;
            }
            List<CommandGroupService.GroupRecord> groups = groupService.readGroups(stack);
            if (groups.isEmpty()) {
                return List.of();
            }
            ArrayList<TameworkCommandGroupManagerPage.GroupEntry> out = new ArrayList<>(groups.size());
            for (CommandGroupService.GroupRecord group : groups) {
                if (group == null || group.groupId == null || group.groupId.isBlank()) {
                    continue;
                }
                out.add(new TameworkCommandGroupManagerPage.GroupEntry(
                        group.groupId,
                        group.name,
                        group.colorHex
                ));
            }
            return out;
        }
        return List.of();
    }
}
