package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.companion.bonded
        .BondedCompanionTransitionService;
import java.util.List;

/** Derives capacity evidence for one exact roster and policy family. */
final class BondedCompanionFamilyScope {
    private BondedCompanionFamilyScope() {
    }

    static BondedCompanionTransitionService.RosterCounts counts(
            List<BondedCompanionRecord.Profile> profiles,
            String familyId
    ) {
        int owned = 0;
        int active = 0;
        for (BondedCompanionRecord.Profile profile : profiles) {
            if (!familyId.equals(profile.familyId())) {
                continue;
            }
            owned++;
            if (profile.state() == BondedCompanionState.ACTIVE) {
                active++;
            }
        }
        return new BondedCompanionTransitionService.RosterCounts(owned, active);
    }

    static boolean hasActiveCapacity(int active, int configuredLimit) {
        return configuredLimit == 0 || active < configuredLimit;
    }
}
