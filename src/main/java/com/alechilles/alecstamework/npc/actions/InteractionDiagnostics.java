package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.logging.Level;

// Reports debug diagnostics for interaction matching and execution.
final class InteractionDiagnostics {
    private final ActionTameworkInteract owner;

    InteractionDiagnostics(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Logs a warning-level message for unsupported interactions.
    void logUnsupported(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.WARNING).log(message);
        }
    }

    // Logs a debug message if logging is enabled.
    void logDebug(String message) {
        Tamework instance = Tamework.getInstance();
        if (instance != null && instance.getLogger() != null) {
            instance.getLogger().at(Level.FINE).log(message);
        }
    }

    // Describes the held item for diagnostics.
    String describeHeldItem(InteractionContextSnapshot ctx) {
        ItemStack stack = ctx != null ? ctx.activeItem : null;
        if (stack == null || stack.isEmpty()) {
            return "<empty>";
        }
        String itemId = stack.getItemId();
        return itemId != null ? itemId : "<unknown>";
    }

    // Builds a summary explaining why no interactions matched.
    String buildNoMatchSummary(TwInteractionConfig config,
                               Ref<EntityStore> npcRef,
                               Role role,
                               InfoProvider infoProvider,
                               Store<EntityStore> store,
                               Player player,
                               InteractionContextSnapshot ctx) {
        String roleName = role != null ? role.getRoleName() : "<null>";
        boolean tamed = owner.isTamed(npcRef, store);
        boolean isOwner = owner.isOwner(npcRef, store, player);
        boolean hasLoved = owner.isHeldItemInList(owner.resolveLovedItems(role, ctx), ctx);
        boolean isHarvestable = owner.resolveIsHarvestable(role, ctx);
        boolean harvestReady = owner.isAlarmReady(npcRef, store, ActionTameworkInteract.DEFAULT_HARVEST_ALARM);
        boolean harvestContext = owner.matchesHarvestContext(role, infoProvider, ctx);
        boolean isMountable = owner.resolveIsMountable(role, ctx);
        boolean crouching = owner.isPlayerCrouching(role, infoProvider, store);
        String configId = config != null ? config.getId() : "<null>";
        return String.format(
                "TameworkInteract: no interactions matched (role=%s config=%s). " +
                        "tamed=%s owner=%s held=%s lovedMatch=%s isHarvestable=%s harvestReady=%s harvestContext=%s " +
                        "isMountable=%s crouching=%s",
                roleName,
                configId,
                tamed,
                isOwner,
                describeHeldItem(ctx),
                hasLoved,
                isHarvestable,
                harvestReady,
                harvestContext,
                isMountable,
                crouching
        );
    }
}
