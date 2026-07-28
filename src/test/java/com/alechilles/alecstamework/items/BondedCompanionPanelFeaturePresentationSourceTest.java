package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedCompanionActionBlockReason;
import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.api.BondedCompanionPlacement;
import com.alechilles.alecstamework.api.BondedCompanionProfileView;
import com.alechilles.alecstamework.api.BondedCompanionStateView;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies panel actions preserve precise policy failures for tooltip feedback. */
class BondedCompanionPanelFeaturePresentationSourceTest {
    @Test
    void storedCompanionAtItsActiveLimitReportsCapacityInsteadOfGenericPolicy() {
        BondedCompanionProfileView profile = new BondedCompanionProfileView(
                "profile-7", UUID.randomUUID(), "hydragon:dragon_horn",
                "hydragon:full_dragons", "Tamed_NordicDrake", "Naomi",
                "Nordic Drake", "Female", 1L, BondedCompanionStateView.STORED,
                false, false, false,
                Map.of(
                        "bonded.activeCapacity.count", "1",
                        "bonded.activeCapacity.limit", "1",
                        "bonded.activeCapacity.label", "Full Dragons"
                ),
                null, 0L, null
        );
        BondedCompanionActionContext context = new BondedCompanionActionContext(
                new BondedCompanionPlacement("world", 0D, 0D, 0D,
                        0F, 0F, 0F), null);

        var row = BondedCompanionPanelFeaturePresentationSource.presentation(
                profile, 0L, null, context, "world");

        assertEquals(BondedCompanionActionBlockReason.CAPACITY_REACHED,
                row.status().blockReason());
    }
}
