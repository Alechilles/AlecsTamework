package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Command to inspect flock-direct-follow runtime state for the targeted NPC.
 */
public final class TameworkGetFlockDebugCommand extends AbstractPlayerCommand {
    private static final String DIRECT_FOLLOW_FLAG = "Tamework_Baby_DirectFollow";
    private static final String DIRECT_FOLLOW_ALARM = "Tamework_Baby_DirectFollow_Window";

    public TameworkGetFlockDebugCommand() {
        super("getflockdebug", "Get flock direct-follow debug state for the NPC you are looking at.");
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

        NPCEntity npc = store.getComponent(candidate.ref, NPCEntity.getComponentType());
        if (npc == null) {
            commandContext.sender().sendMessage(Message.raw("Target is not an NPC."));
            return;
        }

        String roleId = CompanionRoleIdResolver.resolveRoleId(candidate.ref, store);
        String stage = CompanionLifeStageService.resolveCurrentStage(candidate.ref, store, roleId);

        AlarmSnapshot alarm = resolveAlarmSnapshot(npc, store, DIRECT_FOLLOW_ALARM);
        Boolean flag = readDirectFollowFlag(npc);
        FlockSnapshot flock = resolveFlockSnapshot(candidate.ref, store);

        StringBuilder message = new StringBuilder();
        message.append("Flock debug for NPC ")
                .append(npc.getUuid())
                .append(": role=")
                .append(roleId != null ? roleId : "<unknown>")
                .append(", stage=")
                .append(stage)
                .append(", ")
                .append(DIRECT_FOLLOW_FLAG)
                .append("=")
                .append(flag != null ? flag : "<unavailable>")
                .append(", ")
                .append(DIRECT_FOLLOW_ALARM)
                .append("=")
                .append(alarm.status);
        if (alarm.remainingText != null && !alarm.remainingText.isBlank()) {
            message.append(" (").append(alarm.remainingText).append(")");
        }
        message.append(", flockMembership=")
                .append(flock.membershipType)
                .append(", flockId=")
                .append(flock.flockId != null ? flock.flockId : "<none>");
        commandContext.sender().sendMessage(Message.raw(message.toString()));
    }

    @Nonnull
    private static AlarmSnapshot resolveAlarmSnapshot(@Nonnull NPCEntity npc,
                                                      @Nonnull Store<EntityStore> store,
                                                      @Nonnull String alarmName) {
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return new AlarmSnapshot("unavailable", null);
        }
        Alarm alarm = alarmStore.get(npc, alarmName);
        if (alarm == null || !alarm.isSet()) {
            return new AlarmSnapshot("unset", null);
        }
        Instant now = resolveGameTime(store);
        if (alarm.hasPassed(now)) {
            return new AlarmSnapshot("passed", null);
        }
        Instant alarmInstant = readAlarmInstant(alarm);
        if (alarmInstant == null) {
            return new AlarmSnapshot("active", null);
        }
        Duration remaining = Duration.between(now, alarmInstant);
        if (remaining.isNegative()) {
            return new AlarmSnapshot("active", "0s");
        }
        return new AlarmSnapshot("active", formatDuration(remaining));
    }

    @Nullable
    private static Boolean readDirectFollowFlag(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role == null || role.getMarkedEntitySupport() == null) {
            return null;
        }
        Object entitySupport = invokeObjectGetter(role.getMarkedEntitySupport(), "getEntitySupport");
        if (entitySupport == null) {
            return null;
        }
        Object sensorScope = invokeObjectGetter(entitySupport, "getSensorScope");
        if (sensorScope == null) {
            return null;
        }
        Boolean direct = invokeScopeBoolean(sensorScope, DIRECT_FOLLOW_FLAG);
        if (direct != null) {
            return direct;
        }
        return null;
    }

    @Nonnull
    private static FlockSnapshot resolveFlockSnapshot(@Nonnull Ref<EntityStore> npcRef,
                                                      @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, FlockMembership> type = FlockMembership.getComponentType();
        if (type == null) {
            return new FlockSnapshot("unavailable", null);
        }
        FlockMembership membership = store.getComponent(npcRef, type);
        if (membership == null) {
            return new FlockSnapshot("none", null);
        }
        String membershipType = membership.getMembershipType() != null
                ? membership.getMembershipType().toString()
                : "unknown";
        return new FlockSnapshot(membershipType, membership.getFlockId());
    }

    @Nonnull
    private static Instant resolveGameTime(@Nonnull Store<EntityStore> store) {
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        return time != null ? time.getGameTime() : Instant.now();
    }

    @Nullable
    private static Instant readAlarmInstant(@Nonnull Alarm alarm) {
        try {
            Field field = Alarm.class.getDeclaredField("alarmInstant");
            field.setAccessible(true);
            Object value = field.get(alarm);
            if (value instanceof Instant instant) {
                return instant;
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static Boolean invokeScopeBoolean(@Nonnull Object scope, @Nonnull String name) {
        try {
            Method getBoolean = scope.getClass().getMethod("getBoolean", String.class);
            Object value = getBoolean.invoke(scope, name);
            if (value instanceof Boolean bool) {
                return bool;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fallback below.
        } catch (RuntimeException ignored) {
            // Missing symbol/type mismatch in scope.
            return null;
        }
        try {
            Method getBooleanSupplier = scope.getClass().getMethod("getBooleanSupplier", String.class);
            Object supplierObj = getBooleanSupplier.invoke(scope, name);
            if (supplierObj instanceof BooleanSupplier supplier) {
                return supplier.getAsBoolean();
            }
        } catch (ReflectiveOperationException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    @Nullable
    private static Object invokeObjectGetter(@Nullable Object target, @Nonnull String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String formatDuration(@Nonnull Duration duration) {
        long seconds = Math.max(0L, duration.getSeconds());
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (minutes <= 0L) {
            return remainingSeconds + "s";
        }
        return minutes + "m " + remainingSeconds + "s";
    }

    private record AlarmSnapshot(String status, @Nullable String remainingText) {
    }

    private record FlockSnapshot(String membershipType, @Nullable UUID flockId) {
    }
}
