package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to display owner info for the targeted NPC.
 */
public final class TameworkGetOwnerCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkGetOwnerCommand() {
        super("owner", "server.tamework.commands.getOwner.description",
                "server.tamework.commands.getOwner.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        UUID ownerUuid = null;
        String ownerName = null;
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type != null) {
            TameworkOwnerComponent component = store.getComponent(npcRef, type);
            if (component != null) {
                ownerUuid = component.getOwnerId();
                ownerName = component.getOwnerName();
            }
        }

        // Fall back to the live player entity if the stored name is missing.
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
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getOwner.owner.for.npc.is").param("0", String.valueOf(npc.getUuid())).param("1", String.valueOf(ownerText)));
    }
}
