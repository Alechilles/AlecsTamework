package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;

/**
 * Prints the wander state of every NPC whose role id contains a filter: state, position, leash point,
 * horizontal distance to the leash point, and whether it stands in a fluid. Works from the console.
 */
public final class TameworkGetWanderCommand extends AbstractWorldCommand {
    private static final int MAX_LINES = 40;

    public TameworkGetWanderCommand() {
        super("wander", "server.tamework.commands.getWander.description");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        String filter = TameworkCommandInput.firstArgument(commandContext.getInputString(), "wander");
        if (filter == null || filter.isBlank()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getWander.usage"));
            return;
        }
        String needle = filter.toLowerCase(Locale.ROOT);

        List<Ref<EntityStore>> matches = new ArrayList<>();
        store.forEachChunk(Query.any(), (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                String roleName = npc != null ? npc.getRoleName() : null;
                if (roleName != null && roleName.toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(chunk.getReferenceTo(i));
                }
            }
        });

        if (matches.isEmpty()) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getWander.none")
                    .param("0", filter));
            return;
        }
        int shown = 0;
        for (Ref<EntityStore> ref : matches) {
            if (shown++ >= MAX_LINES) {
                break;
            }
            String line = describe(ref, store);
            if (line != null) {
                commandContext.sender().sendMessage(Message.raw(line));
            }
        }
    }

    private static String describe(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return null;
        }
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (npc == null || transform == null) {
            return null;
        }
        Role role = npc.getRole();
        StateSupport stateSupport = role != null ? NpcSupportAccess.state(role, ref, store) : null;
        String state = stateSupport != null ? stateSupport.getStateName() : null;
        MotionController motion = role != null ? role.getActiveMotionController() : null;
        var pos = transform.getPosition();
        var leash = npc.getLeashPoint();
        double dx = pos.x() - leash.x();
        double dz = pos.z() - leash.z();
        return String.format(Locale.ROOT,
                "%s role=%s state=%s pos=%.1f,%.1f,%.1f leash=%.1f,%.1f,%.1f distToLeash=%.1f inWater=%s",
                npc.getUuid(),
                npc.getRoleName(),
                state != null && !state.isBlank() ? state : "<unknown>",
                pos.x(), pos.y(), pos.z(),
                leash.x(), leash.y(), leash.z(),
                Math.sqrt(dx * dx + dz * dz),
                motion != null ? String.valueOf(motion.inWater()) : "<unknown>");
    }
}
