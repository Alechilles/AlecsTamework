package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig.HookEffect;
import com.alechilles.alecstamework.npc.components.TameworkHookComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

// Applies interaction hook effects by writing the hook component to the NPC.
final class InteractionHookEffects {
    private final ActionTameworkInteract owner;

    InteractionHookEffects(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Writes the hook effect data to the NPC hook component.
    boolean applyTriggerNpcHook(HookEffect hookEffect,
                                Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                Player player) {
        if (hookEffect == null) {
            return false;
        }
        String hookId = hookEffect.getHookId();
        if (hookId == null || hookId.isBlank()) {
            return false;
        }
        if (hookEffect.isPlayerOnly() && player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkHookComponent> type = TameworkHookComponent.getComponentType();
        if (type == null) {
            return false;
        }
        UUID playerId = null;
        String playerName = null;
        String heldItemId = null;
        if (player != null) {
            playerId = player.getUuid();
            PlayerRef ref = player.getPlayerRef();
            if (ref != null) {
                playerName = ref.getUsername();
            }
            ItemStack stack = owner.getActiveItem(player);
            if (stack != null) {
                heldItemId = stack.getItemId();
            }
        }
        long timestampMs = System.currentTimeMillis();
        TameworkHookComponent component = new TameworkHookComponent(
                hookId,
                playerId,
                playerName,
                heldItemId,
                timestampMs,
                hookEffect.isConsume()
        );
        store.putComponent(npcRef, type, component);
        return true;
    }
}
