package com.alechilles.alecstamework.npc.systems;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcMountedNameplateVisibilitySystemTest {
    private static final Path SYSTEM_PATH = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "systems",
            "NpcMountedNameplateVisibilitySystem.java"
    );

    @Test
    void mountedNameplateVisibilityIsTickEnforced() throws IOException {
        String content = Files.readString(SYSTEM_PATH, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("extends TickingSystem<EntityStore>"),
                "Mounted nameplate visibility must not depend only on RefSystem add/remove callbacks."
        );
        assertTrue(
                content.contains("Query.and(npcType, mountType)"),
                "The system must scan currently mounted NPCs so missed add callbacks still hide names."
        );
        assertTrue(
                content.contains("Query.and(npcType, mountedNameplateType)"),
                "The system must scan cached hidden-name state so missed remove callbacks still restore names."
        );
    }
}
