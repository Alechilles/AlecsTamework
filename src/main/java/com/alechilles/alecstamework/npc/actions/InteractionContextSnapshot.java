package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.FeedInteraction;
import com.alechilles.alecstamework.inventory.PlayerInventoryAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Snapshot of frequently accessed interaction context data to avoid repeated lookups.
 */
final class InteractionContextSnapshot {
    final Player player;
    final Inventory inventory;
    final CombinedItemContainer combinedInventory;
    final ItemStack activeItem;
    final String activeItemId;
    final UUID playerId;
    final StdScope[] roleScopes;
    final Ref<EntityStore> playerRef;

    @Nullable
    Boolean cachedNpcTamed;
    @Nullable
    UUID cachedNpcOwnerId;
    @Nullable
    Boolean cachedPlayerIsOwner;
    @Nullable
    MovementStates cachedPlayerMovementStates;
    final Map<String, Boolean> promptContextMatches;
    final Map<String, String[]> resolvedItemIdsByParamName;
    final Map<FeedInteraction, InteractionRequiredItems> feedRequirementItemsByInteraction;
    final Map<String, InteractionAlarmHelper.AlarmSnapshot> alarmSnapshotsByName;

    private InteractionContextSnapshot(Player player,
                                       Inventory inventory,
                                       CombinedItemContainer combinedInventory,
                                       ItemStack activeItem,
                                       String activeItemId,
                                       UUID playerId,
                                       StdScope[] roleScopes,
                                       @Nullable Ref<EntityStore> playerRef) {
        this.player = player;
        this.inventory = inventory;
        this.combinedInventory = combinedInventory;
        this.activeItem = activeItem;
        this.activeItemId = activeItemId;
        this.playerId = playerId;
        this.roleScopes = roleScopes;
        this.playerRef = playerRef;
        this.promptContextMatches = new HashMap<>();
        this.resolvedItemIdsByParamName = new HashMap<>();
        this.feedRequirementItemsByInteraction = new HashMap<>();
        this.alarmSnapshotsByName = new HashMap<>();
    }

    private InteractionContextSnapshot(Player player,
                                       Inventory inventory,
                                       CombinedItemContainer combinedInventory,
                                       ItemStack activeItem,
                                       String activeItemId,
                                       UUID playerId,
                                       StdScope[] roleScopes) {
        this(player, inventory, combinedInventory, activeItem, activeItemId, playerId, roleScopes, null);
    }

    static InteractionContextSnapshot from(@Nullable Player player,
                                           StdScope[] roleScopes,
                                           @Nullable Ref<EntityStore> playerRef) {
        if (player == null) {
            return new InteractionContextSnapshot(null, null, null, null, null, null, roleScopes, playerRef);
        }
        Inventory inventory = player.getInventory();
        CombinedItemContainer combined = inventory != null ? inventory.getCombinedBackpackStorageHotbar() : null;
        ItemStack active = PlayerInventoryAccess.getActiveHotbarItem(player);
        String activeId = (active != null && !active.isEmpty()) ? active.getItemId() : null;
        return new InteractionContextSnapshot(
                player,
                inventory,
                combined,
                active,
                activeId,
                player.getUuid(),
                roleScopes,
                playerRef
        );
    }

    static InteractionContextSnapshot from(@Nullable Player player, StdScope[] roleScopes) {
        return from(player, roleScopes, null);
    }
}
