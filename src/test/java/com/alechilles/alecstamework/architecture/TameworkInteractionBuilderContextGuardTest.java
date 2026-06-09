package com.alechilles.alecstamework.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Guards Tamework interaction actions against drifting out of base-game interaction instruction context.
 */
class TameworkInteractionBuilderContextGuardTest {
    private static final Path INTERACT_BUILDER = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "actions",
            "BuilderActionTameworkInteract.java"
    );
    private static final Path PROMPT_BUILDER = Paths.get(
            "src",
            "main",
            "java",
            "com",
            "alechilles",
            "alecstamework",
            "npc",
            "actions",
            "BuilderActionTameworkInteractPrompt.java"
    );

    @Test
    void optimizedInteractionActionsRequireInteractionInstructionContext() throws IOException {
        String interactContent = Files.readString(INTERACT_BUILDER, StandardCharsets.UTF_8);
        String promptContent = Files.readString(PROMPT_BUILDER, StandardCharsets.UTF_8);

        assertTrue(
                interactContent.contains("requireInstructionType(EnumSet.of(InstructionType.Interaction));"),
                "TameworkInteract must require InteractionInstruction context like vanilla interaction actions."
        );
        assertTrue(
                promptContent.contains("extends BuilderActionTameworkInteract"),
                "TameworkInteractPrompt must inherit the TameworkInteract interaction-context requirement."
        );
    }
}
