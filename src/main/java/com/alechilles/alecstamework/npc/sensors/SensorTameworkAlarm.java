package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.alarms.TameworkAlarmService;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkAlarm;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that matches a named alarm stored in {@code TameworkAlarmComponent}.
 */
public final class SensorTameworkAlarm extends TameworkSensorBase {
    private final String name;
    private final AlarmState state;
    private final boolean clear;

    public SensorTameworkAlarm(@Nonnull BuilderSensorTameworkAlarm builder,
                               @Nonnull BuilderSupport support) {
        super(builder);
        this.name = builder.getName(support);
        this.state = AlarmState.fromConfig(builder.getState(support));
        this.clear = builder.isClear(support);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        TameworkAlarmService.Snapshot snapshot = TameworkAlarmService.snapshot(ref, store, name);
        boolean matched = state.matches(snapshot);
        if (matched && clear) {
            TameworkAlarmService.clearAlarm(ref, store, name);
        }
        return matched;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    enum AlarmState {
        UNSET {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return snapshot.valid && !snapshot.exists;
            }
        },
        ACTIVE {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return snapshot.active;
            }
        },
        PASSED {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return snapshot.passed;
            }
        },
        READY {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return snapshot.ready;
            }
        },
        SET {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return snapshot.exists;
            }
        },
        UNKNOWN {
            @Override
            boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot) {
                return false;
            }
        };

        abstract boolean matches(@Nonnull TameworkAlarmService.Snapshot snapshot);

        @Nonnull
        static AlarmState fromConfig(@Nullable String configured) {
            if (configured == null || configured.isBlank()) {
                return PASSED;
            }
            String normalized = configured.trim().replace('-', '_').replace(' ', '_').toUpperCase();
            try {
                return AlarmState.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }
}
