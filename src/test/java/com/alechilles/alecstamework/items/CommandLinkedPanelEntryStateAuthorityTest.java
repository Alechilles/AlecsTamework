package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for committed nonphysical state winning over a retiring live projection. */
class CommandLinkedPanelEntryStateAuthorityTest {
    @Test
    void durableLifecycleStatesResolveBeforeLoadedProjection() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/CommandLinkedPanelEntryService.java"
        ));

        int dead = source.indexOf("deathService.getDeadSnapshotForTool(");
        int captured = source.indexOf("captureService.getCapturedSnapshotForToolOrOwner(");
        int cooped = source.indexOf("coopService.getCoopSnapshotForToolOrOwner(");
        int loaded = source.indexOf("LinkedNpcEntry loadedEntry = buildLoadedEntry(");
        int lost = source.indexOf("lostService.getLostSnapshot(");

        assertTrue(dead >= 0 && captured > dead);
        assertTrue(cooped > captured);
        assertTrue(loaded > cooped,
                "A captured/cooped source can remain live until deferred retirement; committed state must win.");
        assertTrue(lost > loaded,
                "Lost remains a missing-projection fallback after authoritative stored and live states.");
        assertTrue(source.contains("if (!dead && !captured && !inCoop && world != null)"));
        assertTrue(source.contains("liveTargetResolver.resolveRedirect(record)"),
                "A released projection must be retried through its canonical profile UUID.");
        assertTrue(source.contains("if (dead && reviveCostPresentation == null)"),
                "A durable DEAD_REVIVABLE roster row must still present its role-configured revival costs "
                        + "when the in-memory death snapshot is unavailable.");
        assertTrue(source.contains("TwCompanionConfig.resolveEffectiveForRole(record.cachedRoleId)"),
                "The durable-dead fallback must use the canonical roster role rather than a hard-coded cost.");
    }
}
