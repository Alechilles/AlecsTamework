package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

/** Navigation chrome for the same bounded window used by the source. */
final class LinkedNpcPanelPaginationBinder {
    static final String PREVIOUS = "__panel_previous_page__";
    static final String NEXT = "__panel_next_page__";

    static void bind(UICommandBuilder commands, UIEventBuilder events,
                     LinkedNpcPanelPageState state, String language,
                     LinkedNpcPanelRefreshValues values) {
        if (state == null) return;
        boolean hasMultiplePages = state.pageCount() > 1;
        boolean hasPreviousPage = state.pageIndex() > 0;
        boolean hasNextPage = state.pageIndex() + 1 < state.pageCount();
        width(commands, values, "#TameworkLinkedPanelPageNavigation", 124 + (hasPreviousPage ? 96 : 0) + (hasNextPage ? 96 : 0));
        width(commands, values, "#TameworkLinkedPanelPagePrevious", hasPreviousPage ? 96 : 0);
        width(commands, values, "#TameworkLinkedPanelPageNext", hasNextPage ? 96 : 0);
        if (values == null) {
            commands.set("#TameworkLinkedPanelPagination.Visible", true);
            commands.set("#TameworkLinkedPanelPagePrevious.Text", "‹ " + text(language, "previous"));
            commands.set("#TameworkLinkedPanelPageNext.Text", text(language, "next") + " ›");
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    "#TameworkLinkedPanelPagePrevious", EventData.of("CommandId", PREVIOUS), false);
            events.addEventBinding(CustomUIEventBindingType.Activating,
                    "#TameworkLinkedPanelPageNext", EventData.of("CommandId", NEXT), false);
        }
        int totalEntries = state.totalEntries();
        int rangeStart = totalEntries == 0 ? 0 : state.startIndex() + 1;
        String range = LocalizedText.format(language, "tamework.ui.linkedPanel.pagination.range",
                rangeStart, state.endIndex(), totalEntries);
        String status = LocalizedText.format(language, "tamework.ui.linkedPanel.pagination.status",
                state.pageIndex() + 1, state.pageCount());
        if (values == null) {
            commands.set("#TameworkLinkedPanelPageRange.Text", range);
            commands.set("#TameworkLinkedPanelPageStatus.Text", status);
            commands.set("#TameworkLinkedPanelPageNavigation.Visible", hasMultiplePages);
            commands.set("#TameworkLinkedPanelPagePrevious.Visible", hasPreviousPage);
            commands.set("#TameworkLinkedPanelPageStatus.Visible", hasMultiplePages);
            commands.set("#TameworkLinkedPanelPageNext.Visible", hasNextPage);
            commands.set("#TameworkLinkedPanelPagePrevious.Disabled", !hasPreviousPage);
            commands.set("#TameworkLinkedPanelPageNext.Disabled", !hasNextPage);
        } else {
            values.set(commands, "#TameworkLinkedPanelPageRange.Text", range);
            values.set(commands, "#TameworkLinkedPanelPageStatus.Text", status);
            values.set(commands, "#TameworkLinkedPanelPageNavigation.Visible", hasMultiplePages);
            values.set(commands, "#TameworkLinkedPanelPagePrevious.Visible", hasPreviousPage);
            values.set(commands, "#TameworkLinkedPanelPageStatus.Visible", hasMultiplePages);
            values.set(commands, "#TameworkLinkedPanelPageNext.Visible", hasNextPage);
            values.set(commands, "#TameworkLinkedPanelPagePrevious.Disabled", !hasPreviousPage);
            values.set(commands, "#TameworkLinkedPanelPageNext.Disabled", !hasNextPage);
        }
    }

    private static String text(String language, String key) {
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.pagination." + key);
    }

    private static void width(UICommandBuilder commands, LinkedNpcPanelRefreshValues values,
                              String selector, int width) {
        if (values != null && !values.changed(selector + ".width", width)) return;
        Anchor anchor = new Anchor();
        anchor.setWidth(Value.of(width));
        anchor.setHeight(Value.of(28));
        commands.setObject(selector + ".Anchor", anchor);
    }
}
