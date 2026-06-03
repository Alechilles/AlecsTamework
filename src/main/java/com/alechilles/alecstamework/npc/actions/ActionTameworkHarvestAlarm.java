package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.progression.CompanionProgressionModifierService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import com.hypixel.hytale.server.npc.storage.AlarmStore;
import com.hypixel.hytale.server.npc.util.Alarm;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import com.hypixel.hytale.server.npc.util.expression.ValueType;
import java.time.Instant;
import java.util.function.DoubleSupplier;
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
        double baseSeconds = resolveHarvestTimeoutSeconds(npc);
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
        if (CompanionHarvestBonusService.consumeCooldownSkip(npcRef, store)) {
            return true;
        }
        alarm.set(npcRef, Instant.now().plusMillis(Math.max(0L, Math.round(cooldownSeconds * 1000.0))), store);
        return true;
    }

    static double scaleHarvestCooldownSeconds(double baseSeconds, double multiplier) {
        double base = Double.isFinite(baseSeconds) ? Math.max(0.0, baseSeconds) : 0.0;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            multiplier = 1.0;
        }
        double scaled = base * multiplier;
        return Double.isFinite(scaled) ? Math.max(0.0, scaled) : base;
    }

    private static double resolveHarvestTimeoutSeconds(@Nonnull NPCEntity npc) {
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
            if (scope.getType(HARVEST_TIMEOUT_PARAMETER) != ValueType.NUMBER) {
                return 0.0;
            }
            DoubleSupplier supplier = scope.getNumberSupplier(HARVEST_TIMEOUT_PARAMETER);
            double value = supplier != null ? supplier.getAsDouble() : 0.0;
            return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}
