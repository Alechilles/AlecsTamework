package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

// Applies resolved interactions and handles cooldown updates.
final class InteractionExecution {
    private final InteractionExecutor executor;
    private final InteractionCooldowns cooldowns;

    InteractionExecution(InteractionExecutor executor, InteractionCooldowns cooldowns) {
        this.executor = executor;
        this.cooldowns = cooldowns;
    }

    // Applies the interaction and updates cooldowns on success.
    boolean applyInteraction(ActionTameworkInteract.ResolvedInteraction interaction,
                             Ref<EntityStore> npcRef,
                             Role role,
                             InfoProvider infoProvider,
                             Store<EntityStore> store,
                             Player player,
                             InteractionContextSnapshot ctx) {
        if (interaction == null) {
            return false;
        }
        boolean applied = executor.applyInteraction(interaction.entry, npcRef, role, infoProvider, store, player, ctx);
        if (applied) {
            cooldowns.applyInteractionCooldown(interaction, npcRef, store);
        }
        return applied;
    }
}
