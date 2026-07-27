package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.api.BondedCompanionProvisionRequest;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionSnapshotPresentationMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Guards newly provisioned bonded companions from losing their full-health panel data. */
class BondedCompanionProvisionedHealthTest {

    @Test
    void miniwyvernProvisionStartsAtConfiguredFullHealthInPanelData() {
        var prepared = new BondedCompanionProvisioningSupport(roleId -> {
            assertEquals("Tamed_Wyvern_Mini", roleId);
            return 80.0D;
        }).prepare(
                new BondedCompanionProvisionRequest(
                        "test", "mini-health", UUID.randomUUID(),
                        "hydragon:horn", "Tamed_Wyvern_Mini",
                        "Ember", "Miniwyvern", null, Map.of(),
                        "hydragon:miniwyvern"),
                10L
        );

        var panel = new BondedCompanionSnapshotPresentationMapper(
                ignored -> new BondedCompanionSnapshotPresentationMapper
                        .RolePresentation(null, null, null, Map.of()))
                .map(prepared.snapshot());

        assertEquals("80.0", panel.data().get("currentHealth"));
        assertEquals("80.0", panel.data().get("maxHealth"));
        assertEquals("100.0", panel.data().get("healthPercent"));
    }
}
