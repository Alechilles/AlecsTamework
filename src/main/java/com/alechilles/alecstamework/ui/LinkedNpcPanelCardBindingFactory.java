package com.alechilles.alecstamework.ui;

import static com.alechilles.alecstamework.ui.CommandSelectionPageEventBinder.*;

/** Creates the immutable card binding contract shared by a command page's cards. */
final class LinkedNpcPanelCardBindingFactory {
    private LinkedNpcPanelCardBindingFactory() { }

    static LinkedNpcPanelCardBinder.CardBindingConfig create(boolean recall, boolean ownerRoster) {
        return new LinkedNpcPanelCardBinder.CardBindingConfig(
                TameworkCommandSelectionPage.LINKED_PANEL_CARD_UI_PATH, EVENT_COMMAND_ID,
                LINK_COMMAND_PREFIX, UNLINK_COMMAND_PREFIX, OPEN_GROUP_PICKER_COMMAND_PREFIX,
                TOGGLE_ACTIVE_COMMAND_PREFIX, TOGGLE_BREEDING_COMMAND_PREFIX, RELEASE_COMMAND_PREFIX,
                CULL_COMMAND_PREFIX, RESPAWN_COMMAND_PREFIX, LinkedNpcPanelFeatureController.SUMMON_COMMAND_PREFIX,
                LinkedNpcPanelFeatureController.DISMISS_COMMAND_PREFIX, LOCATE_COMMAND_PREFIX,
                RECALL_COMMAND_PREFIX, SET_HOME_COMMAND_PREFIX, RETURN_HOME_COMMAND_PREFIX,
                OPEN_TALENTS_COMMAND_PREFIX, recall, ownerRoster);
    }
}
