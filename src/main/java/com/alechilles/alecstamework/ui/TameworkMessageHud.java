package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** Lightweight HUD used to display a single transient Tamework message. */
public final class TameworkMessageHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:message";
    public static final String UI_PATH = "TameworkMessageHud.ui";
    private static final int FADE_STEP_COUNT = 6;

    private final String message;

    public TameworkMessageHud(@Nonnull PlayerRef playerRef, String message) {
        super(playerRef, HUD_KEY);
        this.message = message == null ? "" : message;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        for (int i = 0; i < FADE_STEP_COUNT; i++) {
            commandBuilder.set(textSelector(i), message);
            commandBuilder.set(visibleSelector(i), i == 0 && !message.isBlank());
        }
    }

    private static String textSelector(int step) {
        return "#TameworkMessage" + step + ".Text";
    }

    private static String visibleSelector(int step) {
        return "#TameworkMessage" + step + ".Visible";
    }
}
