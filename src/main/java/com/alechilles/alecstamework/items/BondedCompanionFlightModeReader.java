package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCompanionFlightToggleSettings;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.Optional;
import javax.annotation.Nullable;

/** Reads the active controller family for an explicitly configured companion capability. */
final class BondedCompanionFlightModeReader {
    Optional<Boolean> read(@Nullable NPCEntity npc,
                           @Nullable TwCompanionFlightToggleSettings settings) {
        if (settings == null || !settings.isConfigured()) {
            return Optional.empty();
        }
        try {
            Role role = npc == null ? null : npc.getRole();
            Object active = role == null
                    ? null : role.getActiveMotionController();
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
