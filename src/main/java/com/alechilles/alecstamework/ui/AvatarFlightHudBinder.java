package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.avatarflight.AvatarFlightHudViewModel;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Binds avatar-flight speed and vigour charge values into the compact HUD asset. */
final class AvatarFlightHudBinder {
    private static final int MAX_PIPS = 6;
    private static final int SPEED_FILL_MAX_WIDTH = 152;
    private static final int SPEED_FILL_HEIGHT = 6;
    private static final int PIP_FILL_MAX_WIDTH = 18;
    private static final int PIP_FILL_HEIGHT = 8;
    private static final String ACTIVE_BACKGROUND = "#081220(0.78)";
    private static final String DIMMED_BACKGROUND = "#081220(0.36)";

    private AvatarFlightHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder,
                     @Nonnull AvatarFlightHudViewModel model) {
        commandBuilder.set("#Root.Visible", model.visible());
        commandBuilder.set("#Root.Background", model.dimmed() ? DIMMED_BACKGROUND : ACTIVE_BACKGROUND);
        commandBuilder.set("#SpeedTrack.Visible", model.visible());
        commandBuilder.setObject("#SpeedFill.Anchor", fillAnchor(SPEED_FILL_MAX_WIDTH, SPEED_FILL_HEIGHT, model.speedRatio()));
        commandBuilder.set("#PipRow.Visible", model.visible() && model.maxVigourCharges() > 0.0);
        for (int i = 0; i < MAX_PIPS; i++) {
            boolean pipVisible = model.visible() && i < Math.ceil(model.maxVigourCharges());
            double fill = pipVisible ? model.pipFill(i) : 0.0;
            commandBuilder.set("#VigourPip" + i + ".Visible", pipVisible);
            commandBuilder.set("#VigourPip" + i + " #Fill.Visible", fill > 0.0);
            commandBuilder.setObject("#VigourPip" + i + " #Fill.Anchor", fillAnchor(PIP_FILL_MAX_WIDTH, PIP_FILL_HEIGHT, fill));
        }
    }

    @Nonnull
    private static Anchor fillAnchor(int maxWidth, int height, double ratio) {
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(1));
        anchor.setLeft(Value.of(1));
        anchor.setWidth(Value.of(Math.max(0, (int) Math.round(maxWidth * clamp01(ratio)))));
        anchor.setHeight(Value.of(Math.max(0, height)));
        return anchor;
    }

    private static double clamp01(double ratio) {
        return Double.isFinite(ratio) ? Math.max(0.0, Math.min(1.0, ratio)) : 0.0;
    }
}
