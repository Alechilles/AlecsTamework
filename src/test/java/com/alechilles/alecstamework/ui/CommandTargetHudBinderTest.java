package com.alechilles.alecstamework.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudBinderTest {
    @Test
    void binderControlsOptionalRowsExplicitly() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/CommandTargetHudBinder.java"
        ));

        Assertions.assertTrue(source.contains("#FoodRow.Visible"));
        Assertions.assertTrue(source.contains("\"#AttachmentRow\" + i"));
        Assertions.assertTrue(source.contains("selector + \".Visible\""));
        Assertions.assertTrue(source.contains("#TameRequirementRow.Visible"));
        Assertions.assertTrue(source.contains("#HarvestCooldownRow"));
        Assertions.assertTrue(source.contains("#BreedingCooldownRow"));
    }

    @Test
    void uiAssetContainsExpectedSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui"
        ));

        Assertions.assertTrue(ui.contains("FoodRow"));
        Assertions.assertTrue(ui.contains("FoodIcon"));
        Assertions.assertTrue(ui.contains("AttachmentRow0"));
        Assertions.assertTrue(ui.contains("TameRequirementRow"));
        Assertions.assertTrue(ui.contains("HealthText"));
    }
}
