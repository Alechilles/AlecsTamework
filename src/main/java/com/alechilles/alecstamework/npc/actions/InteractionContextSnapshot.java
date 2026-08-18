package com.alechilles.alecstamework.npc.actions;

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
    @Nullable
    private Inventory inventory;
    private boolean inventoryResolved;
    @Nullable
    private CombinedItemContainer combinedInventory;
    private boolean combinedInventoryResolved;
    @Nullable
    private Map<String, Boolean> promptContextMatches;
    @Nullable
    private Map<String, String[]> resolvedItemIdsByParamName;
    @Nullable
    private Map<String, InteractionAlarmHelper.AlarmSnapshot> alarmSnapshotsByName;

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
        this.inventoryResolved = inventory != null;
        this.combinedInventory = combinedInventory;
        this.combinedInventoryResolved = combinedInventory != null;
        this.activeItem = activeItem;
        this.activeItemId = activeItemId;
        this.playerId = playerId;
        this.roleScopes = roleScopes;
        this.playerRef = playerRef;
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
        ItemStack activeItem = PlayerInventoryAccess.getActiveHotbarItem(player);
        UUID playerId = player != null ? player.getUuid() : null;
        return from(player, roleScopes, playerRef, activeItem, playerId);
    }

    static InteractionContextSnapshot from(@Nullable Player player,
                                           StdScope[] roleScopes,
                                           @Nullable Ref<EntityStore> playerRef,
                                           @Nullable ItemStack activeItem,
                                           @Nullable UUID playerId) {
        if (player == null) {
            return new InteractionContextSnapshot(null, null, null, null, null, null, roleScopes, playerRef);
        }
        String activeId = activeItem != null && !activeItem.isEmpty() ? activeItem.getItemId() : null;
        return new InteractionContextSnapshot(
                player,
                null,
                null,
                activeItem,
                activeId,
                playerId,
                roleScopes,
                playerRef
        );
    }

    static InteractionContextSnapshot from(@Nullable Player player, StdScope[] roleScopes) {
        return from(player, roleScopes, null);
    }

    @Nullable
    CombinedItemContainer resolveCombinedInventory() {
        if (!combinedInventoryResolved) {
            Inventory resolvedInventory = resolveInventory();
            combinedInventory = resolvedInventory != null
                    ? resolvedInventory.getCombinedBackpackStorageHotbar()
                    : null;
            combinedInventoryResolved = true;
        }
        return combinedInventory;
    }

    @Nullable
    Inventory resolveInventory() {
        if (!inventoryResolved) {
            inventory = player != null ? player.getInventory() : null;
            inventoryResolved = true;
        }
        return inventory;
    }

    @Nullable
    Boolean getPromptContextMatch(String context) {
        return promptContextMatches != null ? promptContextMatches.get(context) : null;
    }

    void cachePromptContextMatch(String context, boolean matched) {
        if (promptContextMatches == null) {
            promptContextMatches = new HashMap<>();
        }
        promptContextMatches.put(context, matched);
    }

    @Nullable
    String[] getResolvedItemIds(String paramName) {
        return resolvedItemIdsByParamName != null ? resolvedItemIdsByParamName.get(paramName) : null;
    }

    void cacheResolvedItemIds(String paramName, String[] itemIds) {
        if (resolvedItemIdsByParamName == null) {
            resolvedItemIdsByParamName = new HashMap<>();
        }
        resolvedItemIdsByParamName.put(paramName, itemIds);
    }

    @Nullable
    InteractionAlarmHelper.AlarmSnapshot getAlarmSnapshot(String alarmName) {
        return alarmSnapshotsByName != null ? alarmSnapshotsByName.get(alarmName) : null;
    }

    void cacheAlarmSnapshot(String alarmName, InteractionAlarmHelper.AlarmSnapshot snapshot) {
        if (alarmSnapshotsByName == null) {
            alarmSnapshotsByName = new HashMap<>();
        }
        alarmSnapshotsByName.put(alarmName, snapshot);
    }
}
