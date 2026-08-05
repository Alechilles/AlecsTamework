package com.alechilles.alecstamework.companion.bonded.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpiryDismountRiderUuidResolverTest {
    @Test
    void resolves_the_avatar_flight_rider_when_regular_mount_links_are_absent() {
        UUID rider = UUID.fromString("c22f9878-9173-4a12-8a38-3b9d1ee7b420");

        assertEquals(rider, ExpiryDismountRiderUuidResolver.resolve(
                null, null, rider.toString()));
    }
}
