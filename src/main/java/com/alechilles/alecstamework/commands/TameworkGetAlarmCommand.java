package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to display alarm status for the targeted NPC.
 */
public final class TameworkGetAlarmCommand extends AbstractPlayerCommand {
    private static final String DEFAULT_ALARM_NAME = "Harvest_Ready";

    public TameworkGetAlarmCommand() {
        super("getalarm", "Get alarm status of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        ParsedArgs args = parseArgs(commandContext);
        Ref<EntityStore> npcRef = resolveTarget(store, ref, world, args);
        if (npcRef == null || !npcRef.isValid()) {
            commandContext.sender().sendMessage(Message.raw("No NPC found in view."));
            return;
        }

        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            commandContext.sender().sendMessage(Message.raw("Target is not an NPC."));
            return;
        }

        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            commandContext.sender().sendMessage(Message.raw("NPC has no alarm store."));
            return;
        }

        String alarmName = args.alarmName != null && !args.alarmName.isBlank()
                ? args.alarmName
                : DEFAULT_ALARM_NAME;
        Alarm alarm = alarmStore.get(npc, alarmName);
        Instant now = resolveGameTime(store);
        String status;
        if (!alarm.isSet()) {
            status = "unset";
        } else if (alarm.hasPassed(now)) {
            status = "passed";
        } else {
            status = "active";
        }

        UUID npcUuid = npc.getUuid();
        commandContext.sender().sendMessage(Message.raw(
                "Alarm " + alarmName + " for NPC " + npcUuid + " is " + status + "."
        ));
    }

    private static Ref<EntityStore> resolveTarget(Store<EntityStore> store,
                                                  Ref<EntityStore> playerRef,
                                                  World world,
                                                  ParsedArgs args) {
        if (args.npcUuid != null) {
            Ref<EntityStore> target = world.getEntityRef(args.npcUuid);
            if (target != null && target.isValid()) {
                return target;
            }
            return null;
        }
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, playerRef);
        return candidate != null ? candidate.ref : null;
    }

    private static Instant resolveGameTime(Store<EntityStore> store) {
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        return time != null ? time.getGameTime() : Instant.now();
    }

    private static ParsedArgs parseArgs(CommandContext commandContext) {
        String arg1 = getArg(commandContext, 2);
        String arg2 = getArg(commandContext, 3);

        UUID uuid1 = parseUuid(arg1);
        UUID uuid2 = parseUuid(arg2);

        if (uuid1 != null) {
            return new ParsedArgs(uuid1, arg2);
        }
        if (uuid2 != null) {
            return new ParsedArgs(uuid2, arg1);
        }
        return new ParsedArgs(null, arg1);
    }

    private static String getArg(CommandContext commandContext, int index) {
        String input = commandContext.getInputString();
        if (input == null) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length <= index) {
            return null;
        }
        return tokens[index];
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static final class ParsedArgs {
        private final UUID npcUuid;
        private final String alarmName;

        private ParsedArgs(UUID npcUuid, String alarmName) {
            this.npcUuid = npcUuid;
            this.alarmName = alarmName;
        }
    }
}
