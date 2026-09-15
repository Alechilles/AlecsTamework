package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.api.commandui.CommandUiContribution;
import com.alechilles.alecstamework.api.commandui.CommandUiSnapshot;
import com.alechilles.alecstamework.api.commandui.CommandUiValue;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Renders documented optional capacity and portrait decorations in the standard card UI. */
final class CommandUiDefaultDecorationBinder {
    private static final String CAPACITY_TEXT = "capacity.text";
    private static final String CAPACITY_TOOLTIP = "capacity.tooltip";
    private static final String PORTRAIT_STARS = "portrait.stars";

    private CommandUiDefaultDecorationBinder() {
    }

    @Nonnull
    static State from(@Nonnull CommandUiSnapshot snapshot) {
        String capacity = "";
        String capacityTooltip = "";
        LinkedHashMap<UUID, Map<String, CommandUiValue>> rows = new LinkedHashMap<>();
        // Snapshot maps are immutable but do not promise iteration order. Contributor IDs make
        // simultaneous optional decorations deterministic without reserving a pack namespace.
        for (Map.Entry<com.alechilles.alecstamework.api.commandui.CommandUiContributorId,
                CommandUiContribution> entry : snapshot.contributions().entrySet().stream()
                .sorted(Comparator.comparing(value -> value.getKey().value()))
                .toList()) {
            CommandUiContribution contribution = entry.getValue();
            if (contribution.status() != CommandUiContribution.Status.READY) continue;
            String text = string(contribution.pageValue(CAPACITY_TEXT));
            if (capacity.isBlank() && text != null && !text.isBlank()) {
                capacity = text;
                String tooltip = string(contribution.pageValue(CAPACITY_TOOLTIP));
                capacityTooltip = tooltip == null ? "" : tooltip;
            }
            contribution.rowData().forEach((rowId, values) -> {
                if (validStars(values)) rows.putIfAbsent(rowId, values);
            });
        }
        return rows.isEmpty() && capacity.isBlank() ? State.EMPTY
                : new State(capacity, capacityTooltip, rows);
    }

    static void bindHeader(@Nonnull UICommandBuilder commands, @Nonnull State state) {
        boolean visible = !state.capacityText().isBlank();
        commands.set("#TameworkContributorCapacity.Visible", visible);
        commands.set("#TameworkContributorCapacity.Text", state.capacityText());
        commands.set("#TameworkContributorCapacity.TooltipText", state.capacityTooltip());
    }

    static void bindCard(@Nonnull UICommandBuilder commands, @Nonnull String selector,
                         @Nullable UUID rowId, @Nonnull State state) {
        StarDecoration stars = state.stars(rowId);
        boolean visible = stars != null;
        commands.set(selector + " #ContributorPortraitStars.Visible", visible);
        if (!visible) return;
        for (int index = 1; index <= 5; index++) {
            commands.set(selector + " #ContributorStar" + index + ".Visible", index <= stars.count());
        }
    }

    @Nullable
    private static String string(@Nullable CommandUiValue value) {
        return value == null || value.type() != CommandUiValue.Type.STRING
                ? null : value.stringValue().trim();
    }

    private static boolean validStars(@Nullable Map<String, CommandUiValue> values) {
        if (values == null) return false;
        CommandUiValue count = values.get(PORTRAIT_STARS);
        return count != null && count.type() == CommandUiValue.Type.LONG
                && count.longValue() >= 1L && count.longValue() <= 5L;
    }

    record State(@Nullable String capacityText, @Nullable String capacityTooltip,
                 @Nonnull Map<UUID, Map<String, CommandUiValue>> rows) {
        static final State EMPTY = new State("", "", Map.of());

        State {
            capacityText = capacityText == null ? "" : capacityText;
            capacityTooltip = capacityTooltip == null ? "" : capacityTooltip;
            rows = rows == null || rows.isEmpty() ? Map.of() : Map.copyOf(rows);
        }

        @Nullable
        StarDecoration stars(@Nullable UUID rowId) {
            if (rowId == null) return null;
            Map<String, CommandUiValue> values = rows.get(rowId);
            if (values == null) return null;
            CommandUiValue count = values.get(PORTRAIT_STARS);
            if (count == null || count.type() != CommandUiValue.Type.LONG) return null;
            long raw = count.longValue();
            if (raw < 1 || raw > 5) return null;
            return new StarDecoration((int) raw);
        }
    }

    private record StarDecoration(int count) {
    }
}
