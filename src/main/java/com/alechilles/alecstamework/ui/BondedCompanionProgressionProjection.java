package com.alechilles.alecstamework.ui;

import java.util.HashMap;
import java.util.Map;

/**
 * Produces a bonded-card presentation that only advances progression fields
 * when the refresh permit is eligible to do so.
 */
final class BondedCompanionProgressionProjection {
    private static final String[] PROGRESSION_ATTRIBUTE_KEYS = {
            "level",
            "currentXp",
            "levelingConfigId",
            "talentConfigId",
            "talentSpentPoints"
    };

    private BondedCompanionProgressionProjection() {
    }

    /**
     * Keeps the last rendered progression attributes for an ineligible permit
     * while preserving the current card's live state and action presentation.
     */
    static CommandPanelFeaturePresentation project(
            CommandPanelFeaturePresentation previous,
            CommandPanelFeaturePresentation current,
            boolean progressionEligible
    ) {
        if (progressionEligible || !hasBondedPresentation(previous)
                || !hasBondedPresentation(current)) {
            return current;
        }

        BondedCompanionPanelPresentation old = previous.bonded();
        BondedCompanionPanelPresentation now = current.bonded();
        Map<String, String> attributes = new HashMap<>(now.attributes());
        for (String key : PROGRESSION_ATTRIBUTE_KEYS) {
            if (old.attributes().containsKey(key)) {
                attributes.put(key, old.attributes().get(key));
            } else {
                attributes.remove(key);
            }
        }

        return CommandPanelFeaturePresentation.bonded(new BondedCompanionPanelPresentation(
                now.profileId(), now.rosterId(), now.roleId(), now.revision(),
                now.displayName(), now.species(), now.gender(), now.rolePresentation(),
                attributes, now.extensions(), now.status(), now.reviveQuote()));
    }

    private static boolean hasBondedPresentation(
            CommandPanelFeaturePresentation presentation
    ) {
        return presentation != null && presentation.bonded() != null;
    }
}
