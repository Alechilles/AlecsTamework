package com.alechilles.alecstamework.ui;

import java.util.UUID;
import javax.annotation.Nullable;

/** Resolves the two-step permanent-delete confirmation for bonded cards. */
final class BondedCompanionUnlinkDecision {
    private BondedCompanionUnlinkDecision() {
    }

    static Decision resolve(
            String commandId,
            String unlinkPrefix,
            @Nullable UUID pendingNpcUuid,
            LinkedNpcPanelFeatureController features
    ) {
        if (!commandId.startsWith(unlinkPrefix)) return Decision.ignored();
        UUID npcUuid = CommandUiIdParser.parseNpcUuid(commandId, unlinkPrefix);
        CommandPanelFeaturePresentation feature = features.presentation(npcUuid);
        if (npcUuid == null || feature == null || feature.bonded() == null) {
            return Decision.ignored();
        }
        return new Decision(true, npcUuid, npcUuid.equals(pendingNpcUuid));
    }

    record Decision(boolean handled, @Nullable UUID npcUuid, boolean confirmed) {
        static Decision ignored() {
            return new Decision(false, null, false);
        }
    }
}
