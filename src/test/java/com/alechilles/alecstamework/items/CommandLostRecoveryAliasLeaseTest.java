package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.CompanionIdentityResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLostRecoveryAliasLeaseTest {
    @Test
    void acquiredLeaseMakesPlannedRecoveryAliasVisibleUntilSpawnFailureReleasesIt() {
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        UUID planned = UUID.randomUUID();
        CommandLostRecoveryAliasLease lease =
                new CommandLostRecoveryAliasLease(identities, "profile-a", planned);

        assertTrue(lease.acquire());
        assertEquals("profile-a", identities.resolveProfileId(planned).orElseThrow());
        assertTrue(lease.releaseBeforeVisibility());
        assertTrue(identities.resolveProfileId(planned).isEmpty());
    }

    @Test
    void leaseFailsClosedWhenPlannedUuidBelongsToAnotherProfile() {
        CompanionIdentityResolver identities = new CompanionIdentityResolver();
        UUID planned = UUID.randomUUID();
        identities.markDurable("profile-b", planned);

        CommandLostRecoveryAliasLease lease =
                new CommandLostRecoveryAliasLease(identities, "profile-a", planned);

        assertFalse(lease.acquire());
        assertEquals("profile-b", identities.resolveProfileId(planned).orElseThrow());
    }
}
