package com.alechilles.alecstamework.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AvatarFlightHudBinderTest {
    @Test
    void flightHudUsesClientSafeCustomLayer() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkAvatarFlightHud.java"
        ));

        Assertions.assertTrue(source.contains("private static final int HUD_Z_ORDER = 1;"));
        Assertions.assertTrue(source.contains("super(playerRef, HUD_KEY, HUD_Z_ORDER);"));
        Assertions.assertFalse(source.contains("HUD_Z_ORDER = 10"));
        Assertions.assertFalse(source.contains("HUD_Z_ORDER = 100"));
    }

    @Test
    void binderUsesDynamicAnchorsAndSixPipSelectors() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java"
        ));

        Assertions.assertTrue(source.contains("final class AvatarFlightHudBinder"));
        Assertions.assertFalse(source.contains("public final class AvatarFlightHudBinder"));
        Assertions.assertTrue(source.contains("UICommandBuilder"));
        Assertions.assertTrue(source.contains("setObject"));
        Assertions.assertTrue(source.contains("Anchor"));
        Assertions.assertTrue(source.contains("Value.of("));
        Assertions.assertTrue(source.contains("#Root.Visible"));
        Assertions.assertTrue(source.contains("#TameworkAvatarFlightControls.Visible"));
        Assertions.assertFalse(source.contains("#ControlsOverlay.Visible"));
        Assertions.assertTrue(source.contains("LAUNCH_TRACK_WIDTH"));
        Assertions.assertTrue(source.contains("LAUNCH_FILL_MAX_WIDTH"));
        Assertions.assertTrue(source.contains("LAUNCH_MIN_MARKER_WIDTH"));
        Assertions.assertTrue(source.contains("#LaunchChargeGroup.Visible"));
        Assertions.assertTrue(source.contains("#LaunchChargeFill.Anchor"));
        Assertions.assertTrue(source.contains("#LaunchMinChargeMarker.Visible"));
        Assertions.assertTrue(source.contains("#LaunchMinChargeMarker.Anchor"));
        Assertions.assertTrue(source.contains("model.launchChargeVisible()"));
        Assertions.assertTrue(source.contains("model.launchChargeRatio()"));
        Assertions.assertTrue(source.contains("launchMarkerAnchor(model.launchMinChargeRatio())"));
        Assertions.assertFalse(source.contains("#Root.Background"),
                "the compact flight HUD root must not receive a dynamic background because it renders as a missing texture");
        Assertions.assertTrue(source.contains("#PitchLabel.Visible"));
        Assertions.assertTrue(source.contains("#PitchLabel.Text"));
        Assertions.assertTrue(source.contains("#SpeedFill.Anchor"));
        Assertions.assertTrue(source.contains("#TargetSpeedMarker.Visible"));
        Assertions.assertTrue(source.contains("#TargetSpeedMarker.Anchor"));
        Assertions.assertTrue(source.contains("#PipRow.Visible"));
        Assertions.assertTrue(source.contains("MAX_PIPS = 6"));
        Assertions.assertTrue(source.contains("targetMarkerAnchor(model.targetSpeedRatio())"));
        Assertions.assertTrue(source.contains("\"#VigourPip\" + i + \" #Fill.Anchor\""));
        Assertions.assertFalse(source.contains("DIMMED_BACKGROUND"));
        Assertions.assertFalse(source.contains("ACTIVE_BACKGROUND"));
    }

    @Test
    void uiAssetContainsExpectedCompactHudSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui"
        )).replace("\r\n", "\n");

        Assertions.assertTrue(ui.contains("Group #Root"));
        Assertions.assertFalse(ui.contains("Group #ControlsOverlay"));
        Assertions.assertTrue(ui.contains("Group #LaunchChargeGroup"));
        Assertions.assertTrue(ui.contains("Group #LaunchChargeTrack"));
        Assertions.assertTrue(ui.contains("Group #LaunchChargeFill"));
        Assertions.assertTrue(ui.contains("Group #LaunchMinChargeMarker"));
        Assertions.assertTrue(ui.contains("Label #PitchLabel"));
        Assertions.assertTrue(ui.contains("Group #SpeedTrack"));
        Assertions.assertTrue(ui.contains("Group #SpeedFill"));
        Assertions.assertTrue(ui.contains("Group #TargetSpeedMarker"));
        Assertions.assertTrue(ui.contains("Group #PipRow"));
        Assertions.assertFalse(ui.contains("@PageOverlay"),
                "compact HUD must not use modal page overlays because they draw a backdrop and placeholder panel");
        Assertions.assertFalse(ui.contains("LayoutMode: Middle"),
                "compact HUD should be positioned by its root anchor, not centered by a page overlay");
        for (int i = 0; i < 6; i++) {
            Assertions.assertTrue(ui.contains("Group #VigourPip" + i));
        }
        Assertions.assertEquals(6, countOccurrences(ui, "Group #Fill"));
        Assertions.assertEquals(1, countOccurrences(ui, "Group #LaunchChargeFill"));
        Assertions.assertTrue(ui.contains("Anchor: (Bottom: 178, Width: 178, Height: 70)"));
        Assertions.assertTrue(ui.contains("Anchor: (Top: 0, Left: 12, Width: 154, Height: 10)"));
        Assertions.assertTrue(ui.contains("Anchor: (Top: 18, Left: 0, Width: 178, Height: 12)"));
        Assertions.assertTrue(ui.contains("Anchor: (Top: 34, Left: 12, Width: 154, Height: 8)"));
        Assertions.assertTrue(ui.contains("Anchor: (Top: 50, Left: 16, Width: 145, Height: 10)"));
        Assertions.assertFalse(ui.contains("Background: #081220(0.78);"),
                "the HUD root must stay transparent because the panel background renders as a missing texture");
        Assertions.assertFalse(ui.contains("Background: (Color:"),
                "Group backgrounds in working Tamework HUDs use direct color literals; object color syntax can render as a placeholder");
        Assertions.assertFalse(ui.contains("Image:"),
                "launch charge HUD must use color groups, not image assets that can render as placeholders");
        Assertions.assertTrue(ui.contains("Background: #203044(0.92);"));
        Assertions.assertTrue(ui.contains("Background: #ff9a4b;"));
        Assertions.assertTrue(ui.contains("Background: #ffd765;"));
        Assertions.assertTrue(ui.contains("Background: #f1d36a;"));
        Assertions.assertTrue(ui.contains("Background: #f04444;"));
        Assertions.assertTrue(ui.contains("Style: (FontSize: 11, RenderBold: true, TextColor: #f2f6fb, HorizontalAlignment: Center, VerticalAlignment: Center)"));
        Assertions.assertTrue(ui.contains("Visible: false;"));
    }

    @Test
    void controlOverlayAssetProvidesCompleteToolOnlyAbilityRow() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/Hud/TameworkAvatarFlightControls.ui"
        )).replace("\r\n", "\n");

        Assertions.assertTrue(ui.contains("Group #TameworkAvatarFlightControls"));
        Assertions.assertTrue(ui.contains("@FlightControlSlotSize = 58"));
        Assertions.assertTrue(ui.contains("@FlightControlSlotHeight = 74"));
        Assertions.assertTrue(ui.contains("Anchor: (Right: 220, Bottom: 40, Width: 250, Height: @FlightControlSlotHeight)"));
        Assertions.assertTrue(ui.contains("LayoutMode: Left"));
        Assertions.assertTrue(ui.contains("Visible: false;"));
        Assertions.assertTrue(ui.contains("Group #ForwardBoostIcon"));
        Assertions.assertTrue(ui.contains("Group #UpwardFlapIcon"));
        Assertions.assertTrue(ui.contains("Group #AirbrakeIcon"));
        Assertions.assertTrue(ui.contains("Group #LaunchIcon"));
        Assertions.assertTrue(ui.contains("../Tamework/AvatarFlightControls/ForwardBoost.png"));
        Assertions.assertTrue(ui.contains("../Tamework/AvatarFlightControls/UpwardFlap.png"));
        Assertions.assertTrue(ui.contains("../Tamework/AvatarFlightControls/Airbrake.png"));
        Assertions.assertTrue(ui.contains("../Tamework/AvatarFlightControls/Launch.png"));
        Assertions.assertTrue(ui.contains("Text: \"CROUCH\";"));
        Assertions.assertTrue(ui.contains("Text: \"LMB\";"));
        Assertions.assertTrue(ui.contains("Text: \"Q\";"));
        Assertions.assertTrue(ui.contains("Text: \"RMB\";"));
        Assertions.assertEquals(4, countOccurrences(ui, "../Tamework/AvatarFlightControls/ControlFrame.png"));
        Assertions.assertTrue(ui.indexOf("Group #LaunchControl") < ui.indexOf("Group #ForwardBoostControl"));
        Assertions.assertTrue(ui.indexOf("Group #ForwardBoostControl") < ui.indexOf("Group #UpwardFlapControl"));
        Assertions.assertTrue(ui.indexOf("Group #UpwardFlapControl") < ui.indexOf("Group #AirbrakeControl"));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
