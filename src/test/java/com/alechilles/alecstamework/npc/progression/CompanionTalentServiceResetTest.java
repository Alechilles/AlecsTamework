package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionTalentServiceResetTest {
    private static final Path SERVICE = Path.of(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "progression",
            "CompanionTalentService.java"
    );

    @Test
    void resetTalentsRefundsSpentPointsAndReappliesModifiers() throws IOException {
        String content = Files.readString(SERVICE, StandardCharsets.UTF_8);
        int methodStart = content.indexOf("public static ResetResult resetTalents");
        int methodEnd = content.indexOf("public static double resolvePurchasedEffectMultiplier", methodStart);

        assertTrue(methodStart >= 0, "Companion talent reset method should exist.");
        assertTrue(methodEnd > methodStart, "Companion talent reset method should be bounded.");

        String method = content.substring(methodStart, methodEnd);
        assertTrue(
                method.contains("component.setSpentPoints(0)"),
                "Talent reset must refund spent points."
        );
        assertTrue(
                method.contains("component.setPurchasedTalentIds(new String[0])"),
                "Talent reset must clear purchased talent nodes."
        );
        assertTrue(
                method.contains("store.putComponent(npcRef, type, component)"),
                "Talent reset must persist the refunded component."
        );
        assertTrue(
                method.contains("CompanionStatModifierService.applyTraitModifiers(npcRef, store)"),
                "Talent reset must immediately remove passive stat modifiers from refunded talents."
        );
    }
}
