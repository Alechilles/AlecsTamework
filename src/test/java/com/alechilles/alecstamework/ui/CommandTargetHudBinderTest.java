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
        Assertions.assertTrue(source.contains("#FavoriteFoodBlock.Visible"));
        Assertions.assertTrue(source.contains("#CompatibleFoodBlock.Visible"));
        Assertions.assertTrue(source.contains("bindCompatibleFoodIcons"));
        Assertions.assertTrue(source.contains("#CompatibleFoodIcon"));
        Assertions.assertTrue(source.contains("#CompatibleFoodMore.Visible"));
        Assertions.assertTrue(source.contains("#GenderMaleIcon.Visible"));
        Assertions.assertTrue(source.contains("#GenderFemaleIcon.Visible"));
        Assertions.assertTrue(source.contains("\"#AttachmentRow\" + i"));
        Assertions.assertTrue(source.contains("selector + \".Visible\""));
        Assertions.assertTrue(source.contains("#Text.Text"));
        Assertions.assertTrue(source.contains("#TameRequirementRow.Visible"));
        Assertions.assertTrue(source.contains("LinkedNpcPanelVitalsBinder.bind"));
        Assertions.assertTrue(source.contains("LinkedNpcPanelProgressionBinder.bindXpProgressRing"));
        Assertions.assertTrue(source.contains("LinkedNpcTraitIndicatorBinder.bind"));
        Assertions.assertTrue(source.contains("#StatusRingRow.Visible"));
        Assertions.assertTrue(source.contains("#ProgressionRow.Visible"));
        Assertions.assertTrue(source.contains("#TraitRingRow.Visible"));
    }

    @Test
    void uiAssetContainsExpectedSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui"
        ));

        Assertions.assertTrue(ui.contains("FoodRow"));
        Assertions.assertTrue(ui.contains("FavoriteFoodBlock"));
        Assertions.assertTrue(ui.contains("CompatibleFoodBlock"));
        Assertions.assertTrue(ui.contains("CompatibleFoodIcon0"));
        Assertions.assertTrue(ui.contains("CompatibleFoodIcon1"));
        Assertions.assertTrue(ui.contains("CompatibleFoodIcon2"));
        Assertions.assertTrue(ui.contains("CompatibleFoodMore"));
        Assertions.assertTrue(ui.contains("GenderMaleIcon"));
        Assertions.assertTrue(ui.contains("GenderFemaleIcon"));
        Assertions.assertTrue(ui.contains("FoodIcon"));
        Assertions.assertTrue(ui.contains("AttachmentRow0"));
        Assertions.assertTrue(ui.contains("AttachmentRow5"));
        Assertions.assertTrue(ui.contains("Label #Text"));
        Assertions.assertTrue(ui.contains("TameRequirementRow"));
        Assertions.assertTrue(ui.contains("HealthText"));
        Assertions.assertTrue(ui.contains("HealthTextShadow"));
        Assertions.assertTrue(ui.contains("HealthTooltip"));
        Assertions.assertTrue(ui.contains("StatusRingRow"));
        Assertions.assertTrue(ui.contains("NeedHappiness"));
        Assertions.assertTrue(ui.contains("NeedHunger"));
        Assertions.assertTrue(ui.contains("NeedThirst"));
        Assertions.assertTrue(ui.contains("BreedingCooldown"));
        Assertions.assertTrue(ui.contains("HarvestCooldown"));
        Assertions.assertTrue(ui.contains("ProgressionRow"));
        Assertions.assertTrue(ui.contains("Group #ProgressionRow {\n        Anchor: (Top: 62, Right: 0, Width: 56, Height: 26);"));
        Assertions.assertTrue(ui.contains("XpProgressRing"));
        Assertions.assertTrue(ui.contains("TalentPointAction"));
        Assertions.assertTrue(ui.contains("TraitRingRow"));
        Assertions.assertTrue(ui.contains("Group #TraitRingRow {\n            Anchor: (Top: 0, Right: 0, Width: 120, Height: 24);"));
        Assertions.assertTrue(ui.contains("TraitSlot0"));
    }
}
