package com.alechilles.alecstamework.commands;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards signed world-time visibility in the targeted breeding diagnostics command. */
class TameworkGetHappinessSignedTimeTest {
    @Test
    void nonzeroNegativeTimestampsRemainVisibleWithoutDuplicateSentencePunctuation()
            throws Exception {
        Class<?> happinessType = Class.forName(
                TameworkGetHappinessCommand.class.getName() + "$HappinessSnapshot"
        );
        Constructor<?> happinessConstructor = happinessType.getDeclaredConstructor(
                double.class,
                String.class,
                long.class,
                String.class,
                double.class,
                double.class,
                List.class
        );
        happinessConstructor.setAccessible(true);
        Object happiness = happinessConstructor.newInstance(
                75.0,
                null,
                -123_456L,
                "shared",
                75.0,
                75.0,
                List.of()
        );

        Class<?> breedingType = Class.forName(
                TameworkGetHappinessCommand.class.getName() + "$BreedingSnapshot"
        );
        Method emptyBreeding = breedingType.getDeclaredMethod("empty");
        emptyBreeding.setAccessible(true);
        Object breeding = emptyBreeding.invoke(null);

        Method buildMessage = TameworkGetHappinessCommand.class.getDeclaredMethod(
                "buildMessage",
                UUID.class,
                happinessType,
                breedingType
        );
        buildMessage.setAccessible(true);
        String message = (String) buildMessage.invoke(
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                happiness,
                breeding
        );

        assertTrue(message.contains("lastUpdateMs=-123456"));
        assertTrue(message.endsWith(". Breeding component: none."));
        assertFalse(message.contains(".."));
    }
}
