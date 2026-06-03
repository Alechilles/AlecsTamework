package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards registration of the XP event debug command.
 */
class DebugXpEventsCommandRegistrationTest {
    private static final Path COMMAND_ROOT = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "commands", "TameworkCommandRoot.java"
    );

    @Test
    void rootCommandRegistersDebugXpEventsCommand() throws IOException {
        String content = Files.readString(COMMAND_ROOT, StandardCharsets.UTF_8);

        assertTrue(
                content.contains("addSubCommand(new TameworkDebugXpEventsCommand());"),
                "/tw must register the debugxpevents command."
        );
    }
}
