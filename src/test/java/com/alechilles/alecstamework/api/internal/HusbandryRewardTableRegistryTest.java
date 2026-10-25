package com.alechilles.alecstamework.api.internal;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A failing reward table provider must fall back to the configured drop list, not to nothing. */
class HusbandryRewardTableRegistryTest {
    @Test
    void aProviderThatThrowsKeepsTheConfiguredDropList() {
        try (HusbandryRewardTableRegistry registry = new HusbandryRewardTableRegistry()) {
            registry.register(context -> {
                throw new IllegalStateException("provider failure");
            });

            assertNull(registry.resolve(new HusbandryOutcomeContext(HusbandryOutcomeKind.CULL_YIELD,
                    UUID.randomUUID(), UUID.randomUUID(), "Cow", "test:profile", Set.of("test:family"), null)));
        }
    }
}
