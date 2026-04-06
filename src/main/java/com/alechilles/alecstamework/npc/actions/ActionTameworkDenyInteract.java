package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Blocks owner-restricted interactions and emits a message.
 */
public final class ActionTameworkDenyInteract extends TameworkActionBase {

    public ActionTameworkDenyInteract(BuilderActionTameworkDenyInteract builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        OwnershipPolicy policy = resolveOwnershipPolicy(player);
        if (!resolveRequiresOwner(policy)) {
            return false;
        }
        // Block interactions when an owner exists and the player is not the owner.
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        return ownerUuid != null && playerUuid != null && !ownerUuid.equals(playerUuid);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        // Send a denial message and consume the interaction.
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player != null) {
            OwnershipPolicy policy = resolveOwnershipPolicy(player);
            UUID ownerUuid = resolveOwnerUuid(npcRef, store);
            String ownerName = resolveOwnerName(npcRef, store);
            String npcName = resolveNpcName(resolveNpcEntity(npcRef, store));
            OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, policy.verb);
        }
        return true;
    }

    private boolean resolveRequiresOwner(OwnershipPolicy policy) {
        TwGlobalConfig global = TwGlobalConfig.resolveActive();
        TwGlobalConfig resolved = global != null ? global : TwGlobalConfig.defaultConfig();
        if (policy == null) {
            return resolved.isOwnershipInteractionRequiresOwner();
        }
        return switch (policy) {
            case CAPTURE -> resolved.isOwnershipCaptureRequiresOwner();
            case LINKING -> resolved.isOwnershipLinkingRequiresOwner();
            case INTERACTION -> resolved.isOwnershipInteractionRequiresOwner();
        };
    }

    private OwnershipPolicy resolveOwnershipPolicy(Player player) {
        ItemStack activeItem = getActiveItem(player);
        ItemFeatureConfig spawnerConfig = resolveSpawnerConfig(activeItem);
        if (isEmptySpawnerItem(spawnerConfig)) {
            return OwnershipPolicy.CAPTURE;
        }
        TwCommandItemConfig commandItemConfig = resolveCommandItemConfig(activeItem);
        if (commandItemConfig != null && commandItemConfig.isEnabled()) {
            return OwnershipPolicy.LINKING;
        }
        return OwnershipPolicy.INTERACTION;
    }

    private TwCommandItemConfig resolveCommandItemConfig(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return null;
        }
        Tamework instance = Tamework.getInstance();
        CommandItemRegistry commandRegistry = instance != null ? instance.getCommandItemRegistry() : null;
        if (commandRegistry == null) {
            return null;
        }
        return commandRegistry.get(itemStack.getItemId());
    }

    private enum OwnershipPolicy {
        CAPTURE("capture"),
        LINKING("link"),
        INTERACTION("interact with");

        private final String verb;

        OwnershipPolicy(String verb) {
            this.verb = verb;
        }
    }
}
