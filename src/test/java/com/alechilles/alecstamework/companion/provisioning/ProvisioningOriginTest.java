package com.alechilles.alecstamework.companion.provisioning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Deterministic identity tests for caller-owned provisioning origins. */
class ProvisioningOriginTest {
    @Test
    void sameOriginAlwaysProducesTheSameCanonicalIdentities() {
        ProvisioningOrigin first = new ProvisioningOrigin(
                "example:integration", "stable-companion"
        );
        ProvisioningOrigin replay = new ProvisioningOrigin(
                " example:integration ", " stable-companion "
        );

        assertEquals(first, replay);
        assertEquals(first.stableKey(), replay.stableKey());
        assertEquals(first.profileId(), replay.profileId());
        assertEquals(first.commandSlotId(), replay.commandSlotId());
        assertEquals(first.operationKey(), replay.operationKey());
    }

    @Test
    void namespaceAndLengthDelimitedKeyArePartOfIdentity() {
        ProvisioningOrigin namespaceA =
                new ProvisioningOrigin("mod:a", "companion");
        ProvisioningOrigin namespaceB =
                new ProvisioningOrigin("mod:b", "companion");
        ProvisioningOrigin splitA =
                new ProvisioningOrigin("a", "bc");
        ProvisioningOrigin splitB =
                new ProvisioningOrigin("ab", "c");

        assertNotEquals(namespaceA.profileId(), namespaceB.profileId());
        assertNotEquals(namespaceA.stableKey(), namespaceB.stableKey());
        assertNotEquals(splitA.profileId(), splitB.profileId());
        assertNotEquals(splitA.stableKey(), splitB.stableKey());
    }

    @Test
    void blankOrOversizedOriginsAreRejectedAtTheBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisioningOrigin(" ", "key")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProvisioningOrigin(
                        "namespace",
                        "k".repeat(ProvisioningOrigin.MAX_KEY_LENGTH + 1)
                )
        );
    }
}
