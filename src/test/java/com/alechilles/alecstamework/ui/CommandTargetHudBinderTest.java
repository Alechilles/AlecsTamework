package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
        Assertions.assertTrue(source.contains("#FoodStripBlock.Visible"));
        Assertions.assertTrue(source.contains("bindFoodStrip"));
        Assertions.assertTrue(source.contains("#FoodSlot"));
        Assertions.assertTrue(source.contains("bindFoodValue"));
        Assertions.assertTrue(source.contains("#FoodValuePositive"));
        Assertions.assertTrue(source.contains("#FoodValueNegative"));
        Assertions.assertTrue(source.contains("#FoodValueNeutral"));
        Assertions.assertTrue(source.contains("new ItemGridSlot(new ItemStack"));
        Assertions.assertTrue(source.contains(".Slots"));
        Assertions.assertTrue(source.contains("#FoodMore.Visible"));
        Assertions.assertTrue(source.contains("#GenderMaleIcon.Visible"));
        Assertions.assertTrue(source.contains("#GenderFemaleIcon.Visible"));
        Assertions.assertTrue(source.contains("\"#AttachmentRow\" + i"));
        Assertions.assertTrue(source.contains("selector + \".Visible\""));
        Assertions.assertTrue(source.contains("#Text.Text"));
        Assertions.assertTrue(source.contains("#TameRequirementRow.Visible"));
        Assertions.assertTrue(source.contains("LinkedNpcPanelVitalsBinder.bind"));
        Assertions.assertTrue(source.contains("HEALTH_FILL_MAX_WIDTH = 230"));
        Assertions.assertTrue(source.contains("LinkedNpcPanelVitalsBinder.bind(commandBuilder, \"#Root\", status, language, HEALTH_FILL_MAX_WIDTH)"));
        Assertions.assertTrue(source.contains("LinkedNpcPanelProgressionBinder.bindXpProgressRing"));
        Assertions.assertTrue(source.contains("LinkedNpcTraitIndicatorBinder.bind"));
        Assertions.assertTrue(source.contains("#StatusRingRow.Visible"));
        Assertions.assertTrue(source.contains("#ProgressionRow.Visible"));
        Assertions.assertTrue(source.contains("#TraitRingRow.Visible"));
        Assertions.assertTrue(source.contains("#OwnerRow.Visible"));
        Assertions.assertTrue(source.contains("#OwnerText.Text"));
        Assertions.assertTrue(source.contains("bindLayout"));
        Assertions.assertTrue(source.contains("#Root.Anchor"));
        Assertions.assertTrue(source.contains("#FoodTameRow.Anchor"));
        Assertions.assertTrue(source.contains("#OwnerRow.Anchor"));
        Assertions.assertTrue(source.contains("#AttachmentRow\" + i + \".Anchor"));
    }

    @Test
    void uiAssetContainsExpectedSelectors() throws Exception {
        String ui = Files.readString(Path.of(
                "src/main/resources/Common/UI/Custom/TameworkCommandTargetHud.ui"
        ));

        Assertions.assertTrue(ui.contains("FoodRow"));
        Assertions.assertTrue(ui.contains("FavoriteFoodBlock"));
        Assertions.assertTrue(ui.contains("FoodStripBlock"));
        Assertions.assertTrue(ui.contains("ItemGrid #FoodItemGrid"));
        Assertions.assertTrue(ui.contains("FoodSlot0"));
        Assertions.assertTrue(ui.contains("FoodSlot1"));
        Assertions.assertTrue(ui.contains("FoodSlot2"));
        Assertions.assertTrue(ui.contains("FoodSlot3"));
        Assertions.assertTrue(ui.contains("SlotSize: 28"));
        Assertions.assertTrue(ui.contains("SlotIconSize: 28"));
        Assertions.assertTrue(ui.contains("FoodValuePositive"));
        Assertions.assertTrue(ui.contains("TextColor: #5CF75F"));
        Assertions.assertTrue(ui.contains("FoodValueNegative"));
        Assertions.assertTrue(ui.contains("TextColor: #F75C5C"));
        Assertions.assertTrue(ui.contains("FoodValueNeutral"));
        Assertions.assertTrue(ui.contains("FoodMore"));
        Assertions.assertTrue(ui.contains("GenderMaleIcon"));
        Assertions.assertTrue(ui.contains("GenderFemaleIcon"));
        Assertions.assertTrue(ui.contains("RenderItemQualityBackground: false"));
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
        Assertions.assertTrue(ui.contains("OwnerRow"));
        Assertions.assertTrue(ui.contains("OwnerText"));
        Assertions.assertTrue(ui.contains("Group #FoodTameRow {\n        Anchor: (Top: 96, Left: 0, Right: 0, Height: 60);\n        Visible: false;"));
    }

    @Test
    void untamedFavoriteOnlyLayoutShrinksPanel() {
        CommandTargetHudBinder.Layout layout = CommandTargetHudBinder.resolveLayout(new CommandTargetHudViewModel(
                unloadedStatus("Doe"),
                new CommandTargetHudViewModel.FoodRow("AH_Lettuce", "Lettuce", null, 6.0),
                List.of(),
                List.of(),
                null,
                null
        ));

        Assertions.assertEquals(62, layout.foodTameTop());
        Assertions.assertEquals(36, layout.foodTameHeight());
        Assertions.assertEquals(120, layout.rootHeight());
        Assertions.assertEquals(0, layout.attachmentCount());
    }

    @Test
    void tamedDetailedLayoutStacksVisibleRowsWithoutFixedGaps() {
        CommandTargetHudBinder.Layout layout = CommandTargetHudBinder.resolveLayout(new CommandTargetHudViewModel(
                loadedNeedsStatus("Stag"),
                null,
                List.of(
                        new CommandTargetHudViewModel.FoodRow("AH_Lettuce", "Lettuce", null, 6.0),
                        new CommandTargetHudViewModel.FoodRow("AH_PremiumFeed", "Premium Feed", null, 10.0)
                ),
                List.of(
                        new CommandTargetHudViewModel.AttachmentRow("Antlers", "Brown"),
                        new CommandTargetHudViewModel.AttachmentRow("Fur Color", "Dark Brown")
                ),
                null,
                null
        ));

        Assertions.assertEquals(62, layout.statusTop());
        Assertions.assertEquals(96, layout.foodTameTop());
        Assertions.assertEquals(46, layout.foodTameHeight());
        Assertions.assertEquals(150, layout.firstAttachmentTop());
        Assertions.assertEquals(208, layout.rootHeight());
    }

    @Test
    void ownerRowAddsBottomAlignedHeightWhenVisible() {
        CommandTargetHudBinder.Layout layout = CommandTargetHudBinder.resolveLayout(new CommandTargetHudViewModel(
                unloadedStatus("Stag"),
                null,
                List.of(),
                List.of(),
                null,
                "Alec"
        ));

        Assertions.assertTrue(layout.ownerVisible());
        Assertions.assertEquals(62, layout.ownerTop());
        Assertions.assertEquals(102, layout.rootHeight());
    }

    private static LinkedNpcEntry unloadedStatus(String displayName) {
        return new LinkedNpcEntry(
                UUID.randomUUID(),
                displayName,
                100,
                100,
                0,
                0,
                null,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );
    }

    private static LinkedNpcEntry loadedNeedsStatus(String displayName) {
        return new LinkedNpcEntry(
                UUID.randomUUID(),
                displayName,
                100,
                100,
                80,
                100,
                null,
                70,
                100,
                60,
                100,
                true,
                false,
                false,
                false,
                false,
                false,
                0L,
                LinkedNpcTraitIndicator.EMPTY
        );
    }
}
