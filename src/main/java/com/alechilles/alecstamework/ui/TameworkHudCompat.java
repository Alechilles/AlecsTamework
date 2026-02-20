package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.reflect.Method;

/**
 * Routes Tamework HUD updates through a shared multi-HUD provider when present, with a fallback to vanilla custom HUD.
 */
final class TameworkHudCompat {
    private static final String MULTI_HUD_CLASS_NAME = "com.buuz135.mhud.MultipleHUD";
    private static final String MULTI_HUD_LAYER_ID = "Tamework_Message_HUD";

    private static volatile boolean lookupDone;
    private static volatile boolean multiHudAvailable;
    private static volatile Object multiHudInstance;
    private static volatile Method multiHudSetCustomHud;

    private TameworkHudCompat() {
    }

    static boolean setCustomHud(Player player,
                                HudManager hudManager,
                                PlayerRef playerRef,
                                CustomUIHud customHud) {
        if (player == null || playerRef == null || !playerRef.isValid() || customHud == null) {
            return false;
        }
        if (setWithMultiHud(player, playerRef, customHud)) {
            return true;
        }
        if (hudManager == null) {
            return false;
        }
        hudManager.setCustomHud(playerRef, customHud);
        return true;
    }

    static boolean isMultiHudAvailable() {
        ensureMultiHudLookup();
        return multiHudAvailable;
    }

    private static boolean setWithMultiHud(Player player, PlayerRef playerRef, CustomUIHud customHud) {
        ensureMultiHudLookup();
        if (!multiHudAvailable || multiHudInstance == null || multiHudSetCustomHud == null) {
            return false;
        }
        try {
            multiHudSetCustomHud.invoke(multiHudInstance, player, playerRef, MULTI_HUD_LAYER_ID, customHud);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            multiHudAvailable = false;
            multiHudInstance = null;
            multiHudSetCustomHud = null;
            return false;
        }
    }

    private static void ensureMultiHudLookup() {
        if (lookupDone) {
            return;
        }
        synchronized (TameworkHudCompat.class) {
            if (lookupDone) {
                return;
            }
            lookupDone = true;
            try {
                Class<?> multiHudClass = Class.forName(MULTI_HUD_CLASS_NAME);
                Method getInstanceMethod = multiHudClass.getMethod("getInstance");
                Object instance = getInstanceMethod.invoke(null);
                Method setCustomHudMethod = resolveSetCustomHudMethod(multiHudClass);
                if (instance != null && setCustomHudMethod != null) {
                    multiHudInstance = instance;
                    multiHudSetCustomHud = setCustomHudMethod;
                    multiHudAvailable = true;
                }
            } catch (ReflectiveOperationException | RuntimeException ex) {
                multiHudAvailable = false;
                multiHudInstance = null;
                multiHudSetCustomHud = null;
            }
        }
    }

    private static Method resolveSetCustomHudMethod(Class<?> multiHudClass) {
        for (Method method : multiHudClass.getMethods()) {
            if (!"setCustomHud".equals(method.getName())) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != 4) {
                continue;
            }
            if (!Player.class.isAssignableFrom(paramTypes[0])) {
                continue;
            }
            if (!PlayerRef.class.isAssignableFrom(paramTypes[1])) {
                continue;
            }
            if (!String.class.equals(paramTypes[2])) {
                continue;
            }
            if (!CustomUIHud.class.isAssignableFrom(paramTypes[3])) {
                continue;
            }
            return method;
        }
        return null;
    }
}
