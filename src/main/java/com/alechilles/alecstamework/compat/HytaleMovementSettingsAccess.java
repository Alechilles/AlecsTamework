package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementConfig;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Adapts player movement profiles and flight permission across Updates 5 and 6.
 */
public final class HytaleMovementSettingsAccess {
    private static final DefaultProfileBinding DEFAULT_PROFILE = bindDefaultProfile();
    private static final FlightBinding FLIGHT = bindFlight();

    private HytaleMovementSettingsAccess() {
    }

    public static boolean setDefaultProfile(@Nullable MovementManager manager,
                                            @Nullable MovementConfig profile,
                                            @Nullable PhysicsValues physics,
                                            @Nullable GameMode gameMode) {
        if (manager == null || profile == null || physics == null || gameMode == null) {
            return false;
        }
        Object profileArgument = DEFAULT_PROFILE.acceptsConfig() ? profile : profile.toPacket();
        try {
            DEFAULT_PROFILE.setter().invokeExact(manager, profileArgument, physics, gameMode);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    public static Object readFlightSetting(@Nullable MovementSettings settings) {
        if (settings == null) {
            return null;
        }
        try {
            return FLIGHT.getter().invokeExact(settings);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not read player flight permission", throwable);
        }
    }

    public static void allowFlight(@Nonnull MovementSettings settings) {
        writeFlightSetting(settings, FLIGHT.allowedValue());
    }

    public static void restoreFlightSetting(@Nonnull MovementSettings settings,
                                            @Nonnull Object value) {
        writeFlightSetting(settings, value);
    }

    private static void writeFlightSetting(@Nonnull MovementSettings settings,
                                           @Nonnull Object value) {
        try {
            FLIGHT.setter().invokeExact(settings, value);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not write player flight permission", throwable);
        }
    }

    @Nonnull
    private static DefaultProfileBinding bindDefaultProfile() {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            MethodHandle setter = lookup.findVirtual(
                    MovementManager.class,
                    "setDefaultSettings",
                    MethodType.methodType(
                            void.class, MovementConfig.class, PhysicsValues.class, GameMode.class)
            ).asType(MethodType.methodType(
                    void.class, MovementManager.class, Object.class, PhysicsValues.class, GameMode.class));
            return new DefaultProfileBinding(true, setter);
        } catch (NoSuchMethodException ignored) {
            return bindLegacyDefaultProfile(lookup);
        } catch (IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static DefaultProfileBinding bindLegacyDefaultProfile(@Nonnull MethodHandles.Lookup lookup) {
        try {
            MethodHandle setter = lookup.findVirtual(
                    MovementManager.class,
                    "setDefaultSettings",
                    MethodType.methodType(
                            void.class, MovementSettings.class, PhysicsValues.class, GameMode.class)
            ).asType(MethodType.methodType(
                    void.class, MovementManager.class, Object.class, PhysicsValues.class, GameMode.class));
            return new DefaultProfileBinding(false, setter);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static FlightBinding bindFlight() {
        try {
            Field field;
            Object allowedValue;
            try {
                field = MovementSettings.class.getField("fly");
                allowedValue = findEnumConstant(field.getType(), "Allowed");
            } catch (NoSuchFieldException ignored) {
                field = MovementSettings.class.getField("canFly");
                allowedValue = Boolean.TRUE;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle getter = lookup.unreflectGetter(field).asType(
                    MethodType.methodType(Object.class, MovementSettings.class));
            MethodHandle setter = lookup.unreflectSetter(field).asType(
                    MethodType.methodType(void.class, MovementSettings.class, Object.class));
            return new FlightBinding(getter, setter, allowedValue);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Nonnull
    private static Object findEnumConstant(@Nonnull Class<?> enumType,
                                           @Nonnull String name) {
        Object[] values = enumType.getEnumConstants();
        if (values != null) {
            for (Object value : values) {
                if (value instanceof Enum<?> enumValue && name.equals(enumValue.name())) {
                    return enumValue;
                }
            }
        }
        throw new IllegalStateException("Missing movement flight mode: " + name);
    }

    private record DefaultProfileBinding(
            boolean acceptsConfig,
            @Nonnull MethodHandle setter) {
    }

    private record FlightBinding(
            @Nonnull MethodHandle getter,
            @Nonnull MethodHandle setter,
            @Nonnull Object allowedValue) {
    }
}
