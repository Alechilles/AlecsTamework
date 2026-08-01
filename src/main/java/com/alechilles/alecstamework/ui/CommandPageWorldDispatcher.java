package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Safely dispatches delayed UI callbacks to the page owner's current world. */
final class CommandPageWorldDispatcher {
    private CommandPageWorldDispatcher() { }

    static void dispatch(Ref<EntityStore> ref, Runnable task) {
        tryDispatch(ref, task);
    }
    static boolean tryDispatch(Ref<EntityStore> ref, Runnable task) {
        if (ref == null || !ref.isValid()) return false;
        Store<EntityStore> store = ref.getStore();
        if (store == null || store.getExternalData() == null) return false;
        World world = store.getExternalData().getWorld();
        if (world == null || !world.isAlive()) return false;
        try { world.execute(task); return true; } catch (RuntimeException ignored) { return false; }
    }
}
