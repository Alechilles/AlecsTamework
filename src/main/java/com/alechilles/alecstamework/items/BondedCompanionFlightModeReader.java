package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Reads the active controller family for an explicitly configured companion capability. */
final class BondedCompanionFlightModeReader {
    Optional<Boolean> read(@Nullable NPCEntity npc,
                           @Nullable TwCompanionFlightToggleSettings settings) {
        return read(settings, npc == null ? null : npc::getRole);
    }

    Optional<Boolean> read(@Nullable TwCompanionFlightToggleSettings settings,
                           @Nullable Supplier<Role> roleSupplier) {
        if (settings == null || !settings.isConfigured()) {
            return Optional.empty();
        }
        return readLiveRole(roleSupplier);
    }

    Optional<Boolean> readLiveRole(@Nullable Supplier<Role> roleSupplier) {
        try {
            Role role = roleSupplier == null ? null : roleSupplier.get();
            return readLiveController(role == null
                    ? null : role::getActiveMotionController);
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    Optional<Boolean> readLiveController(
            @Nullable Supplier<?> controllerSupplier
    ) {
        try {
            Object active = controllerSupplier == null
                    ? null : controllerSupplier.get();
            return classify(active == null ? null : active.getClass());
        } catch (RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    static Optional<Boolean> classify(@Nullable Class<?> controllerClass) {
        if (controllerClass == null) {
            return Optional.empty();
        }
        if (MotionControllerFly.class.isAssignableFrom(controllerClass)) {
            return Optional.of(true);
        }
        if (MotionControllerWalk.class.isAssignableFrom(controllerClass)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }
}
