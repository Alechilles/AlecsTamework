package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sets the harvest-ready alarm using the role's harvest timeout and passive talent cooldown scaling.
 */
public final class ActionTameworkHarvestAlarm extends TameworkActionBase {
    private static final String HARVEST_ALARM_NAME = "Harvest_Ready";
    private static final String HARVEST_TIMEOUT_PARAMETER = "HarvestTimeout";
    private static final String HARVEST_COOLDOWN_MULTIPLIER_EFFECT_KEY = "HarvestCooldownMultiplier";

    public ActionTameworkHarvestAlarm(@Nonnull BuilderActionTameworkHarvestAlarm builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(@Nullable Ref<EntityStore> npcRef,
                              @Nullable Role role,
                              @Nullable InfoProvider infoProvider,
                              double dt,
                              @Nullable Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid() && store != null;
    }

    @Override
    public boolean execute(@Nullable Ref<EntityStore> npcRef,
                           @Nullable Role role,
                           @Nullable InfoProvider infoProvider,
                           double dt,
                           @Nullable Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        double baseSeconds = resolveHarvestTimeoutSeconds(npc, role, ThreadLocalRandom.current()::nextDouble);
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier);
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, HARVEST_ALARM_NAME);
        if (alarm == null) {
            return false;
        }
        return applyHarvestCooldown(npcRef, store, alarm, cooldownSeconds, false, false);
    }

    static boolean applyHarvestCooldown(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Role role,
                                        @Nullable Store<EntityStore> store,
                                        boolean markHandled) {
        return applyHarvestCooldown(npcRef, role, store, 0.0, markHandled);
    }

    static boolean applyHarvestCooldown(@Nullable Ref<EntityStore> npcRef,
                                        @Nullable Role role,
                                        @Nullable Store<EntityStore> store,
                                        double resolvedBaseSeconds,
                                        boolean markHandled) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            return false;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            return false;
        }
        double baseSeconds = resolvedBaseSeconds > 0.0
                ? resolvedBaseSeconds
                : resolveHarvestTimeoutSeconds(npc, role, ThreadLocalRandom.current()::nextDouble);
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier);
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            return false;
        }
        Alarm alarm = alarmStore.get(npc, HARVEST_ALARM_NAME);
        if (alarm == null) {
            return false;
        }
        return applyHarvestCooldown(npcRef, store, alarm, cooldownSeconds, markHandled, false);
    }

    static boolean applyHarvestCooldownIfReady(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Role role,
                                               @Nullable Store<EntityStore> store,
                                               double resolvedBaseSeconds,
                                               boolean markHandled) {
        HarvestCooldownContext context = resolveHarvestCooldownContext(
                npcRef,
                role,
                store,
                resolvedBaseSeconds,
                "apply-if-ready"
        );
        if (context == null) {
            return false;
        }
        boolean setBefore = context.alarm.isSet();
        boolean readyBefore = isAlarmReady(context.alarm, store);
        Instant nowBefore = resolveGameTime(store);
        Instant untilBefore = HarvestAlarmTimeBasis.readAlarmInstant(context.alarm);

        boolean applied = applyHarvestCooldown(npcRef, store, context.alarm, context.cooldownSeconds, markHandled, true);

        logHarvestCooldownDiagnostic(
                "apply-if-ready",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplier,
                context.cooldownSeconds,
                setBefore,
                readyBefore,
                nowBefore,
                untilBefore,
                context.alarm.isSet(),
                isAlarmReady(context.alarm, store),
                resolveGameTime(store),
                HarvestAlarmTimeBasis.readAlarmInstant(context.alarm),
                applied
        );
        return applied;
    }

    static boolean isHarvestCooldownReady(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Role role,
                                          @Nullable Store<EntityStore> store,
                                          double resolvedBaseSeconds) {
        HarvestCooldownContext context = resolveHarvestCooldownContext(
                npcRef,
                role,
                store,
                resolvedBaseSeconds,
                "ready-check"
        );
        if (context == null) {
            return false;
        }
        boolean ready = context.cooldownSeconds > 0.0 && isAlarmReady(context.alarm, store);
        logHarvestCooldownDiagnostic(
                "ready-check",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplier,
                context.cooldownSeconds,
                context.alarm.isSet(),
                ready,
                resolveGameTime(store),
                HarvestAlarmTimeBasis.readAlarmInstant(context.alarm),
                context.alarm.isSet(),
                ready,
                resolveGameTime(store),
                HarvestAlarmTimeBasis.readAlarmInstant(context.alarm),
                ready
        );
        return ready;
    }

    static boolean ensureHarvestCooldownActive(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Role role,
                                               @Nullable Store<EntityStore> store,
                                               double resolvedBaseSeconds) {
        HarvestCooldownContext context = resolveHarvestCooldownContext(
                npcRef,
                role,
                store,
                resolvedBaseSeconds,
                "ensure-active"
        );
        if (context == null) {
            return false;
        }
        boolean setBefore = context.alarm.isSet();
        boolean readyBefore = isAlarmReady(context.alarm, store);
        Instant nowBefore = resolveGameTime(store);
        Instant untilBefore = HarvestAlarmTimeBasis.readAlarmInstant(context.alarm);
        boolean applied = true;
        if (readyBefore) {
            applied = context.cooldownSeconds > 0.0
                    && applyHarvestCooldown(npcRef, store, context.alarm, context.cooldownSeconds, false, true);
        }
        logHarvestCooldownDiagnostic(
                "ensure-active",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplier,
                context.cooldownSeconds,
                setBefore,
                readyBefore,
                nowBefore,
                untilBefore,
                context.alarm.isSet(),
                isAlarmReady(context.alarm, store),
                resolveGameTime(store),
                HarvestAlarmTimeBasis.readAlarmInstant(context.alarm),
                applied
        );
        return applied;
    }

    private static boolean applyHarvestCooldown(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nonnull Alarm alarm,
                                                double cooldownSeconds,
                                                boolean markHandled,
                                                boolean requireReady) {
        if (!requireReady && CompanionHarvestBonusService.consumeCooldownHandled(npcRef, store)) {
            return true;
        }
        if (requireReady && !isAlarmReady(alarm, store)) {
            return false;
        }
        if (requireReady && cooldownSeconds <= 0.0) {
            return false;
        }
        if (CompanionHarvestBonusService.consumeCooldownSkip(npcRef, store)) {
            if (markHandled) {
                CompanionHarvestBonusService.markCooldownHandled(npcRef, store);
            }
            return true;
        }
        alarm.set(npcRef, HarvestAlarmTimeBasis.resolveCooldownUntil(resolveGameTime(store), cooldownSeconds), store);
        if (markHandled) {
            CompanionHarvestBonusService.markCooldownHandled(npcRef, store);
        }
        return true;
    }

    private static boolean isAlarmReady(@Nonnull Alarm alarm, @Nullable Store<EntityStore> store) {
        if (!alarm.isSet()) {
            return true;
        }
        Instant now = resolveGameTime(store);
        return now != null && alarm.hasPassed(now);
    }

    static double scaleHarvestCooldownSeconds(double baseSeconds, double multiplier) {
        double base = Double.isFinite(baseSeconds) ? Math.max(0.0, baseSeconds) : 0.0;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double scaled = base * multiplier;
        return Double.isFinite(scaled) ? Math.max(0.0, scaled) : base;
    }

    @Nullable
    private static HarvestCooldownContext resolveHarvestCooldownContext(@Nullable Ref<EntityStore> npcRef,
                                                                        @Nullable Role role,
                                                                        @Nullable Store<EntityStore> store,
                                                                        double resolvedBaseSeconds,
                                                                        String missingStage) {
        if (npcRef == null || !npcRef.isValid() || store == null) {
            logMissingHarvestCooldownContext(
                    missingStage + "-invalid-input",
                    role,
                    resolvedBaseSeconds,
                    0.0,
                    1.0,
                    0.0,
                    null
            );
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null) {
            logMissingHarvestCooldownContext(
                    missingStage + "-missing-npc",
                    role,
                    resolvedBaseSeconds,
                    0.0,
                    1.0,
                    0.0,
                    null
            );
            return null;
        }
        double baseSeconds = resolvedBaseSeconds > 0.0
                ? resolvedBaseSeconds
                : resolveHarvestTimeoutSeconds(npc, role, ThreadLocalRandom.current()::nextDouble);
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                HARVEST_COOLDOWN_MULTIPLIER_EFFECT_KEY,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier);
        AlarmStore alarmStore = npc.getAlarmStore();
        if (alarmStore == null) {
            logMissingHarvestCooldownContext(
                    missingStage + "-missing-alarm-store",
                    role,
                    resolvedBaseSeconds,
                    baseSeconds,
                    multiplier,
                    cooldownSeconds,
                    resolveGameTime(store)
            );
            return null;
        }
        Alarm alarm = alarmStore.get(npc, HARVEST_ALARM_NAME);
        if (alarm == null) {
            logMissingHarvestCooldownContext(
                    missingStage + "-missing-alarm",
                    role,
                    resolvedBaseSeconds,
                    baseSeconds,
                    multiplier,
                    cooldownSeconds,
                    resolveGameTime(store)
            );
            return null;
        }
        return new HarvestCooldownContext(baseSeconds, multiplier, cooldownSeconds, alarm);
    }

    private static void logMissingHarvestCooldownContext(String stage,
                                                        @Nullable Role role,
                                                        double resolvedBaseSeconds,
                                                        double baseSeconds,
                                                        double multiplier,
                                                        double cooldownSeconds,
                                                        @Nullable Instant now) {
        logHarvestCooldownDiagnostic(
                stage,
                role,
                resolvedBaseSeconds,
                baseSeconds,
                multiplier,
                cooldownSeconds,
                false,
                false,
                now,
                null,
                false,
                false,
                now,
                null,
                false
        );
    }

    @Nullable
    private static Instant resolveGameTime(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        WorldTimeResource time = store.getResource(WorldTimeResource.getResourceType());
        return time != null ? time.getGameTime() : null;
    }

    static double resolveHarvestTimeoutSeconds(@Nonnull NPCEntity npc,
                                               @Nullable Role role,
                                               @Nonnull DoubleSupplier random) {
        double roleSeconds = resolveHarvestTimeoutSeconds(role, random);
        if (roleSeconds > 0.0) {
            return roleSeconds;
        }
        int roleIndex = npc.getRoleIndex();
        if (roleIndex < 0) {
            return 0.0;
        }
        NPCPlugin npcPlugin = NPCPlugin.get();
        if (npcPlugin == null) {
            return 0.0;
        }
        Builder<Role> roleBuilder = npcPlugin.tryGetCachedValidRole(roleIndex);
        if (roleBuilder == null) {
            return 0.0;
        }
        BuilderParameters builderParameters = roleBuilder.getBuilderParameters();
        if (builderParameters == null) {
            return 0.0;
        }
        try {
            StdScope scope = builderParameters.createScope();
            return HarvestAlarmTimeBasis.resolveHarvestTimeoutSeconds(scope, HARVEST_TIMEOUT_PARAMETER, random);
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private static double resolveHarvestTimeoutSeconds(@Nullable Role role, @Nonnull DoubleSupplier random) {
        if (role == null) {
            return 0.0;
        }
        EntitySupport support = role.getEntitySupport();
        StdScope scope = support != null ? support.getSensorScope() : null;
        return HarvestAlarmTimeBasis.resolveHarvestTimeoutSeconds(scope, HARVEST_TIMEOUT_PARAMETER, random);
    }

    private static void logHarvestCooldownDiagnostic(String stage,
                                                     @Nullable Role role,
                                                     double resolvedBaseSeconds,
                                                     double baseSeconds,
                                                     double multiplier,
                                                     double cooldownSeconds,
                                                     boolean setBefore,
                                                     boolean readyBefore,
                                                     @Nullable Instant nowBefore,
                                                     @Nullable Instant untilBefore,
                                                     boolean setAfter,
                                                     boolean readyAfter,
                                                     @Nullable Instant nowAfter,
                                                     @Nullable Instant untilAfter,
                                                     boolean applied) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkHarvestDebug: cooldown"
                        + " stage=" + stage
                        + " role=" + roleName(role)
                        + " resolvedBaseSeconds=" + resolvedBaseSeconds
                        + " baseSeconds=" + baseSeconds
                        + " multiplier=" + multiplier
                        + " cooldownSeconds=" + cooldownSeconds
                        + " setBefore=" + setBefore
                        + " readyBefore=" + readyBefore
                        + " nowBefore=" + instantText(nowBefore)
                        + " untilBefore=" + instantText(untilBefore)
                        + " setAfter=" + setAfter
                        + " readyAfter=" + readyAfter
                        + " nowAfter=" + instantText(nowAfter)
                        + " untilAfter=" + instantText(untilAfter)
                        + " applied=" + applied
        );
    }

    private static String roleName(@Nullable Role role) {
        String name = role != null ? role.getRoleName() : null;
        return name != null && !name.isBlank() ? name : "<null>";
    }

    private static String instantText(@Nullable Instant instant) {
        return instant != null ? instant.toString() : "<null>";
    }

    private static final class HarvestCooldownContext {
        private final double baseSeconds;
        private final double multiplier;
        private final double cooldownSeconds;
        private final Alarm alarm;

        private HarvestCooldownContext(double baseSeconds,
                                       double multiplier,
                                       double cooldownSeconds,
                                       @Nonnull Alarm alarm) {
            this.baseSeconds = baseSeconds;
            this.multiplier = multiplier;
            this.cooldownSeconds = cooldownSeconds;
            this.alarm = alarm;
        }
    }
}
