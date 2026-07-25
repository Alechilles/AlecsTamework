package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for the sole live-world evidence seam used by API authors. */
class ReplacementFeatureLiveEvidenceSourceTest {
    private static final UUID OWNER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void rosterEvidenceNormalizesAssetIdentifiers() {
        var evidence = new ReplacementFeatureLiveEvidenceSource.RosterAccess(
                OWNER,
                " family ",
                " config ",
                " item ",
                new CommandRosterSlotId(
                        UUID.fromString(
                                "20000000-0000-0000-0000-000000000001"
                        )
                ),
                -100
        );

        assertEquals("family", evidence.commandFamilyId());
        assertEquals("config", evidence.commandConfigId());
        assertEquals("item", evidence.accessItemId());
        assertEquals(-100, evidence.observedAtMs());
    }

    @Test
    void provisioningIntentRejectsPartialProjectionIdentity() {
        ProvisioningOrigin origin = new ProvisioningOrigin(
                "test", "partial-projection"
        );
        NpcAlias alias = new NpcAlias(
                UUID.fromString(
                        "30000000-0000-0000-0000-000000000001"
                )
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplacementFeatureLiveEvidenceSource
                        .ProvisioningWorldIntent(
                        origin,
                        OWNER,
                        "world",
                        "role",
                        null,
                        alias,
                        null
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReplacementFeatureLiveEvidenceSource
                        .ProvisioningWorldIntent(
                        origin,
                        OWNER,
                        "world",
                        "role",
                        null,
                        alias,
                        " "
                )
        );
    }

    @Test
    void paidInventoryEvidenceDefensivelyCopiesFrozenFacts() {
        List<ReplacementFeatureLiveEvidenceSource.PaidCostAvailability>
                costs = new ArrayList<>();
        costs.add(
                new ReplacementFeatureLiveEvidenceSource
                        .PaidCostAvailability(
                        "ingredient", 3, "Ingredient", "icon"
                )
        );
        var evidence =
                new ReplacementFeatureLiveEvidenceSource
                        .PaidInventoryEvidence(
                        OWNER, costs, List.of(), null, -50
                );

        costs.clear();

        assertEquals(1, evidence.costs().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> evidence.costs().clear()
        );
    }
}
