package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the released direct release/cull behavior after removing population admission. */
class CommandOwnerActionsReleasedBehaviorTest {

    @Test
    void releaseClearsOwnershipAndLinksBeforeSchedulingDespawn() throws Exception {
        String source = source("CommandOwnerReleaseService.java");

        int clearOwner = source.indexOf("clearOwner(npcRef, store)");
        int clearLinks = source.indexOf("clearTamedAndLinks(npcRef, store)");
        int despawn = source.indexOf("npc.setToDespawn()");
        assertTrue(clearOwner >= 0 && clearLinks > clearOwner && despawn > clearLinks);
        assertTrue(source.contains("owner.setOwnerId(null)"));
        assertTrue(source.contains("owner.setOwnerName(null)"));
        assertFalse(source.contains("OwnerMutationScheduler"));
        assertFalse(source.contains("OwnerPopulation"));
    }

    @Test
    void cullRetainsOwnerUntilDeathSnapshotAndRemovesCommandLinks() throws Exception {
        String source = source("CommandOwnerCullService.java");

        int clearLinks = source.indexOf("clearNpcCommandLinks(target)");
        int clearTools = source.indexOf("removeNpcFromAllCommandToolRecords(player, npcUuid)");
        int damage = source.indexOf("applyFatalDamage(target, cause)");
        assertTrue(clearLinks >= 0 && clearTools > clearLinks && damage > clearTools);
        assertFalse(source.contains("TameworkOwnerComponent"));
        assertFalse(source.contains("OwnerMutationScheduler"));
        assertFalse(source.contains("CommandOwnerCullContinuation"));
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "items", fileName
        ), StandardCharsets.UTF_8);
    }
}
