package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** Right-side HUD shown while a command item targets a supported NPC. */
public final class TameworkCommandTargetHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:command_target";
    public static final String UI_PATH = "TameworkCommandTargetHud.ui";

    private CommandTargetHudViewModel model;
    private String language;

    public TameworkCommandTargetHud(@Nonnull PlayerRef playerRef,
                                    @Nonnull CommandTargetHudViewModel model,
                                    String language) {
        super(playerRef, HUD_KEY);
        this.model = model;
        this.language = language;
    }

    public void refresh(@Nonnull CommandTargetHudViewModel updatedModel, String updatedLanguage) {
        this.model = updatedModel;
        this.language = updatedLanguage;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        CommandTargetHudBinder.bind(commandBuilder, updatedModel, updatedLanguage);
        update(false, commandBuilder);
    }

    public void hideNow() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        update(true, commandBuilder);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        CommandTargetHudBinder.bind(commandBuilder, model, language);
    }
}
