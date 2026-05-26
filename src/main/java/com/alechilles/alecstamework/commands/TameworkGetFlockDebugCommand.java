package com.alechilles.alecstamework.commands;

import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.group.EntityGroup;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
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
        StageGateSnapshot stageGate = resolveStageGate(candidate.ref, store, roleId, stage);
        String currentState = resolveStateName(npc);

        AlarmSnapshot alarm = resolveAlarmSnapshot(npc, store, DIRECT_FOLLOW_ALARM);
        Boolean flag = readDirectFollowFlag(npc);
        boolean derivedFlag = deriveDirectFollowFlag(stage, alarm);
        boolean usingDerivedFlag = flag == null;
        FlockSnapshot flock = resolveFlockSnapshot(candidate.ref, store);
        LeashDebugSnapshot leash = resolveLeashSnapshot(candidate.ref, npc, store, stageGate, currentState);

        StringBuilder message = new StringBuilder();
        message.append("Flock debug for NPC ")
                .append(npc.getUuid())
                .append(": role=")
                .append(roleId != null ? roleId : "<unknown>")
                .append(", stage=")
                .append(stage)
                .append(" [baby=")
                .append(stageGate.isBaby)
                .append(", adolescent=")
                .append(stageGate.isAdolescent)
                .append(", adult=")
                .append(stageGate.isAdult)
                .append("]")
                .append(", stageSource=")
                .append(stageGate.stageSource)
                .append(", worldTimeMs=")
                .append(stageGate.worldTimeMs)
                .append(", componentStage=")
                .append(stageGate.componentStage)
                .append(", bornAtMs=")
                .append(stageGate.bornAtMs)
                .append(", adolescentAtMs=")
                .append(stageGate.adolescentAtMs)
                .append(", adultAtMs=")
                .append(stageGate.adultAtMs)
                .append(", growthScaling=")
                .append(stageGate.growthScaling)
                .append(", fallbackAdultRole=")
                .append(stageGate.fallbackAdultRole)
                .append(", state=")
                .append(currentState)
                .append(", ")
                .append(DIRECT_FOLLOW_FLAG)
                .append("=")
                .append(flag != null ? flag : derivedFlag)
                .append(usingDerivedFlag ? " (derived)" : "")
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

        StringBuilder leashMessage = new StringBuilder();
        leashMessage.append("Flock leash debug: stateDefault=")
                .append(leash.stateDefault)
                .append(", leashEnabled=")
                .append(leash.leashEnabled)
                .append(", follower=")
                .append(leash.follower)
                .append(", hasLeader=")
                .append(leash.hasLeader)
                .append(", stageBaby=")
                .append(leash.stageBaby)
                .append(", stageAdult=")
                .append(leash.stageAdult)
                .append(", distToLeash=")
                .append(formatNumber(leash.distanceToLeash))
                .append(", distToLeader=")
                .append(formatNumber(leash.distanceToLeader))
                .append(", leashRadius=")
                .append(formatNumber(leash.leashRadius))
                .append(", stopDistance=")
                .append(formatNumber(leash.stopDistance))
                .append(", relSpeed=")
                .append(formatNumber(leash.relativeSpeed))
                .append(", outOfRange=")
                .append(leash.outOfRange)
                .append(", withinStopDistance=")
                .append(leash.withinStopDistance)
                .append(", adultLeashGateApprox=")
                .append(leash.adultLeashGateApprox);
        commandContext.sender().sendMessage(Message.raw(leashMessage.toString()));
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

    private static boolean deriveDirectFollowFlag(@Nonnull String stage, @Nonnull AlarmSnapshot alarm) {
        return "Baby".equalsIgnoreCase(stage) && "active".equalsIgnoreCase(alarm.status);
    }

    @Nonnull
    private static LeashDebugSnapshot resolveLeashSnapshot(@Nonnull Ref<EntityStore> npcRef,
                                                           @Nonnull NPCEntity npc,
                                                           @Nonnull Store<EntityStore> store,
                                                           @Nonnull StageGateSnapshot stageGate,
                                                           @Nonnull String currentState) {
        Role role = npc.getRole();
        StdScope scope = (role != null && role.getEntitySupport() != null)
                ? role.getEntitySupport().getSensorScope()
                : null;

        Boolean leashEnabledValue = getBooleanFromScope(scope, "LeashEnabled");
        Double leashRadiusValue = getNumberFromScope(scope, "FlockFollowerWanderRadius");
        Double stopDistanceValue = getNumberFromScope(scope, "FlockFollowerStopDistance");
        Double relativeSpeedValue = getNumberFromScope(scope, "FlockFollowerRelativeSpeed");

        boolean leashEnabled = leashEnabledValue == null || leashEnabledValue;
        double leashRadius = leashRadiusValue != null ? leashRadiusValue : Double.NaN;
        double stopDistance = stopDistanceValue != null ? stopDistanceValue : Double.NaN;
        double relativeSpeed = relativeSpeedValue != null ? relativeSpeedValue : Double.NaN;

        TransformComponent npcTransform = store.getComponent(npcRef, TransformComponent.getComponentType());
        Vector3d npcPosition = npcTransform != null ? npcTransform.getPosition() : null;
        Vector3d leashPoint = npc.getLeashPoint();
        double distanceToLeash = (npcPosition != null && leashPoint != null)
                ? npcPosition.distance(leashPoint)
                : Double.NaN;

        FlockMembership membership = store.getComponent(npcRef, FlockMembership.getComponentType());
        boolean follower = membership != null && membership.getMembershipType() == FlockMembership.Type.MEMBER;
        boolean hasLeader = false;
        double distanceToLeader = Double.NaN;
        if (membership != null) {
            Ref<EntityStore> flockRef = membership.getFlockRef();
            if (flockRef != null && flockRef.isValid()) {
                EntityGroup group = store.getComponent(flockRef, EntityGroup.getComponentType());
                if (group != null) {
                    Ref<EntityStore> leaderRef = group.getLeaderRef();
                    if (leaderRef != null && leaderRef.isValid() && !leaderRef.equals(npcRef)) {
                        hasLeader = true;
                        TransformComponent leaderTransform = store.getComponent(leaderRef, TransformComponent.getComponentType());
                        if (npcPosition != null && leaderTransform != null) {
                            distanceToLeader = npcPosition.distance(leaderTransform.getPosition());
                        }
                    }
                }
            }
        }

        boolean outOfRange = !Double.isNaN(distanceToLeash)
                && !Double.isNaN(leashRadius)
                && distanceToLeash > leashRadius;
        boolean withinStopDistance = !Double.isNaN(distanceToLeash)
                && !Double.isNaN(stopDistance)
                && distanceToLeash <= stopDistance;
        boolean stateDefault = currentState.endsWith(".Default");
        boolean adultLeashGateApprox = stateDefault
                && leashEnabled
                && follower
                && hasLeader
                && outOfRange
                && !stageGate.isBaby;
        return new LeashDebugSnapshot(
                stateDefault,
                leashEnabled,
                follower,
                hasLeader,
                stageGate.isBaby,
                stageGate.isAdult,
                distanceToLeash,
                distanceToLeader,
                leashRadius,
                stopDistance,
                relativeSpeed,
                outOfRange,
                withinStopDistance,
                adultLeashGateApprox
        );
    }

    @Nonnull
    private static StageGateSnapshot resolveStageGate(@Nonnull Ref<EntityStore> npcRef,
                                                      @Nonnull Store<EntityStore> store,
                                                      @Nullable String roleId,
                                                      @Nonnull String resolvedStage) {
        ComponentType<EntityStore, TameworkLifeStageComponent> type = TameworkLifeStageComponent.getComponentType();
        TameworkLifeStageComponent component = type != null ? store.getComponent(npcRef, type) : null;
        String normalizedResolved = normalizeStage(resolvedStage);
        boolean fallbackAdult = isAdultRoleFallback(roleId);
        long worldTimeMs = resolveGameTime(store).toEpochMilli();
        if (component == null) {
            return new StageGateSnapshot(
                    CompanionLifeStageService.STAGE_BABY.equals(normalizedResolved),
                    CompanionLifeStageService.STAGE_ADOLESCENT.equals(normalizedResolved),
                    CompanionLifeStageService.STAGE_ADULT.equals(normalizedResolved),
                    "fallback-role",
                    worldTimeMs,
                    "<none>",
                    0L,
                    0L,
                    0L,
                    false,
                    fallbackAdult
            );
        }
        String componentStage = component.getStage();
        return new StageGateSnapshot(
                CompanionLifeStageService.STAGE_BABY.equals(normalizedResolved),
                CompanionLifeStageService.STAGE_ADOLESCENT.equals(normalizedResolved),
                CompanionLifeStageService.STAGE_ADULT.equals(normalizedResolved),
                "component",
                worldTimeMs,
                componentStage != null && !componentStage.isBlank() ? componentStage : "<blank>",
                component.getBornAtMs(),
                component.getAdolescentAtMs(),
                component.getAdultAtMs(),
                component.isGrowthScalingEnabled(),
                fallbackAdult
        );
    }

    @Nonnull
    private static String resolveStateName(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role == null || role.getStateSupport() == null) {
            return "<unknown>";
        }
        String state = role.getStateSupport().getStateName();
        return state != null && !state.isBlank() ? state : "<unknown>";
    }

    @Nullable
    private static Boolean readDirectFollowFlag(@Nonnull NPCEntity npc) {
        Role role = npc.getRole();
        if (role == null || role.getEntitySupport() == null) {
            return null;
        }
        StdScope sensorScope = role.getEntitySupport().getSensorScope();
        if (sensorScope == null) {
            return null;
        }
        try {
            BooleanSupplier supplier = sensorScope.getBooleanSupplier(DIRECT_FOLLOW_FLAG);
            return supplier != null ? supplier.getAsBoolean() : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
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

    @Nonnull
    private static String normalizeStage(@Nullable String stage) {
        if (stage == null || stage.isBlank()) {
            return CompanionLifeStageService.STAGE_ADULT;
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if ("baby".equals(normalized)) {
            return CompanionLifeStageService.STAGE_BABY;
        }
        if ("adolescent".equals(normalized) || "juvenile".equals(normalized)) {
            return CompanionLifeStageService.STAGE_ADOLESCENT;
        }
        return CompanionLifeStageService.STAGE_ADULT;
    }

    private static boolean isAdultRoleFallback(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return true;
        }
        String normalized = roleId.trim().toLowerCase(Locale.ROOT);
        return !normalized.contains("baby") && !normalized.contains("adolescent");
    }

    @Nullable
    private static Boolean getBooleanFromScope(@Nullable StdScope scope, @Nonnull String key) {
        if (scope == null) {
            return null;
        }
        try {
            BooleanSupplier supplier = scope.getBooleanSupplier(key);
            return supplier != null ? supplier.getAsBoolean() : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Nullable
    private static Double getNumberFromScope(@Nullable StdScope scope, @Nonnull String key) {
        if (scope == null) {
            return null;
        }
        try {
            DoubleSupplier supplier = scope.getNumberSupplier(key);
            return supplier != null ? supplier.getAsDouble() : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    @Nonnull
    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record AlarmSnapshot(String status, @Nullable String remainingText) {
    }

    private record FlockSnapshot(String membershipType, @Nullable UUID flockId) {
    }

    private record StageGateSnapshot(boolean isBaby,
                                     boolean isAdolescent,
                                     boolean isAdult,
                                     String stageSource,
                                     long worldTimeMs,
                                     String componentStage,
                                     long bornAtMs,
                                     long adolescentAtMs,
                                     long adultAtMs,
                                     boolean growthScaling,
                                     boolean fallbackAdultRole) {
    }

    private record LeashDebugSnapshot(boolean stateDefault,
                                      boolean leashEnabled,
                                      boolean follower,
                                      boolean hasLeader,
                                      boolean stageBaby,
                                      boolean stageAdult,
                                      double distanceToLeash,
                                      double distanceToLeader,
                                      double leashRadius,
                                      double stopDistance,
                                      double relativeSpeed,
                                      boolean outOfRange,
                                      boolean withinStopDistance,
                                      boolean adultLeashGateApprox) {
    }
}
