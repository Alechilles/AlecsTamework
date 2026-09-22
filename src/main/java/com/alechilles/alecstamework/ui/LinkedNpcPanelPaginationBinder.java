package com.alechilles.alecstamework.ui;

import com.alechilles.alecstamework.localization.LocalizedText;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
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
        if (values == null) {
            commands.set("#TameworkLinkedPanelPagination.Visible", true);
            commands.set("#TameworkLinkedPanelPagePrevious.Text", text(language, "previous"));
            commands.set("#TameworkLinkedPanelPageNext.Text", text(language, "next"));
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
            commands.set("#TameworkLinkedPanelPagePrevious.Disabled", state.pageIndex() == 0);
            commands.set("#TameworkLinkedPanelPageNext.Disabled", state.pageIndex() + 1 >= state.pageCount());
        } else {
            values.set(commands, "#TameworkLinkedPanelPageRange.Text", range);
            values.set(commands, "#TameworkLinkedPanelPageStatus.Text", status);
            values.set(commands, "#TameworkLinkedPanelPagePrevious.Disabled", state.pageIndex() == 0);
            values.set(commands, "#TameworkLinkedPanelPageNext.Disabled", state.pageIndex() + 1 >= state.pageCount());
        }
    }

    private static String text(String language, String key) {
        return LocalizedText.resolve(language, "tamework.ui.linkedPanel.pagination." + key);
    }
}
