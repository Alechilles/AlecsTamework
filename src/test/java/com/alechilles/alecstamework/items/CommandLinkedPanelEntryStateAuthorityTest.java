package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Regression coverage for canonical lifecycle being the panel's only state authority. */
class CommandLinkedPanelEntryStateAuthorityTest {
    @Test
    void canonicalLifecycleResolvesBeforeLoadedProjectionWithoutLocalStatusCaches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java"
        ));

        int profile = source.indexOf("persistenceView.find(record)");
        int dead = source.indexOf("dead = canonicalProfile.dead();");
        int captured = source.indexOf("captured = canonicalProfile.captured();");
        int cooped = source.indexOf("inCoop = canonicalProfile.inCoop();");
        int lost = source.indexOf("lost = canonicalProfile.lost();");
        int loaded = source.indexOf("LinkedNpcEntry loadedEntry = buildLoadedEntry(");

        assertTrue(profile >= 0 && dead > profile);
        assertTrue(captured > dead);
        assertTrue(cooped > captured);
        assertTrue(lost > cooped);
        assertTrue(loaded > lost,
                "Canonical nonphysical lifecycle must win while a retiring live entity still exists.");
        assertTrue(source.contains("if (!dead && !captured && !inCoop && world != null)"));
        assertTrue(source.contains("liveTargetResolver.resolveRedirect(record)"),
                "A released projection must be retried through its canonical profile UUID.");
        assertTrue(source.contains("if (dead)"),
                "A durable DEAD_REVIVABLE roster row must still apply its role-configured respawn policy "
                        + "when the in-memory death snapshot is unavailable.");
        assertTrue(source.contains("TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId)"),
                "The durable-dead fallback must use the canonical roster role.");
        assertFalse(source.contains("CommandReviveCostPresentation")
                        || source.contains("reviveCostQuoteService"),
                "Panel entries must not carry the removed paid-revival quote protocol.");
        assertFalse(source.contains("deathService")
                        || source.contains("captureService")
                        || source.contains("coopService")
                        || source.contains("lostService"),
                "Process-local lifecycle caches must not compete with the canonical projection.");
    }
}
