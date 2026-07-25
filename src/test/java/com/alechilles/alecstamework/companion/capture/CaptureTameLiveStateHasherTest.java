package com.alechilles.alecstamework.companion.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaptureTameLiveStateHasherTest {
    private static final OwnerId OWNER = OwnerId.parse(
            "30000000-0000-0000-0000-000000000101"
    );

    @Test
    void commandLinksAreCanonicalizedAsASet() {
        var first = state(List.of("roster:b", "roster:a"));
        var second = state(List.of("roster:a", "roster:b", "roster:a"));

        assertEquals(
                CaptureTameLiveStateHasher.hash(first),
                CaptureTameLiveStateHasher.hash(second)
        );
    }

    @Test
    void everyAuthoritativeFieldContributesToTheDigest() {
        var expected = state(List.of("roster:a"));
        var changed = new CaptureTameLiveStateHasher.State(
                expected.roleId(),
                expected.ownerPresent(),
                expected.ownerId(),
                expected.ownerName(),
                expected.tamedPresent(),
                expected.tamed(),
                expected.commandLinksPresent(),
                expected.commandOwnerId(),
                expected.commandLinkIds(),
                expected.commandHomePresent(),
                expected.homeX(),
                expected.homeY(),
                expected.homeZ(),
                expected.spawnConfigurationIndex(),
                expected.environmentIndex(),
                true,
                expected.spawnBeaconPresent()
        );

        assertNotEquals(
                CaptureTameLiveStateHasher.hash(expected),
                CaptureTameLiveStateHasher.hash(changed)
        );
    }

    private CaptureTameLiveStateHasher.State state(List<String> links) {
        return new CaptureTameLiveStateHasher.State(
                "Tamed_Dragon_Fire",
                true,
                OWNER,
                "Alec",
                true,
                true,
                true,
                OWNER,
                links,
                false,
                0.0D,
                0.0D,
                0.0D,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                false,
                false
        );
    }
}
