package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

public final class TameworkMessageHud extends CustomUIHud {
    public static final String UI_PATH = "TameworkMessageHud.ui";
    private static final String ROOT_VISIBLE_SELECTOR = "#TameworkMessageRoot.Visible";
    private static final String MESSAGE_TEXT_SELECTOR = "#TameworkMessage.Text";
    private static final String MESSAGE_COLOR_SELECTOR = "#TameworkMessage.Style.TextColor";
    private static final long DEFAULT_COLOR = 0xffffffffL;

    private String message;
    private long messageColor = DEFAULT_COLOR;

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
        commandBuilder.setObject(MESSAGE_COLOR_SELECTOR, messageColor);
    }

    public void updateMessage(@Nonnull String message, long color) {
        this.message = message;
        this.messageColor = color;
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(ROOT_VISIBLE_SELECTOR, true);
        builder.set(MESSAGE_TEXT_SELECTOR, message);
        builder.setObject(MESSAGE_COLOR_SELECTOR, color);
        update(false, builder);
    }

    public void updateColor(long color) {
        this.messageColor = color;
        UICommandBuilder builder = new UICommandBuilder();
        builder.setObject(MESSAGE_COLOR_SELECTOR, color);
        update(false, builder);
    }

    public void hideMessage() {
        this.message = "";
        UICommandBuilder builder = new UICommandBuilder();
        builder.set(MESSAGE_TEXT_SELECTOR, "");
        builder.set(ROOT_VISIBLE_SELECTOR, false);
        update(false, builder);
    }
}
