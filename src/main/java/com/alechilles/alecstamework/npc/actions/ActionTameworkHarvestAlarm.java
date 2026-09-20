package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.alechilles.alecstamework.api.internal.HusbandryYieldResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
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
        String multiplierEffectKey = resolveHarvestCooldownMultiplierEffectKey();
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                multiplierEffectKey,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier,
                recoverySpeedBonus(npcRef, store, role));
        return applyHarvestCooldown(npcRef, store, resolveHarvestAlarmName(), cooldownSeconds, false, false);
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
        String multiplierEffectKey = resolveHarvestCooldownMultiplierEffectKey();
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                multiplierEffectKey,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier,
                recoverySpeedBonus(npcRef, store, role));
        return applyHarvestCooldown(npcRef, store, resolveHarvestAlarmName(), cooldownSeconds, markHandled, false);
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
        TameworkAlarmService.Snapshot before = TameworkAlarmService.snapshot(npcRef, store, context.alarmName);

        boolean applied = applyHarvestCooldown(
                npcRef,
                store,
                context.alarmName,
                context.cooldownSeconds,
                markHandled,
                true
        );
        TameworkAlarmService.Snapshot after = TameworkAlarmService.snapshot(npcRef, store, context.alarmName);

        logHarvestCooldownDiagnostic(
                "apply-if-ready",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplierEffectKey,
                context.multiplier,
                context.cooldownSeconds,
                before,
                after,
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
        TameworkAlarmService.Snapshot alarm = TameworkAlarmService.snapshot(npcRef, store, context.alarmName);
        boolean ready = context.cooldownSeconds > 0.0 && alarm.ready;
        logHarvestCooldownDiagnostic(
                "ready-check",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplierEffectKey,
                context.multiplier,
                context.cooldownSeconds,
                alarm,
                alarm,
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
        TameworkAlarmService.Snapshot before = TameworkAlarmService.snapshot(npcRef, store, context.alarmName);
        boolean applied = before.valid && !before.ready;
        if (before.ready) {
            applied = context.cooldownSeconds > 0.0
                    && applyHarvestCooldown(npcRef, store, context.alarmName, context.cooldownSeconds, false, true);
        }
        TameworkAlarmService.Snapshot after = TameworkAlarmService.snapshot(npcRef, store, context.alarmName);
        logHarvestCooldownDiagnostic(
                "ensure-active",
                role,
                resolvedBaseSeconds,
                context.baseSeconds,
                context.multiplierEffectKey,
                context.multiplier,
                context.cooldownSeconds,
                before,
                after,
                applied
        );
        return applied;
    }

    static double resolveHarvestCooldownSeconds(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Role role,
                                                @Nullable Store<EntityStore> store) {
        HarvestCooldownContext context = resolveHarvestCooldownContext(
                npcRef,
                role,
                store,
                0.0,
                "debug-set"
        );
        return context == null ? 0.0 : context.cooldownSeconds;
    }

    private static boolean applyHarvestCooldown(@Nullable Ref<EntityStore> npcRef,
                                                @Nullable Store<EntityStore> store,
                                                @Nonnull String alarmName,
                                                double cooldownSeconds,
                                                boolean markHandled,
                                                boolean requireReady) {
        if (!requireReady && CompanionHarvestBonusService.consumeCooldownHandled(npcRef, store)) {
            return true;
        }
        TameworkAlarmService.Snapshot snapshot = TameworkAlarmService.snapshot(npcRef, store, alarmName);
        if (requireReady && !snapshot.ready) {
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
        if (!TameworkAlarmService.applyAlarm(npcRef, store, alarmName, cooldownSeconds)) {
            return false;
        }
        if (markHandled) {
            CompanionHarvestBonusService.markCooldownHandled(npcRef, store);
        }
        return true;
    }

    static double scaleHarvestCooldownSeconds(double baseSeconds, double multiplier) {
        return scaleHarvestCooldownSeconds(baseSeconds, multiplier, 0.0);
    }

    /**
     * Converts the existing duration multiplier to a speed contribution, then
     * combines it with new recovery speed once. Recovery uses
     * base / clamp(1 + bonuses, .25, 2), so the total can never fall below
     * half the base interval.
     */
    static double scaleHarvestCooldownSeconds(double baseSeconds,
                                              double multiplier,
                                              double recoverySpeedBonus) {
        double base = Double.isFinite(baseSeconds) ? Math.max(0.0, baseSeconds) : 0.0;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double legacySpeed = 1.0 / multiplier;
        double speed = legacySpeed + (Double.isFinite(recoverySpeedBonus) ? recoverySpeedBonus : 0.0);
        speed = Math.max(0.25, Math.min(2.0, speed));
        double scaled = base / speed;
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
        String multiplierEffectKey = resolveHarvestCooldownMultiplierEffectKey();
        double multiplier = CompanionProgressionModifierService.resolveMultiplier(
                npcRef,
                store,
                multiplierEffectKey,
                1.0
        );
        double cooldownSeconds = scaleHarvestCooldownSeconds(baseSeconds, multiplier,
                recoverySpeedBonus(npcRef, store, role));
        String alarmName = resolveHarvestAlarmName();
        return new HarvestCooldownContext(baseSeconds, multiplierEffectKey, multiplier, cooldownSeconds, alarmName);
    }

    private static void logMissingHarvestCooldownContext(String stage,
                                                        @Nullable Role role,
                                                        double resolvedBaseSeconds,
                                                        double baseSeconds,
                                                        double multiplier,
                                                        double cooldownSeconds,
                                                        @Nullable Object unused) {
        TameworkAlarmService.Snapshot snapshot = TameworkAlarmService.snapshot(null, null, resolveHarvestAlarmName());
        logHarvestCooldownDiagnostic(
                stage,
                role,
                resolvedBaseSeconds,
                baseSeconds,
                resolveHarvestCooldownMultiplierEffectKey(),
                multiplier,
                cooldownSeconds,
                snapshot,
                snapshot,
                false
        );
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
        EntitySupport support = NpcSupportAccess.entity(role, null, null);
        StdScope scope = support != null ? support.getSensorScope() : null;
        return HarvestAlarmTimeBasis.resolveHarvestTimeoutSeconds(scope, HARVEST_TIMEOUT_PARAMETER, random);
    }

    private static void logHarvestCooldownDiagnostic(String stage,
                                                     @Nullable Role role,
                                                     double resolvedBaseSeconds,
                                                     double baseSeconds,
                                                     String multiplierEffectKey,
                                                     double multiplier,
                                                     double cooldownSeconds,
                                                     @Nonnull TameworkAlarmService.Snapshot before,
                                                     @Nonnull TameworkAlarmService.Snapshot after,
                                                     boolean applied) {
        Tamework instance = Tamework.getInstance();
        if (instance == null || !instance.isDebugHarvestEnabled() || instance.getLogger() == null) {
            return;
        }
        instance.getLogger().at(Level.INFO).log(
                "TameworkHarvestDebug: cooldown"
                        + " stage=" + stage
                        + " role=" + roleName(role)
                        + " alarmName=" + before.name
                        + " resolvedBaseSeconds=" + resolvedBaseSeconds
                        + " baseSeconds=" + baseSeconds
                        + " multiplierEffectKey=" + multiplierEffectKey
                        + " multiplier=" + multiplier
                        + " cooldownSeconds=" + cooldownSeconds
                        + " setBefore=" + before.exists
                        + " readyBefore=" + before.ready
                        + " nowBefore=" + before.nowMs
                        + " untilBefore=" + before.untilMs
                        + " setAfter=" + after.exists
                        + " readyAfter=" + after.ready
                        + " nowAfter=" + after.nowMs
                        + " untilAfter=" + after.untilMs
                        + " applied=" + applied
        );
    }

    private static String roleName(@Nullable Role role) {
        String name = role != null ? role.getRoleName() : null;
        return name != null && !name.isBlank() ? name : "<null>";
    }

    static String resolveHarvestAlarmName() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestAlarmName() : null;
        return configured != null && !configured.isBlank() ? configured : HARVEST_ALARM_NAME;
    }

    private static String resolveHarvestCooldownMultiplierEffectKey() {
        TwGlobalConfig config = TwGlobalConfig.resolveActive();
        String configured = config != null ? config.getHarvestCooldownMultiplierEffectKey() : null;
        return configured != null && !configured.isBlank()
                ? configured
                : HARVEST_COOLDOWN_MULTIPLIER_EFFECT_KEY;
    }

    private static double recoverySpeedBonus(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             @Nullable Role role) {
        if (npcRef == null || store == null) {
            return 0.0;
        }
        HusbandryHarvestUseContext.CapturedUse use = HusbandryHarvestUseContext.current(npcRef, store);
        if (!use.tool().present()) {
            return HusbandryYieldResolver.harvestRecoverySpeedBonus(
                    npcRef, store, null, role == null ? null : role.getRoleName(), null);
        }
        return HusbandryYieldResolver.harvestRecoverySpeedBonus(
                npcRef, store, use.genericModifiers());
    }

    private static final class HarvestCooldownContext {
        private final double baseSeconds;
        private final String multiplierEffectKey;
        private final double multiplier;
        private final double cooldownSeconds;
        private final String alarmName;

        private HarvestCooldownContext(double baseSeconds,
                                       @Nonnull String multiplierEffectKey,
                                       double multiplier,
                                       double cooldownSeconds,
                                       @Nonnull String alarmName) {
            this.baseSeconds = baseSeconds;
            this.multiplierEffectKey = multiplierEffectKey;
            this.multiplier = multiplier;
            this.cooldownSeconds = cooldownSeconds;
            this.alarmName = alarmName;
        }
    }
}
