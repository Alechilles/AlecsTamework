package com.alechilles.alecstamework.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkSettingsProgressionRefreshTest {

    @Test
    void applyingSettingsRefreshesLoadedNpcProgressionStatModifiers() throws IOException {
        String content = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "ui",
                "TameworkSettingsPage.java"
        ));

        assertTrue(
                content.contains("CompanionStatModifierRefreshService.refreshLoadedNpcStatModifiers(store);"),
                "Applying /tw settings must reapply loaded NPC stat modifiers so disabled talents or leveling remove stale bonuses."
        );
    }

    @Test
    void progressionRefreshCoversTalentOnlyNpcsWithoutTraitComponents() throws IOException {
        String content = Files.readString(Path.of(
                "src",
                "main",
                "java",
                "com",
                "alechilles",
                "alecstamework",
                "npc",
                "progression",
                "CompanionStatModifierRefreshService.java"
        ));

        assertTrue(content.contains("Query.and(npcType, statType)"));
        assertFalse(
                content.contains("TameworkTraitsComponent"),
                "Talent-only max-health modifiers must refresh even when the NPC has no trait component."
        );
    }
}
