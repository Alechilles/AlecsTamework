package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the public companion XP event wiring.
 */
class CompanionXpEventWiringTest {
    private static final Path LEVELING_SERVICE = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "progression", "CompanionLevelingService.java"
    );

    @Test
    void simpleXpSourcesUsePublicSourceBuckets() throws IOException {
        String content = readService();

        assertTrue(
                content.contains("awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.FEED, CompanionXpSource.FEED);"),
                "Manual and automatic feed XP must publish the FEED source bucket."
        );
        assertTrue(
                content.contains("awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.HARVEST, CompanionXpSource.HARVEST);"),
                "Harvest XP must publish the HARVEST source bucket."
        );
        assertTrue(
                content.contains("awardSimpleSourceXp(npcRef, store, SimpleXpSourceType.BREEDING, CompanionXpSource.BREEDING);"),
                "Breeding XP must publish the BREEDING source bucket."
        );
    }

    @Test
    void xpEventEmitsOnlyAfterSuccessfulComponentWritePath() throws IOException {
        String content = readService();

        int noChangeReturn = content.indexOf("if (!hasMeaningfulChange(component, updated))");
        int componentWrite = content.indexOf("putComponent(npcRef, store, commandBuffer, type, updated);");
        int eventEmit = content.indexOf("emitXpAwardedEvent(npcRef, store, config, roleId, source, amount");

        assertTrue(noChangeReturn >= 0, "XP service must retain no-op award rejection.");
        assertTrue(componentWrite > noChangeReturn, "XP component write should happen only after no-op rejection.");
        assertTrue(eventEmit > componentWrite, "XP event must emit only after the component write is applied or queued.");
    }

    @Test
    void ownerCreditPrefersCommandLinkOwnerThenFallsBackToOwnerComponent() throws IOException {
        String content = readService();

        int commandLinkOwner = content.indexOf("ownerUuid = links.getOwnerId();");
        int ownerFallback = content.indexOf("TameworkOwnerComponent owner = ownerType != null");

        assertTrue(commandLinkOwner >= 0, "XP event owner credit should read TameworkCommandLinksComponent owner id.");
        assertTrue(ownerFallback > commandLinkOwner, "Owner component lookup should remain a fallback after command links.");
    }

    private static String readService() throws IOException {
        return Files.readString(LEVELING_SERVICE, StandardCharsets.UTF_8);
    }
}
