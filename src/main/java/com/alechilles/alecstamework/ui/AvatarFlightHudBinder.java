package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.avatarflight.AvatarFlightHudViewModel;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import javax.annotation.Nonnull;

/** Binds avatar-flight speed and vigour charge values into the compact HUD asset. */
final class AvatarFlightHudBinder {
    private static final int MAX_PIPS = 6;
    private static final int SPEED_TRACK_WIDTH = 154;
    private static final int SPEED_FILL_MAX_WIDTH = 152;
    private static final int SPEED_FILL_HEIGHT = 6;
    private static final int TARGET_MARKER_WIDTH = 2;
    private static final int TARGET_MARKER_HEIGHT = 10;
    private static final int PIP_FILL_MAX_WIDTH = 18;
    private static final int PIP_FILL_HEIGHT = 8;
    private static final int LAUNCH_TRACK_WIDTH = 154;
    private static final int LAUNCH_FILL_MAX_WIDTH = 152;
    private static final int LAUNCH_FILL_HEIGHT = 8;
    private static final int LAUNCH_MIN_MARKER_WIDTH = 2;
    private static final int LAUNCH_MIN_MARKER_HEIGHT = 12;

    private AvatarFlightHudBinder() {
    }

    static void bind(@Nonnull UICommandBuilder commandBuilder,
                     @Nonnull AvatarFlightHudViewModel model) {
        commandBuilder.set("#Root.Visible", model.visible());
        commandBuilder.set("#ControlsOverlay.Visible", model.visible());
        commandBuilder.set("#LaunchChargeGroup.Visible", model.visible() && model.launchChargeVisible());
        commandBuilder.setObject("#LaunchChargeFill.Anchor",
                fillAnchor(LAUNCH_FILL_MAX_WIDTH, LAUNCH_FILL_HEIGHT, model.launchChargeRatio()));
        commandBuilder.set("#LaunchMinChargeMarker.Visible", model.visible() && model.launchChargeVisible());
        commandBuilder.setObject("#LaunchMinChargeMarker.Anchor", launchMarkerAnchor(model.launchMinChargeRatio()));
        commandBuilder.set("#PitchLabel.Visible", model.visible());
        commandBuilder.set("#PitchLabel.Text", model.pitchLabel());
        commandBuilder.set("#SpeedTrack.Visible", model.visible());
        commandBuilder.setObject("#SpeedFill.Anchor", fillAnchor(SPEED_FILL_MAX_WIDTH, SPEED_FILL_HEIGHT, model.speedRatio()));
        commandBuilder.set("#TargetSpeedMarker.Visible", model.visible());
        commandBuilder.setObject("#TargetSpeedMarker.Anchor", targetMarkerAnchor(model.targetSpeedRatio()));
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

    @Nonnull
    private static Anchor targetMarkerAnchor(double ratio) {
        int center = 1 + (int) Math.round(SPEED_FILL_MAX_WIDTH * clamp01(ratio));
        int left = Math.max(0, Math.min(SPEED_TRACK_WIDTH - TARGET_MARKER_WIDTH,
                center - TARGET_MARKER_WIDTH / 2));
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(-1));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(TARGET_MARKER_WIDTH));
        anchor.setHeight(Value.of(TARGET_MARKER_HEIGHT));
        return anchor;
    }

    @Nonnull
    private static Anchor launchMarkerAnchor(double ratio) {
        int center = 1 + (int) Math.round(LAUNCH_FILL_MAX_WIDTH * clamp01(ratio));
        int left = Math.max(0, Math.min(LAUNCH_TRACK_WIDTH - LAUNCH_MIN_MARKER_WIDTH,
                center - LAUNCH_MIN_MARKER_WIDTH / 2));
        Anchor anchor = new Anchor();
        anchor.setTop(Value.of(-2));
        anchor.setLeft(Value.of(left));
        anchor.setWidth(Value.of(LAUNCH_MIN_MARKER_WIDTH));
        anchor.setHeight(Value.of(LAUNCH_MIN_MARKER_HEIGHT));
        return anchor;
    }

    private static double clamp01(double ratio) {
        return Double.isFinite(ratio) ? Math.max(0.0, Math.min(1.0, ratio)) : 0.0;
    }
}
