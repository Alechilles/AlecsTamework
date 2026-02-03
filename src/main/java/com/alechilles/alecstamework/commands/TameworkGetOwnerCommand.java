package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

public final class TameworkGetOwnerCommand extends AbstractPlayerCommand {
    public TameworkGetOwnerCommand() {
        super("getowner", "Get owner of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, ref);
        if (candidate == null || candidate.ref == null || !candidate.ref.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        UUID ownerUuid = null;
        String ownerName = null;
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type != null) {
            TameworkOwnerComponent component = store.getComponent(candidate.ref, type);
            if (component != null) {
                ownerUuid = component.getOwnerId();
                ownerName = component.getOwnerName();
            }
        }

        if ((ownerName == null || ownerName.isBlank()) && ownerUuid != null) {
            Ref<EntityStore> ownerRef = world.getEntityRef(ownerUuid);
            if (ownerRef != null && ownerRef.isValid()) {
                ownerName = OwnerNameUtil.resolve(store.getComponent(ownerRef, Player.getComponentType()));
            }
        }

        String ownerText;
        if (ownerUuid == null) {
            ownerText = "null";
        } else if (ownerName != null && !ownerName.isBlank()) {
            ownerText = ownerName + " (" + ownerUuid + ")";
        } else {
            ownerText = ownerUuid.toString();
        }
        commandContext.sender().sendMessage(Message.raw("Owner for NPC " + candidate.npcUuid + " is " + ownerText));
    }
}
