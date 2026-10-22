package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Points an NPC target slot (default MasterTarget) at the NPC's owner, as the Follow command's OwnerPlayer
 * target step does. Usage: {@code tw debug set target <npc uuid> [slot]}. Works from the console.
 */
public final class TameworkSetTargetToOwnerCommand extends AbstractWorldCommand {
    private static final String DEFAULT_SLOT = "MasterTarget";

    public TameworkSetTargetToOwnerCommand() {
        super("target", "server.tamework.commands.setTarget.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        String[] args = TameworkCommandInput.argumentsAfter(commandContext.getInputString(), "target");
        UUID uuid = args.length >= 1 && args.length <= 2 ? parseUuid(args[0]) : null;
        if (uuid == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTarget.usage"));
            return;
        }
        String slot = args.length == 2 ? args[1] : DEFAULT_SLOT;
        Ref<EntityStore> ref = world.getEntityRef(uuid);
        NPCEntity npc = ref != null && ref.isValid() ? store.getComponent(ref, NPCEntity.getComponentType()) : null;
        if (npc == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setState.npc.not.found")
                    .param("0", uuid.toString()));
            return;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> ownerType = TameworkOwnerComponent.getComponentType();
        TameworkOwnerComponent owner = ownerType != null ? store.getComponent(ref, ownerType) : null;
        UUID ownerId = owner != null ? owner.getOwnerId() : null;
        Ref<EntityStore> ownerRef = ownerId != null ? world.getEntityRef(ownerId) : null;
        if (ownerRef == null || !ownerRef.isValid()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTarget.owner.not.found")
                    .param("0", uuid.toString()));
            return;
        }
        MarkedEntitySupport marked = NpcSupportAccess.markedEntity(npc.getRole(), ref, store);
        if (marked == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTarget.no.slots")
                    .param("0", String.valueOf(npc.getRoleName())));
            return;
        }
        marked.setMarkedEntity(slot, ownerRef);
        if (marked.getMarkedEntityRef(slot) != ownerRef) {
            // The engine ignores slot names the role never declared.
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTarget.unknown.slot")
                    .param("0", slot)
                    .param("1", String.valueOf(npc.getRoleName())));
            return;
        }
        commandContext.sender().sendMessage(Message.translation("server.tamework.commands.setTarget.done")
                .param("0", uuid.toString())
                .param("1", slot));
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
