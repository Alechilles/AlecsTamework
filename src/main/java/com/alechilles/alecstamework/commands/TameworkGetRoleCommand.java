package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to display the role, tamed status and owner of the targeted NPC in one line. Support
 * uses it to spot a companion that is tamed but still runs its untamed role.
 */
public final class TameworkGetRoleCommand extends AbstractTameworkNpcTargetCommand {

    public TameworkGetRoleCommand() {
        super("role", "server.tamework.commands.getRole.description",
                "server.tamework.commands.getRole.no.npc.found.in.view");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull NPCEntity npc,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> npcRef) {
        String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);

        String ownerText = "-";
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = type == null ? null : store.getComponent(npcRef, type);
        UUID ownerUuid = owner == null ? null : owner.getOwnerId();
        if (ownerUuid != null) {
            String ownerName = owner.getOwnerName();
            ownerText = ownerName == null || ownerName.isBlank()
                    ? ownerUuid.toString()
                    : ownerName + " (" + ownerUuid + ")";
        }

        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getRole.role.for.npc.is")
                .param("0", String.valueOf(npc.getUuid()))
                .param("1", roleId == null || roleId.isBlank() ? "?" : roleId)
                .param("2", String.valueOf(tamed))
                .param("3", ownerText));
    }
}
