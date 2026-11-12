package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.items.CommandLinkedPanelCooldownSnapshotService;
import com.alechilles.alecstamework.npc.actions.HarvestAlarmTimeBasis;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.compat.NpcAlarmAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractWorldCommand;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Prints one machine-readable line with the state of a named alarm of the targeted NPC, without
 * changing it. Tamework's own alarm (harvest) is read first, judged on the animal's progression time
 * as gameplay judges it; the engine's alarm store is the fallback for alarms Tamework does not keep.
 * The default name is the configured harvest alarm.
 */
public final class TameworkGetAlarmCommand extends AbstractWorldCommand {
    public TameworkGetAlarmCommand() {
        super("alarm", "server.tamework.commands.getAlarm.description");
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
            commandContext.sender().sendMessage(commandContext.isPlayer() ? Message.translation("server.tamework.commands.getAlarm.no.npc.found.in.view") : Message.translation("server.tamework.commands.getAlarm.no.npc.resolved.console.usage.requires.an"));
            return;
        }

        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getAlarm.target.is.not.an.npc"));
            return;
        }

        String alarmName = args.alarmName != null && !args.alarmName.isBlank()
                ? args.alarmName
                : CommandLinkedPanelCooldownSnapshotService.resolveHarvestAlarmName();
        String prefix = "ok entity=" + npc.getUuid() + " alarm=" + alarmName;
        TameworkAlarmService.Snapshot tamework = TameworkAlarmService.snapshot(npcRef, store, alarmName);
        Alarm alarm = NpcAlarmAccess.resolveAlarm(npcRef, store, alarmName);
        // Harvest readiness is Tamework's alarm alone; other names use Tamework's when it has one.
        if (tamework.valid
                && (tamework.exists || alarm == null || HarvestAlarmTimeBasis.isHarvestAlarmName(alarmName))) {
            long remainingMs = tamework.active ? Math.max(0L, tamework.untilMs - tamework.nowMs) : 0L;
            commandContext.sender().sendMessage(Message.raw(prefix + " source=tamework"
                    + " ready=" + tamework.ready
                    + " exists=" + tamework.exists
                    + " untilMs=" + tamework.untilMs
                    + " nowMs=" + tamework.nowMs
                    + " remainingMs=" + remainingMs));
            return;
        }
        if (alarm == null) {
            commandContext.sender().sendMessage(Message.translation("server.tamework.commands.getAlarm.npc.has.no.alarm.store"));
            return;
        }
        Instant now = resolveGameTime(store);
        Instant until = alarm.isSet() ? readAlarmInstant(alarm) : null;
        long nowMs = now.toEpochMilli();
        long untilMs = until != null ? until.toEpochMilli() : 0L;
        commandContext.sender().sendMessage(Message.raw(prefix + " source=engine"
                + " ready=" + (!alarm.isSet() || alarm.hasPassed(now))
                + " exists=" + alarm.isSet()
                + " untilMs=" + untilMs
                + " nowMs=" + nowMs
                + " remainingMs=" + (until != null ? Math.max(0L, untilMs - nowMs) : 0L)));
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

    private static ParsedArgs parseArgs(CommandContext commandContext) {
        String arg1 = getArg(commandContext.getInputString(), 0);
        String arg2 = getArg(commandContext.getInputString(), 1);

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

    static String getArg(String input, int index) {
        String[] arguments = TameworkCommandInput.argumentsAfter(input, "alarm");
        return index < 0 || index >= arguments.length ? null : arguments[index];
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
