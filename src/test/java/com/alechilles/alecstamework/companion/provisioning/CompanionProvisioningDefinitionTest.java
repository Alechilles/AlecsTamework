package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipDraft;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocationKind;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupAssignment;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupMembership;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupPolicy;
import com.alechilles.alecstamework.companion.population.group.PopulationGroupScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact request and durable payload tests for dormant provisioning. */
class CompanionProvisioningDefinitionTest {
    private static final ProvisioningOrigin ORIGIN =
            new ProvisioningOrigin("test:provisioning", "profile");
    private static final OwnerId OWNER =
            OwnerId.parse("30000000-0000-0000-0000-000000000095");
    private static final long NOW = -5_000;

    @Test
    void operationPayloadRoundTripsAllExactCanonicalEvidence() {
        CompanionProvisioningRequest request = request();

        assertEquals(
                request,
                CompanionProvisioningDefinition.INSTANCE.decode(
                        CompanionProvisioningDefinition.INSTANCE.encode(
                                request
                        )
                )
        );
        assertEquals(
                "companion_provisioning",
                CompanionProvisioningDefinition.KIND.value()
        );
    }

    @Test
    void mismatchedProfileOrPolicySnapshotIsRejected() {
        CompanionProvisioningRequest valid = request();
        CompanionIdentity wrong = new CompanionIdentity(
                new ProvisioningOrigin("test:other", "profile")
                        .profileId(),
                "Provisioned",
                "Mini",
                null,
                null,
                "world-a",
                NOW,
                NOW,
                NOW,
                0
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionProvisioningRequest(
                        valid.origin(),
                        valid.correlationId(),
                        wrong,
                        valid.lifecycle(),
                        valid.groupAssignment(),
                        valid.groupPolicies(),
                        valid.globalOwnerLimit(),
                        valid.perWorldOwnerLimit(),
                        valid.commandMembership(),
                        valid.expectedCommandRosterRevision(),
                        valid.requestedAtMs()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionProvisioningRequest(
                        valid.origin(),
                        valid.correlationId(),
                        valid.identity(),
                        valid.lifecycle(),
                        valid.groupAssignment(),
                        List.of(),
                        valid.globalOwnerLimit(),
                        valid.perWorldOwnerLimit(),
                        valid.commandMembership(),
                        valid.expectedCommandRosterRevision(),
                        valid.requestedAtMs()
                )
        );
    }

    private CompanionProvisioningRequest request() {
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                ORIGIN.profileId(),
                OWNER,
                LifecycleState.PROVISIONED_DORMANT,
                LifecycleLocation.keyed(
                        LifecycleLocationKind.PROVISIONING,
                        ORIGIN.stableKey()
                ),
                LifecycleRevision.INITIAL,
                null,
                NOW,
                ReconciliationGeneration.INITIAL,
                null,
                "world-a"
        );
        return new CompanionProvisioningRequest(
                ORIGIN,
                new UUID(0, 95),
                new CompanionIdentity(
                        ORIGIN.profileId(),
                        "Provisioned",
                        "Mini",
                        null,
                        null,
                        "world-a",
                        NOW,
                        NOW,
                        NOW,
                        0
                ),
                lifecycle,
                new PopulationGroupAssignment(
                        ORIGIN.profileId(),
                        "Mini",
                        List.of(new PopulationGroupMembership(
                                "mod:mini",
                                PopulationGroupScope.GLOBAL
                        )),
                        7,
                        0,
                        LifecycleRevision.INITIAL,
                        1,
                        NOW
                ),
                List.of(new PopulationGroupPolicy(
                        "mod:mini",
                        PopulationGroupScope.GLOBAL,
                        5,
                        2,
                        7
                )),
                10,
                4,
                new CommandRosterMembershipDraft(
                        ORIGIN.commandSlotId(),
                        new CommandFamilyKey(OWNER, "summon"),
                        ORIGIN.profileId(),
                        "companions",
                        true,
                        null,
                        NOW
                ),
                0L,
                NOW
        );
    }
}
