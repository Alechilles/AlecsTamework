package com.alechilles.alecstamework.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Command to display alarm status for the targeted NPC.
 */
public final class TameworkGetAlarmCommand extends AbstractWorldCommand {
    private static final String DEFAULT_ALARM_NAME = "Harvest_Ready";

    public TameworkGetAlarmCommand() {
        super("getalarm", "Get alarm status of the NPC you are looking at.");
        setAllowsExtraArguments(true);
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
                           @Nonnull World world,
                           @Nonnull Store<EntityStore> store) {
        ParsedArgs args = parseArgs(commandContext);
        Ref<EntityStore> senderRef = commandContext.isPlayer() ? commandContext.senderAsPlayerRef() : null;
        Ref<EntityStore> npcRef = resolveTarget(store, senderRef, world, args);
        if (npcRef == null || !npcRef.isValid()) {
            commandContext.sender().sendMessage(Message.raw(commandContext.isPlayer()
                    ? "No NPC found in view."
                    : "No NPC resolved. Console usage requires an NPC UUID."));
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
        String remainingText = "";
        if (alarm.isSet()) {
            Instant alarmInstant = readAlarmInstant(alarm);
            if (alarmInstant != null) {
                Duration remaining = Duration.between(now, alarmInstant);
                if (!remaining.isNegative()) {
                    remainingText = " Remaining: " + formatDuration(remaining) + ".";
                }
            }
        }
        commandContext.sender().sendMessage(Message.raw(
                "Alarm " + alarmName + " for NPC " + npcUuid + " is " + status + "." + remainingText
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
        if (playerRef == null) {
            return null;
        }
        TameworkCommandTargeting.Candidate candidate = TameworkCommandTargeting.findTargetNpc(store, playerRef);
        return candidate != null ? candidate.ref : null;
    }

    private static Instant resolveGameTime(Store<EntityStore> store) {
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        return time != null ? time.getGameTime() : Instant.now();
    }

    private static Instant readAlarmInstant(Alarm alarm) {
        try {
            Field field = Alarm.class.getDeclaredField("alarmInstant");
            field.setAccessible(true);
            Object value = field.get(alarm);
            if (value instanceof Instant) {
                return (Instant) value;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0 || days > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || hours > 0 || days > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append("s");
        return sb.toString().trim();
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
