package com.alechilles.alecstamework.npc.components;

import com.alechilles.alecstamework.Tamework;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Stores durable named Tamework alarm windows on an NPC.
 */
public final class TameworkAlarmComponent implements Component<EntityStore> {
    private static final AlarmEntry[] EMPTY_ALARMS = new AlarmEntry[0];
    private static final BuilderCodec<AlarmEntry> ALARM_ENTRY_CODEC = BuilderCodec.builder(
            AlarmEntry.class,
            AlarmEntry::new
    )
        .append(
            new KeyedCodec<>("Name", Codec.STRING),
            AlarmEntry::setName,
            AlarmEntry::getName
        )
        .add()
        .append(
            new KeyedCodec<>("UntilMs", Codec.LONG),
            AlarmEntry::setUntilMs,
            AlarmEntry::getUntilMs
        )
        .add()
        .append(
            new KeyedCodec<>("StartedAtMs", Codec.LONG),
            AlarmEntry::setStartedAtMs,
            AlarmEntry::getStartedAtMs
        )
        .add()
        .append(
            new KeyedCodec<>("DurationMs", Codec.LONG),
            AlarmEntry::setDurationMs,
            AlarmEntry::getDurationMs
        )
        .add()
        .build();
    private static final ArrayCodec<AlarmEntry> ALARM_ENTRY_ARRAY_CODEC =
            new ArrayCodec<>(ALARM_ENTRY_CODEC, AlarmEntry[]::new);

    public static final BuilderCodec<TameworkAlarmComponent> CODEC = BuilderCodec.builder(
            TameworkAlarmComponent.class,
            TameworkAlarmComponent::new
    )
        .append(
            new KeyedCodec<>("Alarms", ALARM_ENTRY_ARRAY_CODEC),
            TameworkAlarmComponent::setAlarms,
            TameworkAlarmComponent::getAlarms
        )
        .add()
        .build();

    private AlarmEntry[] alarms = EMPTY_ALARMS;

    public static ComponentType<EntityStore, TameworkAlarmComponent> getComponentType() {
        Tamework instance = Tamework.getInstance();
        return instance != null ? instance.getAlarmComponentType() : null;
    }

    public AlarmEntry[] getAlarms() {
        return alarms == null ? EMPTY_ALARMS : alarms;
    }

    public void setAlarms(@Nullable AlarmEntry[] alarms) {
        this.alarms = sanitizeAlarms(alarms);
    }

    @Nullable
    public AlarmEntry getAlarm(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (AlarmEntry alarm : getAlarms()) {
            if (alarm != null && name.equals(alarm.name)) {
                return alarm;
            }
        }
        return null;
    }

    public void setAlarm(@Nullable String name, long startedAtMs, long untilMs) {
        if (name == null || name.isBlank()) {
            return;
        }
        long durationMs = durationBetween(startedAtMs, untilMs);
        setAlarm(new AlarmEntry(name, untilMs, startedAtMs, durationMs));
    }

    public void setAlarm(@Nullable String name, long startedAtMs, long durationMs, long untilMs) {
        if (name == null || name.isBlank()) {
            return;
        }
        setAlarm(new AlarmEntry(name, untilMs, startedAtMs, durationMs));
    }

    private static long durationBetween(long startedAtMs, long untilMs) {
        if (untilMs <= startedAtMs) {
            return 0L;
        }
        try {
            return Math.subtractExact(untilMs, startedAtMs);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    public void clearAlarm(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        List<AlarmEntry> kept = new ArrayList<>();
        for (AlarmEntry alarm : getAlarms()) {
            if (alarm != null && !name.equals(alarm.name)) {
                kept.add(alarm.clone());
            }
        }
        alarms = kept.isEmpty() ? EMPTY_ALARMS : kept.toArray(new AlarmEntry[0]);
    }

    public boolean isAlarmActive(@Nullable String name, long nowMs) {
        AlarmEntry alarm = getAlarm(name);
        return alarm != null && alarm.isActive(nowMs);
    }

    @Override
    public TameworkAlarmComponent clone() {
        TameworkAlarmComponent clone = new TameworkAlarmComponent();
        clone.setAlarms(getAlarms());
        return clone;
    }

    private void setAlarm(AlarmEntry entry) {
        List<AlarmEntry> values = new ArrayList<>();
        boolean replaced = false;
        for (AlarmEntry alarm : getAlarms()) {
            if (alarm == null) {
                continue;
            }
            if (entry.name.equals(alarm.name)) {
                values.add(entry.clone());
                replaced = true;
            } else {
                values.add(alarm.clone());
            }
        }
        if (!replaced) {
            values.add(entry.clone());
        }
        alarms = values.toArray(new AlarmEntry[0]);
    }

    private static AlarmEntry[] sanitizeAlarms(@Nullable AlarmEntry[] input) {
        if (input == null || input.length == 0) {
            return EMPTY_ALARMS;
        }
        List<AlarmEntry> values = new ArrayList<>(input.length);
        for (AlarmEntry alarm : input) {
            if (alarm == null || alarm.name == null || alarm.name.isBlank()) {
                continue;
            }
            values.add(alarm.clone());
        }
        return values.isEmpty() ? EMPTY_ALARMS : values.toArray(new AlarmEntry[0]);
    }

    /** Single named alarm window. */
    public static final class AlarmEntry implements Cloneable {
        private String name;
        private long untilMs;
        private long startedAtMs;
        private long durationMs;

        public AlarmEntry() {
        }

        public AlarmEntry(String name, long untilMs, long startedAtMs, long durationMs) {
            this.name = name;
            this.untilMs = untilMs;
            this.startedAtMs = startedAtMs;
            setDurationMs(durationMs);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getUntilMs() {
            return untilMs;
        }

        public void setUntilMs(long untilMs) {
            this.untilMs = untilMs;
        }

        public long getStartedAtMs() {
            return startedAtMs;
        }

        public void setStartedAtMs(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = Math.max(0L, durationMs);
        }

        public boolean isActive(long nowMs) {
            return untilMs != 0L && nowMs < untilMs;
        }

        @Override
        public AlarmEntry clone() {
            return new AlarmEntry(name, untilMs, startedAtMs, durationMs);
        }
    }
}
