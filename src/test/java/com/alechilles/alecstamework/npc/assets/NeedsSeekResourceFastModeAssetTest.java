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
        String fastBranchBody = asset.substring(fastBranch, seekBranch);
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceFastMode\""));
        assertTrue(asset.contains("\"Type\": \"TameworkNeedsResourceConsume\""));
        assertTrue(
                !fastBranchBody.contains("\"Continue\": true"),
                "Fast consume branch must be terminal and must not continue into normal Seek."
        );
        assertTrue(
                fastBranchBody.contains("\"Stage\": \"fast_consume\"")
                        && fastBranchBody.contains("\"ReleaseTarget\": false"),
                "Fast consume must preserve the active target for the shared post-consume release flow."
        );
        int postConsume = asset.indexOf("\"$Comment\": \"After consuming once");
        assertTrue(postConsume > seekBranch, "Needs seek asset must keep the shared post-consume branch.");
        String postConsumeBody = asset.substring(postConsume);
        int postConsumeReadPosition = postConsumeBody.indexOf("\"Type\": \"ReadPosition\"");
        int releaseReservation = postConsumeBody.indexOf("\"Type\": \"TameworkNeedsResourceReleaseTarget\"");
        int releaseTarget = postConsumeBody.indexOf("\"Type\": \"ReleaseTarget\"");
        assertTrue(postConsumeReadPosition >= 0, "Post-consume release must read the active target position.");
        assertTrue(releaseReservation >= 0, "Post-consume completion must release the Tamework reservation.");
        assertTrue(releaseTarget >= 0, "Post-consume completion must clear the active target slot.");
        assertTrue(
                postConsumeReadPosition < releaseReservation,
                "The Tamework reservation release branch must have target position info available."
        );
        assertTrue(
                releaseReservation < releaseTarget,
                "The Tamework reservation must be released before clearing the active target slot."
        );
    }
}
