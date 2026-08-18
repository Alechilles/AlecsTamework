package com.alechilles.alecstamework.runtime.activation;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Behavior tests for the pre-start downstream capability request seam. */
class TameworkRuntimeCapabilityRequestsTest {
    @Test
    void setupRequestsAreFrozenAndReadableAfterPublication() {
        TameworkRuntimeCapabilityRequests requests = new TameworkRuntimeCapabilityRequests();
        requests.request(TameworkRuntimeModule.FOOD, "RUNE_PROFESSIONS_FEED_V1");
        requests.request(TameworkRuntimeModule.FOOD, "RUNE_PROFESSIONS_FEED_V1");

        Map<TameworkRuntimeModule, java.util.Set<String>> snapshot = requests.publish();

        assertEquals(
                java.util.Set.of("RUNE_PROFESSIONS_FEED_V1"),
                snapshot.get(TameworkRuntimeModule.FOOD)
        );
        assertEquals(snapshot, requests.snapshot());
        assertThrows(
                IllegalStateException.class,
                () -> requests.request(TameworkRuntimeModule.FOOD, "late")
        );
    }
}
