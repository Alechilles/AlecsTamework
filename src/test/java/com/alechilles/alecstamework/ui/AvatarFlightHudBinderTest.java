package com.alechilles.alecstamework.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AvatarFlightHudBinderTest {
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
        Assertions.assertTrue(source.contains("#Root.Background"));
        Assertions.assertTrue(source.contains("#SpeedFill.Anchor"));
        Assertions.assertTrue(source.contains("#PipRow.Visible"));
        Assertions.assertTrue(source.contains("MAX_PIPS = 6"));
        Assertions.assertTrue(source.contains("\"#VigourPip\" + i + \" #Fill.Anchor\""));
        Assertions.assertTrue(source.contains("DIMMED_BACKGROUND"));
        Assertions.assertTrue(source.contains("ACTIVE_BACKGROUND"));
    }

    @Test
    void uiAssetContainsExpectedCompactHudSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkAvatarFlightHud.ui"
        )).replace("\r\n", "\n");

        Assertions.assertTrue(ui.contains("Group #Root"));
        Assertions.assertTrue(ui.contains("Group #SpeedTrack"));
        Assertions.assertTrue(ui.contains("Group #SpeedFill"));
        Assertions.assertTrue(ui.contains("Group #PipRow"));
        for (int i = 0; i < 6; i++) {
            Assertions.assertTrue(ui.contains("Group #VigourPip" + i));
        }
        Assertions.assertEquals(6, countOccurrences(ui, "Group #Fill"));
        Assertions.assertTrue(ui.contains("Anchor: (Bottom: 118"));
        Assertions.assertTrue(ui.contains("Visible: false;"));
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
