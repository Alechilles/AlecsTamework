package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiPanelState;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies optional contributor values reach the existing standard card slots. */
class CommandUiDefaultDecorationBinderTest {
    @Test
    void rendersReadyCapacityAndPortraitStarsAndHidesInvalidRows() {
        UUID rowId = UUID.randomUUID();
        CommandUiContribution contribution = new CommandUiContribution(
                CommandUiContributorId.of("example:husbandry"),
                Map.of("capacity.text", CommandUiValue.of("Animals: 3 / 10"),
                        "capacity.tooltip", CommandUiValue.of("Your current limit.")),
                Map.of(rowId, Map.of("portrait.stars", CommandUiValue.of(3L),
                        "portrait.stars.tooltip", CommandUiValue.of("Excellent traits."))));
        CommandUiSnapshot snapshot = snapshot().withContributions(Map.of(
                contribution.contributorId(), contribution));
        CommandUiDefaultDecorationBinder.State state =
                CommandUiDefaultDecorationBinder.from(snapshot);
        UICommandBuilder commands = new UICommandBuilder();

        CommandUiDefaultDecorationBinder.bindHeader(commands, state);
        CommandUiDefaultDecorationBinder.bindCard(commands, "#Card", rowId, state);
        CommandUiDefaultDecorationBinder.bindCard(commands, "#CardInvalid",
                UUID.randomUUID(), state);

        assertEquals(encoded("Animals: 3 / 10"), data(commands,
                "#TameworkContributorCapacity.Text"));
        assertEquals(encoded("Your current limit."), data(commands,
                "#TameworkContributorCapacity.TooltipText"));
        assertEquals(encoded("★★★☆☆"), data(commands,
                "#Card #ContributorPortraitStars.Text"));
        assertEquals(encoded("Excellent traits."), data(commands,
                "#Card #ContributorPortraitStarsTooltip.TooltipText"));
        assertEquals(encoded(false), data(commands,
                "#CardInvalid #ContributorPortraitStars.Visible"));
    }

    @Test
    void ignoresUnavailableContributorsAndOutOfRangeStarValues() {
        UUID rowId = UUID.randomUUID();
        CommandUiContribution unavailable = new CommandUiContribution(
                CommandUiContributorId.of("example:unavailable"),
                Map.of("capacity.text", CommandUiValue.of("Do not render")), Map.of())
                .withStatus(CommandUiContribution.Status.OPTIONAL_UNAVAILABLE, "offline");
        CommandUiContribution invalid = new CommandUiContribution(
                CommandUiContributorId.of("example:invalid"), Map.of(),
                Map.of(rowId, Map.of("portrait.stars", CommandUiValue.of(6L))));
        CommandUiDefaultDecorationBinder.State state = CommandUiDefaultDecorationBinder.from(
                snapshot().withContributions(Map.of(unavailable.contributorId(), unavailable,
                        invalid.contributorId(), invalid)));
        UICommandBuilder commands = new UICommandBuilder();

        CommandUiDefaultDecorationBinder.bindHeader(commands, state);
        CommandUiDefaultDecorationBinder.bindCard(commands, "#Card", rowId, state);

        assertEquals(encoded(false), data(commands, "#TameworkContributorCapacity.Visible"));
        assertEquals(encoded(false), data(commands, "#Card #ContributorPortraitStars.Visible"));
    }

    private static CommandUiSnapshot snapshot() {
        return new CommandUiSnapshot(UUID.randomUUID(), 1L, 1L, null,
                List.of(), List.of(), new CommandUiPanelState("linked"));
    }

    private static Object data(UICommandBuilder commands, String selector) {
        return java.util.Arrays.stream(commands.getCommands())
                .filter(command -> selector.equals(command.selector)).reduce(
                        (ignored, latest) -> latest).orElseThrow().data;
    }

    private static Object encoded(Object value) {
        UICommandBuilder expected = new UICommandBuilder();
        if (value instanceof String text) {
            expected.set("#Expected", text);
        } else if (value instanceof Boolean visible) {
            expected.set("#Expected", visible);
        } else {
            throw new IllegalArgumentException("Unsupported expected UI value: " + value);
        }
        return expected.getCommands()[0].data;
    }
}
