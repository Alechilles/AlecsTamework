package com.alechilles.alecstamework.items;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for restart and runtime stale-source suppression mappings. */
class CommandRecoveredSourceSuppressionIndexTest {

    @Test
    void persistedAndNewlyFinalizedSourcesResolveWithoutTreatingOtherAliasesAsStale() {
        UUID persistedSource = UUID.randomUUID();
        UUID persistedReplacement = UUID.randomUUID();
        UUID runtimeSource = UUID.randomUUID();
        UUID runtimeReplacement = UUID.randomUUID();
        UUID unrelatedAlias = UUID.randomUUID();
        CommandRecoveredSourceSuppressionIndex index =
                new CommandRecoveredSourceSuppressionIndex(
                        Map.of(persistedSource, persistedReplacement)
                );

        index.record(runtimeSource, runtimeReplacement);

        assertEquals(persistedReplacement, index.replacementFor(persistedSource));
        assertEquals(runtimeReplacement, index.replacementFor(runtimeSource));
        assertNull(index.replacementFor(unrelatedAlias));
    }
}
