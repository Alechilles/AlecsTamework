package com.alechilles.alecstamework.npc.assets;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NeedsSeekResourceFastModeAssetTest {
    @Test
    void needsSeekResourceHasFastConsumeBranchBeforeSeekMotion() throws Exception {
        String asset = Files.readString(
                Path.of("src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Needs_Seek_Resource.json"),
                StandardCharsets.UTF_8
        );

        int fastBranch = asset.indexOf("\"$Comment\": \"Fast mode: consume directly from the stored resource target.\"");
        int seekBranch = asset.indexOf("\"$Comment\": \"Seek toward the stored destination.\"");

        assertTrue(fastBranch >= 0, "Needs seek asset must include a fast consume branch.");
        assertTrue(seekBranch >= 0, "Needs seek asset must keep the accurate movement branch.");
        assertTrue(fastBranch < seekBranch, "Fast consume branch must run before movement.");
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceFastMode\""));
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceConsume\""));
    }
}
