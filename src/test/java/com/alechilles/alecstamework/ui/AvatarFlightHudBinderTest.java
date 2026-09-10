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
        Assertions.assertTrue(source.contains("#Ability2Control"));
        Assertions.assertTrue(source.contains("#Ability3Control"));
        Assertions.assertTrue(source.contains("#Ability2Glyph"));
        Assertions.assertTrue(source.contains("#Ability3Glyph"));
        Assertions.assertTrue(source.contains("glyph.visible() ? glyph.glyph() : \"\""),
                "hidden combat cells must clear their glyph text before a later refresh");
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
    void binderUsesTextureGlyphsWhenTheCombatGlyphProvidesOne() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java"
        ));

        Assertions.assertTrue(source.contains("iconSelector + \".Background\""));
        Assertions.assertTrue(source.contains("glyph.hasIconTexturePath()"));
        Assertions.assertTrue(source.contains("glyph.iconTexturePath()"));
        Assertions.assertTrue(source.contains("glyphTextSelector + \".Visible\""));
    }

    @Test
    void binderShowsCooldownShadeAndWholeSecondLabelForCombatGlyphs() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/AvatarFlightHudBinder.java"
        ));
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/Hud/TameworkAvatarFlightControls.ui"
        ));

        Assertions.assertTrue(source.contains("CooldownShade"));
        Assertions.assertTrue(source.contains("glyph.coolingDown()"));
        Assertions.assertTrue(source.contains("glyph.cooldownLabel()"));
        Assertions.assertTrue(ui.contains("#Ability2CooldownShade"));
        Assertions.assertTrue(ui.contains("#Ability3CooldownShade"));
        Assertions.assertTrue(ui.contains("#Ability2CooldownLabel"));
        Assertions.assertTrue(ui.contains("#Ability3CooldownLabel"));
    }

    @Test
    void generatedCombatGlyphTexturesAreBundledAtHudResolution() throws Exception {
        assertPngDimensions("Fireball.png");
        assertPngDimensions("FireBreath.png");
    }

    private static void assertPngDimensions(String fileName) throws Exception {
        Path image = Path.of("src/main/resources/Common/UI/Custom/Tamework/AvatarFlightControls", fileName);
        Assertions.assertTrue(Files.isRegularFile(image));
        byte[] header = Files.readAllBytes(image);
        Assertions.assertTrue(header.length >= 24);
        int width = ((header[16] & 0xff) << 24) | ((header[17] & 0xff) << 16)
                | ((header[18] & 0xff) << 8) | (header[19] & 0xff);
        int height = ((header[20] & 0xff) << 24) | ((header[21] & 0xff) << 16)
                | ((header[22] & 0xff) << 8) | (header[23] & 0xff);
        Assertions.assertEquals(58, width);
        Assertions.assertEquals(58, height);
    }

}
