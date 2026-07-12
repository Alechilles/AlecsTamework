package com.alechilles.alecstamework.commands;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards signed world-time visibility in the targeted breeding diagnostics command. */
class TameworkGetHappinessSignedTimeTest {
    @Test
    void nonzeroNegativeTimestampsRemainVisibleWithoutDuplicateSentencePunctuation()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/commands/"
                        + "TameworkGetHappinessCommand.java"));

        assertTrue(source.contains("happiness.lastUpdateMs() != 0L"));
        assertFalse(source.contains("happiness.lastUpdateMs() > 0L"));
        assertTrue(source.contains("message.append(\". Breeding component: none\")"));
        assertFalse(source.contains("message.append(\". Breeding component: none.\")"));
    }
}
