package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.items.CommandTargetHudViewModel;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudBinderTest {
    @Test
    void rendersNeedPercentagesAndMatchingFillWidths() {
        UICommandBuilder commands = bind(model(loadedNeedsStatus("Duck"), List.of()));
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#Root #NeedHappiness #NeedValueText.Text", "80%");
        expected.set("#Root #NeedHunger #NeedValueText.Text", "70%");
        expected.set("#Root #NeedThirst #NeedValueText.Text", "60%");
        Anchor fill = new Anchor();
        fill.setLeft(Value.of(0));
        fill.setTop(Value.of(26));
        fill.setWidth(Value.of(80));
        fill.setHeight(Value.of(6));
        expected.setObject("#NeedHappiness #MeterFill.Anchor", fill);
        assertCommands(expected, commands);
    }

    @Test
    void readyCooldownsRemainVisibleAndReserveSpaceWithoutNeeds() {
        LinkedNpcEntry ready = cooldownStatus(false);
        UICommandBuilder commands = bind(model(ready, List.of()));
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#CooldownRow.Visible", true);
        expected.set("#Root #BreedingCooldown.Visible", true);
        expected.set("#Root #HarvestCooldown.Visible", true);
        assertCommands(expected, commands);
        Assertions.assertTrue(data(commands, "#Root #HarvestCooldown #CooldownText.Text").toLowerCase().contains("ready"));
        var readyLayout = CommandTargetHudBinder.resolveLayout(model(ready, List.of()));
        var emptyLayout = CommandTargetHudBinder.resolveLayout(model(unloadedStatus("Duck"), List.of()));
        Assertions.assertTrue(readyLayout.rootHeight() > emptyLayout.rootHeight());
        UICommandBuilder cooling = bind(model(cooldownStatus(true), List.of()));
        Assertions.assertTrue(data(cooling, "#Root #BreedingCooldown #CooldownText.Text").contains("1:00"));
    }

    @Test
    void rendersAppearancePairsAndClearsOldRowsWhenTargetChanges() {
        var appearance = List.of(new CommandTargetHudViewModel.AttachmentRow("Coat", "Brown"),
                new CommandTargetHudViewModel.AttachmentRow("Eyes", "Red"));
        UICommandBuilder commands = bind(model(loadedNeedsStatus("Scrouge").withRoleSubtitle("Duck"), appearance));
        UICommandBuilder expected = new UICommandBuilder();
        expected.set("#RoleSubtitle.Text", "Duck");
        expected.set("#RoleSubtitle.Visible", true);
        expected.set("#AttachmentRow0 #Text.Text", "Coat: Brown");
        expected.set("#AttachmentRow1 #Text.Text", "Eyes: Red");
        assertCommands(expected, commands);
        CommandTargetHudBinder.bind(commands, model(loadedNeedsStatus("Cat"), List.of()), "en-US");
        expected = new UICommandBuilder();
        expected.set("#AttachmentRow0.Visible", false);
        expected.set("#AttachmentRow1.Visible", false);
        expected.set("#AppearanceDivider.Visible", false);
        expected.set("#RoleSubtitle.Visible", false);
        assertCommands(expected, commands);
    }

    @Test
    void optionalSectionsCollapseAndFoodPrecedesAppearanceAndOwner() {
        var food = new CommandTargetHudViewModel.FoodRow("Food_Corn", "Corn", null, 5.0);
        var detailed = new CommandTargetHudViewModel(loadedNeedsStatus("Duck"), food, List.of(food),
                List.of(new CommandTargetHudViewModel.AttachmentRow("Coat", "Brown")),
                new CommandTargetHudViewModel.TameRequirementRow(true, 4, "2 (42s)"), "Alec");
        var layout = CommandTargetHudBinder.resolveLayout(detailed);
        Assertions.assertTrue(layout.foodTameVisible());
        Assertions.assertTrue(layout.ownerVisible());
        Assertions.assertTrue(layout.foodTameTop() + layout.foodTameHeight() <= layout.firstAttachmentTop());
        Assertions.assertTrue(layout.firstAttachmentTop() < layout.ownerTop());
        Assertions.assertTrue(layout.foodTameTop() + layout.foodTameHeight() <= layout.ownerTop());
        Assertions.assertTrue(layout.ownerTop() < layout.rootHeight());
        var minimal = CommandTargetHudBinder.resolveLayout(model(unloadedStatus("Duck"), List.of()));
        Assertions.assertFalse(minimal.foodTameVisible());
        Assertions.assertFalse(minimal.ownerVisible());
        Assertions.assertTrue(minimal.rootHeight() < layout.rootHeight());
    }

    private static CommandTargetHudViewModel model(LinkedNpcEntry status,
            List<CommandTargetHudViewModel.AttachmentRow> attachments) {
        return new CommandTargetHudViewModel(status, null, List.of(), attachments, null, null);
    }

    private static UICommandBuilder bind(CommandTargetHudViewModel model) {
        UICommandBuilder commands = new UICommandBuilder();
        CommandTargetHudBinder.bind(commands, model, "en-US");
        return commands;
    }

    private static void assertCommands(UICommandBuilder expected, UICommandBuilder actual) {
        for (var command : expected.getCommands()) {
            Assertions.assertEquals(command.data, data(actual, command.selector), command.selector);
        }
    }

    private static String data(UICommandBuilder commands, String selector) {
        String result = null;
        for (var command : commands.getCommands()) {
            if (selector.equals(command.selector)) result = command.data;
        }
        Assertions.assertNotNull(result, selector);
        return result;
    }

    private static LinkedNpcEntry cooldownStatus(boolean active) {
        return new LinkedNpcEntry(UUID.randomUUID(), "Sheep", 25, 25, 0, 0, 80,
                null, 0, 0, 0, 0, true, false, false, false, false, false, 0L,
                null, null, null, LinkedNpcTraitIndicator.EMPTY,
                false, false, false, false, true, true,
                null, null, null, null, null, true, active, active ? 60_000L : 0L,
                active ? 0.5 : 1.0, true, false, 0L, 1.0, true);
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
