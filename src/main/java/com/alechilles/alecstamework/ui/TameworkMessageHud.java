package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

public final class TameworkMessageHud extends CustomUIHud {
    public static final String UI_PATH = "Custom/TameworkMessageHud.ui";
    private static final String MESSAGE_TEXT_SELECTOR = "#TameworkMessage.Text";
    private static final String MESSAGE_COLOR_SELECTOR = "#TameworkMessage.Style.TextColor";

    private String message;
    private String messageColor = "#ffffff";

    public TameworkMessageHud(@Nonnull PlayerRef playerRef, String message) {
        super(playerRef);
        this.message = message;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        if (message != null) {
            commandBuilder.set(MESSAGE_TEXT_SELECTOR, message);
        }
        if (messageColor != null) {
            commandBuilder.set(MESSAGE_COLOR_SELECTOR, messageColor);
        }
    }

    public void updateMessage(@Nonnull String message, @Nonnull String color) {
        this.message = message;
        this.messageColor = color;
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(MESSAGE_TEXT_SELECTOR, message);
        builder.set(MESSAGE_COLOR_SELECTOR, color);
        update(false, builder);
    }

    public void updateColor(@Nonnull String color) {
        this.messageColor = color;
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(MESSAGE_COLOR_SELECTOR, color);
        update(false, builder);
    }
}
