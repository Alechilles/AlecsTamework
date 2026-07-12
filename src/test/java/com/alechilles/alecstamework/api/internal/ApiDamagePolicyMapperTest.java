package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ClaimAccessDecisionView;
import com.alechilles.alecstamework.api.DamagePolicyDecisionView;
import com.alechilles.alecstamework.api.OwnershipPolicyView;
import com.alechilles.alecstamework.api.Vector3View;
import com.alechilles.alecstamework.damage.TamedDamageDecision;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiDamagePolicyMapperTest {
    private static final OwnershipPolicyView OWNERSHIP = new OwnershipPolicyView(
            "profile",
            UUID.randomUUID(),
            UUID.randomUUID(),
            "owner",
            "Tamed_Test",
            true,
            false,
            null,
            null,
            true,
            false,
            false
    );

    @Test
    void publicApiMapsSharedRuntimeDecisionWithoutReevaluatingPolicy() {
        for (DecisionMapping mapping : decisionMappings()) {
            DamagePolicyDecisionView view = ApiDamagePolicyMapper.map(
                    OWNERSHIP,
                    UUID.randomUUID(),
                    mapping.decision(),
                    "world",
                    new Vector3View(1, 2, 3)
            );

            assertEquals(mapping.decision().allowed(), view.allowed());
            assertEquals(mapping.expectedStatus(), view.status());
            assertEquals(mapping.decision().reason(), view.reason());
            assertEquals(mapping.expectedClaimStatus(), view.claimAccess().status());
            assertEquals(mapping.decision().allowed(), view.claimAccess().allowed());
        }
    }

    @Test
    void ownerDenialStaysFirstAndHasNoClaimDecision() {
        TamedDamageDecision decision = new TamedDamageDecision(
                false,
                TamedDamageDecision.Status.DENY_OWNER,
                "block-owner-damage",
                false,
                null,
                null
        );

        DamagePolicyDecisionView view = ApiDamagePolicyMapper.map(
                OWNERSHIP,
                OWNERSHIP.ownerUuid(),
                decision,
                null,
                null
        );

        assertEquals(DamagePolicyDecisionView.Status.DENIED_OWNER_PROTECTION, view.status());
        assertNull(view.claimAccess());
    }

    private static List<DecisionMapping> decisionMappings() {
        UUID partyId = UUID.randomUUID();
        return List.of(
                new DecisionMapping(
                        new TamedDamageDecision(true, TamedDamageDecision.Status.ALLOW_SKIPPED,
                                "damage-protection-disabled", false, null, null),
                        DamagePolicyDecisionView.Status.ALLOWED_SKIPPED,
                        ClaimAccessDecisionView.Status.SKIPPED
                ),
                new DecisionMapping(
                        new TamedDamageDecision(true, TamedDamageDecision.Status.ALLOW_ENFORCED,
                                "native-policy-allowed", true, partyId, null),
                        DamagePolicyDecisionView.Status.ALLOWED,
                        ClaimAccessDecisionView.Status.ALLOWED
                ),
                new DecisionMapping(
                        new TamedDamageDecision(false, TamedDamageDecision.Status.DENY_CLAIM,
                                "claim-protection-denied", true, partyId, null),
                        DamagePolicyDecisionView.Status.DENIED_CLAIM_PROTECTION,
                        ClaimAccessDecisionView.Status.DENIED
                ),
                new DecisionMapping(
                        new TamedDamageDecision(true, TamedDamageDecision.Status.ALLOW_FAIL_OPEN,
                                "lookup-error", true, partyId, "boom"),
                        DamagePolicyDecisionView.Status.ALLOWED_FAIL_OPEN,
                        ClaimAccessDecisionView.Status.ALLOW_FAIL_OPEN
                ),
                new DecisionMapping(
                        new TamedDamageDecision(true, TamedDamageDecision.Status.UNAVAILABLE,
                                "live-target-required", false, null, null),
                        DamagePolicyDecisionView.Status.UNAVAILABLE,
                        ClaimAccessDecisionView.Status.UNAVAILABLE
                )
        );
    }

    private record DecisionMapping(TamedDamageDecision decision,
                                   DamagePolicyDecisionView.Status expectedStatus,
                                   ClaimAccessDecisionView.Status expectedClaimStatus) {
    }
}
