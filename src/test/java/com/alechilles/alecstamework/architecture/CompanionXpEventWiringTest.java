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
    private static final Path HARVEST_DROP_ACTION = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "ActionTameworkHarvestDrop.java"
    );
    private static final Path AVATAR_FLIGHT_MOVEMENT_SYSTEM = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "avatarflight", "AvatarFlightMovementSystem.java"
    );
    private static final Path AVATAR_FLIGHT_PROGRESSION_TUNING = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "avatarflight", "AvatarFlightProgressionTuning.java"
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

    @Test
    void xpEligibilityUsesCompanionStateNotCommandLinks() throws IOException {
        String content = readService();

        assertTrue(
                !content.contains("isXpEligibleLink"),
                "XP eligibility should not be tied to command link state."
        );
        assertTrue(
                !content.contains("missing-command-links-component")
                        && !content.contains("missing-command-tool-link"),
                "Missing command links should not reject otherwise eligible companion XP."
        );
        assertTrue(
                content.contains("TameworkTamedComponent tamed = store.getComponent(npcRef, tamedType);"),
                "Tamed companion state should make XP awards eligible."
        );
        assertTrue(
                content.contains("TameworkOwnerComponent owner = store.getComponent(npcRef, ownerType);"),
                "Owned companion state should make unlinked XP awards eligible."
        );
    }

    @Test
    void tameworkHarvestDropAwardsHarvestXpAfterSuccessfulDrop() throws IOException {
        String content = Files.readString(HARVEST_DROP_ACTION, StandardCharsets.UTF_8);

        int dropFlag = content.indexOf("boolean dropped = false;");
        int itemDrop = content.indexOf("ItemUtils.throwItem(ref, store, drop, this.dropDirection, this.throwSpeed);");
        int markDropped = content.indexOf("dropped = true;");
        int awardXp = content.indexOf("AwardResult result = CompanionLevelingService.awardHarvestXp(ref, store);");
        int debugLog = content.indexOf("logHarvestDropAward(ref, store");

        assertTrue(dropFlag >= 0, "TameworkHarvestDrop should track whether any item actually dropped.");
        assertTrue(itemDrop > dropFlag, "Drop tracking should start before harvest items are thrown.");
        assertTrue(markDropped > itemDrop, "Drop tracking should mark success only after an item is thrown.");
        assertTrue(awardXp > markDropped, "Harvest XP should be awarded after TameworkHarvestDrop succeeds.");
        assertTrue(debugLog > awardXp, "Harvest XP debug diagnostics should log the award outcome.");
    }

    @Test
    void avatarFlightAwardsOnlyThroughTheDedicatedCompanionXpSource() throws IOException {
        String content = Files.readString(AVATAR_FLIGHT_MOVEMENT_SYSTEM, StandardCharsets.UTF_8);

        assertTrue(content.contains("CompanionXpSource.AVATAR_FLIGHT"));
        assertTrue(content.contains("AvatarFlightExperienceService"));
        assertTrue(content.contains("resolveValidatedFlightXpSource("));
        assertTrue(content.contains("sourceResolution.originalRoleId()"));
        assertTrue(content.contains("sourceResolution.recipient()"));
    }

    @Test
    void avatarFlightProgressionTuningKeepsTheSixPublicEffectKeys() throws IOException {
        String content = Files.readString(AVATAR_FLIGHT_PROGRESSION_TUNING, StandardCharsets.UTF_8);

        assertTrue(content.contains("AvatarFlightVigourCapacityMultiplier"));
        assertTrue(content.contains("AvatarFlightVigourRechargeRateMultiplier"));
        assertTrue(content.contains("AvatarFlightForwardBoostCostMultiplier"));
        assertTrue(content.contains("AvatarFlightForwardBoostImpulseMultiplier"));
        assertTrue(content.contains("AvatarFlightGlideSinkMultiplier"));
        assertTrue(content.contains("AvatarFlightClimbLiftMultiplier"));
    }

    private static String readService() throws IOException {
        return Files.readString(LEVELING_SERVICE, StandardCharsets.UTF_8);
    }
}
