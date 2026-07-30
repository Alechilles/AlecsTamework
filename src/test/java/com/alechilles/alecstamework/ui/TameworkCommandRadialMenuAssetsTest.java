package com.alechilles.alecstamework.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the cropped raster artwork used by Tamework's fixed command radial menu. */
class TameworkCommandRadialMenuAssetsTest {
    private static final Path UI = Path.of(
            "src/main/resources/Common/UI/Custom/TameworkCommandRadialMenu.ui"
    );
    private static final Path ASSET_ROOT = Path.of(
            "src/main/resources/Common/UI/Custom/Tamework/RadialMenu/Default"
    );
    private static final int[] SOURCE_SLICE_BY_SLOT = {6, 7, 0, 1, 2, 3, 4, 5};

    @Test
    void commandWheelUsesCroppedDefaultArtworkInStandaloneSlotOrder() throws Exception {
        String ui = Files.readString(UI, StandardCharsets.UTF_8);

        for (int slot = 0; slot < SOURCE_SLICE_BY_SLOT.length; slot++) {
            int sourceSlice = SOURCE_SLICE_BY_SLOT[slot];
            String style = styleBlock(ui, slot);
            assertTrue(style.contains(croppedPath(sourceSlice, "Default")));
            assertTrue(style.contains(croppedPath(sourceSlice, "Hover")));
            assertTrue(style.contains(croppedPath(sourceSlice, "Pressed")));
            assertTrue(Files.isRegularFile(cropped(sourceSlice, "Default")));
            assertTrue(Files.isRegularFile(cropped(sourceSlice, "Hover")));
            assertTrue(Files.isRegularFile(cropped(sourceSlice, "Pressed")));
        }

        assertTrue(ui.contains("Tamework/RadialMenu/Default/CommandWheelCenterPanel.png"));
        assertTrue(Files.isRegularFile(ASSET_ROOT.resolve("CommandWheelCenterPanel.png")));
        assertFalse(ui.contains("Tamework/Vector/"));
        assertFalse(ui.contains("Tamework/RadialMenu/Default/CommandWheelSlice"));
    }

    @Test
    void closeButtonUsesFooterSpaceBelowTheWheelContent() throws Exception {
        String ui = Files.readString(UI, StandardCharsets.UTF_8);

        assertTrue(ui.contains("Anchor: (Width: 920, Height: 832);"));
        assertTrue(ui.contains("Group #TameworkCommandMenuContent {"));
        assertTrue(ui.contains("Anchor: (Top: 36, Width: 920, Height: 760);"));
        assertTrue(ui.contains("TextButton #CommandMenuCloseButton {"));
        assertTrue(ui.contains("Anchor: (Bottom: 0, Width: 180, Height: 44, Left: 370);"));
        assertTrue(ui.contains("Style: $C.@SecondaryTextButtonStyle;"));
    }

    private static Path cropped(int sourceSlice, String state) {
        return ASSET_ROOT.resolve("Cropped")
                .resolve("CommandWheelSlice" + sourceSlice + "_" + state + ".png");
    }

    private static String croppedPath(int sourceSlice, String state) {
        return "Tamework/RadialMenu/Default/Cropped/CommandWheelSlice"
                + sourceSlice + "_" + state + ".png";
    }

    private static String styleBlock(String ui, int slot) {
        String marker = "@Slice" + slot + "ButtonStyle = TextButtonStyle(";
        int start = ui.indexOf(marker);
        assertTrue(start >= 0, "Missing button style " + slot + ".");
        int end = ui.indexOf(");", start);
        assertTrue(end >= 0, "Missing end of button style " + slot + ".");
        return ui.substring(start, end);
    }
}
