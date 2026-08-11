package com.alechilles.alecstamework.compat;

/**
 * Detects the active Hytale API generation without linking Update 6-only types.
 */
public final class HytaleApiLevel {
    private static final String UPDATE_6_MARKER =
            "com.hypixel.hytale.server.npc.instructions.ExecutionSupport";
    private static final boolean UPDATE_6_OR_LATER = classExists(UPDATE_6_MARKER);

    private HytaleApiLevel() {
    }

    public static boolean isUpdate6OrLater() {
        return UPDATE_6_OR_LATER;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, HytaleApiLevel.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
