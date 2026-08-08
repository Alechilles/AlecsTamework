package com.alechilles.alecstamework.items;

import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the configured or bundled glyph texture for one command hotswap. */
final class CommandHotswapHudIconResolver {
    private static final String ICON_ROOT = "Tamework/CommandHotswaps/";

    private CommandHotswapHudIconResolver() {
    }

    @Nonnull
    static String resolve(@Nullable String configuredIcon, @Nullable String commandId) {
        String explicit = normalize(configuredIcon);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return switch (normalize(commandId).toUpperCase(Locale.ROOT)) {
            case "FOLLOW" -> ICON_ROOT + "Follow.png";
            case "HOLD" -> ICON_ROOT + "Hold.png";
            case "RECALL" -> ICON_ROOT + "Recall.png";
            case "MOVETOPING" -> ICON_ROOT + "MoveToPing.png";
            case "DEFEND" -> ICON_ROOT + "Defend.png";
            case "AGGRESSIVE" -> ICON_ROOT + "Aggressive.png";
            case "ATTACKTARGET" -> ICON_ROOT + "AttackTarget.png";
            case "IDLE" -> ICON_ROOT + "Idle.png";
            case "SETHOME", "RETURNHOME" -> ICON_ROOT + "Home.png";
            case "TOGGLEAIRBORNEMODE", "TOGGLEFROSTDRAGONAIRBORNEMODE" ->
                    ICON_ROOT + "FlightToggle.png";
            default -> "";
        };
    }

    @Nonnull
    static String fallbackGlyph(@Nullable String commandId) {
        String normalized = normalize(commandId);
        return normalized.isEmpty() ? "?" : normalized.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
