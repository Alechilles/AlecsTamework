package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

public final class TameworkMessageHud extends CustomUIHud {
    public static final String UI_PATH = "TameworkMessageHud.ui";
    private static final String ROOT_VISIBLE_SELECTOR = "#TameworkMessageRoot.Visible";
    private static final int FADE_STEP_COUNT = 6;

    private String message;

    public TameworkMessageHud(@Nonnull PlayerRef playerRef, String message) {
        super(playerRef);
        this.message = message;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        if (message != null) {
            for (int i = 0; i < FADE_STEP_COUNT; i++) {
                commandBuilder.set(textSelector(i), message);
            }
        }
    }

    public void updateMessage(@Nonnull String message) {
        this.message = message;
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(ROOT_VISIBLE_SELECTOR, true);
        for (int i = 0; i < FADE_STEP_COUNT; i++) {
            builder.set(textSelector(i), message);
            builder.set(visibleSelector(i), i == 0);
        }
        update(false, builder);
    }

    public void showFadeStep(int step) {
        if (step < 0 || step >= FADE_STEP_COUNT) {
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(ROOT_VISIBLE_SELECTOR, true);
        for (int i = 0; i < FADE_STEP_COUNT; i++) {
            builder.set(visibleSelector(i), i == step);
        }
        update(false, builder);
    }

    public void hideMessage() {
        this.message = "";
        UICommandBuilder builder = new UICommandBuilder();
        for (int i = 0; i < FADE_STEP_COUNT; i++) {
            builder.set(textSelector(i), "");
            builder.set(visibleSelector(i), false);
        }
        builder.set(ROOT_VISIBLE_SELECTOR, false);
        update(false, builder);
    }

    private static String textSelector(int step) {
        return "#TameworkMessage" + step + ".Text";
    }

    private static String visibleSelector(int step) {
        return "#TameworkMessage" + step + ".Visible";
    }
}
