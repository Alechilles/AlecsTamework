package com.alechilles.alecstamework.ui;

import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.*;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import javax.annotation.Nonnull;

/**
 * Rejects stale or forged legacy linked-record events on a bonded roster page.
 */
final class CommandSelectionRosterEventBoundary {
    private final boolean bondedRoster;

    CommandSelectionRosterEventBoundary(TwCommandItemConfig config) {
        this.bondedRoster = config != null
                && config.usesBondedCompanionRoster();
    }

    boolean bondedRoster() {
        return bondedRoster;
    }

    boolean blocks(@Nonnull CommandSelectionEventData data,
                   @Nonnull String commandId) {
        if (!bondedRoster) return false;
        if (data.panelAutoLinkEnabled != null
                || data.panelGroupActiveValue != null
                || data.panelGroupAssignValue != null) {
            return true;
        }
        if (PANEL_MANAGE_GROUPS_COMMAND_ID.equals(commandId)
                || PANEL_GROUP_ASSIGN_APPLY_COMMAND_ID.equals(commandId)
                || PANEL_GROUP_ASSIGN_CANCEL_COMMAND_ID.equals(commandId)) {
            return true;
        }
        return commandId.startsWith(LINK_COMMAND_PREFIX)
                || commandId.startsWith(UNLINK_COMMAND_PREFIX)
                || commandId.startsWith(OPEN_GROUP_PICKER_COMMAND_PREFIX)
                || commandId.startsWith(TOGGLE_ACTIVE_COMMAND_PREFIX)
                || commandId.startsWith(TOGGLE_BREEDING_COMMAND_PREFIX)
                || commandId.startsWith(RELEASE_COMMAND_PREFIX)
                || commandId.startsWith(CULL_COMMAND_PREFIX)
                || commandId.startsWith(RESPAWN_COMMAND_PREFIX)
                || commandId.startsWith(LOCATE_COMMAND_PREFIX)
                || commandId.startsWith(RECALL_COMMAND_PREFIX)
                || commandId.startsWith(SET_HOME_COMMAND_PREFIX)
                || commandId.startsWith(RETURN_HOME_COMMAND_PREFIX)
                || commandId.startsWith(OPEN_TALENTS_COMMAND_PREFIX);
    }
}
