package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandHotswapHudViewModel;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;

/** Vanilla-aligned Q/E/R command glyph strip shown for an equipped command flute. */
public final class TameworkCommandHotswapHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:command_hotswaps";
    public static final String UI_PATH = "Hud/TameworkCommandHotswapControls.ui";
    private static final int HUD_Z_ORDER = 1;

    private CommandHotswapHudViewModel model;

    public TameworkCommandHotswapHud(@Nonnull PlayerRef playerRef,
                                     @Nonnull CommandHotswapHudViewModel model) {
        super(playerRef, HUD_KEY, HUD_Z_ORDER);
        this.model = model;
    }

    public void refresh(@Nonnull CommandHotswapHudViewModel updatedModel) {
        model = updatedModel;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        CommandHotswapHudBinder.bind(commandBuilder, updatedModel);
        update(false, commandBuilder);
    }

    public void hideNow() {
        UICommandBuilder commandBuilder = new UICommandBuilder();
        update(true, commandBuilder);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        CommandHotswapHudBinder.bind(commandBuilder, model);
    }
}
