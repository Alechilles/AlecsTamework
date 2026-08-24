package com.alechilles.alecstamework.api.commandui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Immutable value tree used for detached command UI presentation data. */
public final class CommandUiValue {
    /** Supported command UI value kinds. */
    public enum Type {
        STRING,
        BOOLEAN,
        LONG,
        DOUBLE,
        LIST,
        OBJECT
    }

    private final Type type;
    private final Object value;

    private CommandUiValue(@Nonnull Type type, @Nonnull Object value) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
    }

    @Nonnull
    public static CommandUiValue of(@Nonnull String value) {
        return string(value);
    }

    @Nonnull
    public static CommandUiValue of(boolean value) {
        return booleanValue(value);
    }

    @Nonnull
    public static CommandUiValue of(long value) {
        return longValue(value);
    }

    @Nonnull
    public static CommandUiValue of(double value) {
        return doubleValue(value);
    }

    @Nonnull
    public static CommandUiValue of(@Nonnull List<CommandUiValue> value) {
        return list(value);
    }

    @Nonnull
    public static CommandUiValue of(@Nonnull Map<String, CommandUiValue> value) {
        return object(value);
    }

    @Nonnull
    public static CommandUiValue string(@Nonnull String value) {
        return new CommandUiValue(Type.STRING, Objects.requireNonNull(value, "value"));
    }

    @Nonnull
    public static CommandUiValue booleanValue(boolean value) {
        return new CommandUiValue(Type.BOOLEAN, value);
    }

    @Nonnull
    public static CommandUiValue longValue(long value) {
        return new CommandUiValue(Type.LONG, value);
    }

    @Nonnull
    public static CommandUiValue doubleValue(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Command UI double values must be finite.");
        }
        return new CommandUiValue(Type.DOUBLE, value);
    }

    @Nonnull
    public static CommandUiValue list(@Nonnull List<CommandUiValue> values) {
        Objects.requireNonNull(values, "values");
        return new CommandUiValue(Type.LIST, List.copyOf(values));
    }

    @Nonnull
    public static CommandUiValue object(@Nonnull Map<String, CommandUiValue> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, CommandUiValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CommandUiValue> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "object key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Command UI object keys must be nonblank.");
            }
            copy.put(key, Objects.requireNonNull(entry.getValue(), "object value"));
        }
        return new CommandUiValue(
                Type.OBJECT,
                Collections.unmodifiableMap(copy)
        );
    }

    @Nonnull
    public Type type() {
        return type;
    }

    @Nonnull
    public String stringValue() {
        return cast(Type.STRING, String.class);
    }

    public boolean booleanValue() {
        return cast(Type.BOOLEAN, Boolean.class);
    }

    public long longValue() {
        return cast(Type.LONG, Long.class);
    }

    public double doubleValue() {
        return cast(Type.DOUBLE, Double.class);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public List<CommandUiValue> listValue() {
        return cast(Type.LIST, (Class<List<CommandUiValue>>) (Class<?>) List.class);
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public Map<String, CommandUiValue> objectValue() {
        return cast(Type.OBJECT, (Class<Map<String, CommandUiValue>>) (Class<?>) Map.class);
    }

    /** Returns the immutable Java value represented by this node. */
    @Nonnull
    public Object rawValue() {
        return value;
    }

    private <T> T cast(@Nonnull Type expected, @Nonnull Class<T> valueType) {
        if (type != expected) {
            throw new IllegalStateException(
                    "Command UI value is " + type + ", not " + expected
            );
        }
        return valueType.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandUiValue that)) {
            return false;
        }
        return type == that.type && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return type + "(" + value + ")";
    }
}
