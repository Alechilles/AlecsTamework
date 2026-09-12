package com.alechilles.alecstamework;

import com.alechilles.alecstamework.lifecycle.TameworkEventRegistrationSupport;
import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator;
import com.alechilles.alecstamework.npc.systems.AmbientHerdSystem;
import com.alechilles.alecstamework.runtime.TameworkRuntimeParticipantRegistry;
import com.alechilles.alecstamework.runtime.activation.TameworkRuntimeModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * Owns the optional ambient runtime and exposes it to passive NPC builders only after activation.
 */
public final class TameworkAmbientHerdRuntimeParticipants {
    private static volatile AmbientHerdCoordinator installed;

    private TameworkAmbientHerdRuntimeParticipants() {}

    static void add(Tamework plugin, TameworkRuntimeParticipantRegistry participants) {
        var prepared = new AtomicReference<AmbientHerdCoordinator>();
        participants.entitySystem(
                TameworkRuntimeModule.AMBIENT_HERDS,
                "ambientherdsystem",
                () -> {
                    var coordinator = new AmbientHerdCoordinator();
                    prepared.set(coordinator);
                    return new AmbientHerdSystem(coordinator);
                });
        participants.listener(
                TameworkRuntimeModule.AMBIENT_HERDS,
                "ambientherdworldremoval",
                () -> {
                    var coordinator = prepared.get();
                    if (!TameworkEventRegistrationSupport.registerGlobal(
                            plugin,
                            Short.MAX_VALUE,
                            RemoveWorldEvent.class,
                            event -> clearWorld(coordinator, event.getWorld()),
                            "ambient herd world cleanup")) {
                        coordinator.close();
                        throw new IllegalStateException(
                                "Ambient herd cleanup listener could not be installed");
                    }
                    installed = coordinator;
                });
    }

    @Nullable
    public static AmbientHerdCoordinator current() {
        return installed;
    }

    static void closeInstalled() {
        var coordinator = installed;
        installed = null;
        if (coordinator != null) {
            coordinator.close();
            Universe universe = Universe.get();
            if (universe != null) {
                for (World world : universe.getWorlds().values()) clearWorld(coordinator, world);
            }
        }
    }

    private static void clearWorld(AmbientHerdCoordinator coordinator, World world) {
        if (world == null) {
            return;
        }
        if (!world.isInThread()) {
            // Removal can win the race with the final world callback. Release only scalars here.
            coordinator.forgetWorld(world.getWorldConfig().getUuid());
            if (world.isAlive()) {
                world.execute(() -> clearWorld(coordinator, world));
            }
            return;
        }
        if (world.getEntityStore() != null && world.getEntityStore().getStore() != null) {
            coordinator.clear(world.getEntityStore().getStore());
        }
    }
}
