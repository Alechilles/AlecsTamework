package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CommandTimedSummonPolicySnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SpawnerTameLinkDurableContextTest {
    @Test
    void roundTripsLeasePolicyInsideExistingSourceEvidence() {
        SpawnerTameLinkDurableContext.Evidence expected =
                new SpawnerTameLinkDurableContext.Evidence(
                        "hydragon", "dragon-command", "Flightmasters_Talisman",
                        "Mini_Wyvern", "Mini_Wyvern", 7L,
                        new CommandTimedSummonPolicySnapshot(
                                60_000L, 5_000L, true, new long[]{30_000L, 10_000L}));

        String merged = SpawnerTameLinkDurableContext.merge(
                "{\"version\":1,\"world\":\"test\",\"inventory\":\"hotbar\","
                        + "\"slot\":2,\"fingerprint\":\"abc\"}", expected);
        SpawnerTameLinkDurableContext.Evidence actual =
                SpawnerTameLinkDurableContext.parse(merged);

        assertNotNull(actual);
        assertEquals(expected.commandFamilyId(), actual.commandFamilyId());
        assertEquals(expected.requiredCommandConfigId(), actual.requiredCommandConfigId());
        assertEquals(expected.accessItemId(), actual.accessItemId());
        assertEquals(expected.targetRoleId(), actual.targetRoleId());
        assertEquals(expected.timedConfigId(), actual.timedConfigId());
        assertEquals(expected.timedConfigRevision(), actual.timedConfigRevision());
        assertEquals(expected.policy().activeDurationMs(), actual.policy().activeDurationMs());
        assertEquals(expected.policy().resummonCooldownMs(), actual.policy().resummonCooldownMs());
        assertEquals(expected.policy().autoStoreOnOwnerLogout(),
                actual.policy().autoStoreOnOwnerLogout());
        assertArrayEquals(expected.policy().expiryWarningThresholdsMs(),
                actual.policy().expiryWarningThresholdsMs());
    }
}
