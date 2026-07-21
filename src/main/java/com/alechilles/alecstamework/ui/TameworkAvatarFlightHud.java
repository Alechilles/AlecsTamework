package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.avatarflight.AvatarFlightHudViewModel;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Compact bottom HUD shown while avatar flight vigour is active. */
public final class TameworkAvatarFlightHud extends CustomUIHud {
    public static final String HUD_KEY = "alecstamework:avatar_flight";
    public static final String UI_PATH = "TameworkAvatarFlightHud.ui";
    // Public HUD overlays use this bounded layer; layer 100 crashes the 0.5.6 client.
    private static final int HUD_Z_ORDER = 10;

    private AvatarFlightHudViewModel model;

    public TameworkAvatarFlightHud(@Nonnull PlayerRef playerRef,
                                   @Nonnull AvatarFlightHudViewModel model) {
        super(playerRef, HUD_KEY, HUD_Z_ORDER);
        this.model = model;
    }

    public void refresh(@Nonnull AvatarFlightHudViewModel updatedModel) {
        this.model = updatedModel;
        UICommandBuilder commandBuilder = new UICommandBuilder();
        AvatarFlightHudBinder.bind(commandBuilder, updatedModel);
        update(false, commandBuilder);
    }

    public static void removeFrom(@Nullable Player player) {
        if (player == null || player.getPlayerRef() == null || player.getHudManager() == null) {
            return;
        }
        player.getHudManager().removeCustomHud(player.getPlayerRef(), TameworkAvatarFlightHud.HUD_KEY);
    }

    @Override
    protected void build(@Nonnull UICommandBuilder commandBuilder) {
        commandBuilder.append(UI_PATH);
        AvatarFlightHudBinder.bind(commandBuilder, model);
    }
}
