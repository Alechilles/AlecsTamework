package com.alechilles.alecstamework.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LinkedNpcPanelVitalsBinderTest {
    @Test
    void needSlotsAreHiddenWhenRuntimeSystemsAreDisabled() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/LinkedNpcPanelVitalsBinder.java"
        ));

        Assertions.assertTrue(source.contains("shouldShowHappiness"));
        Assertions.assertTrue(source.contains("TameworkRuntimeSettings.happinessEnabled(true)"));
        Assertions.assertTrue(source.contains("shouldShowNeeds"));
        Assertions.assertTrue(source.contains("TameworkRuntimeSettings.needsEnabled(true)"));
        Assertions.assertTrue(source.contains("slotSelector + \".Visible\", visible"));
        Assertions.assertTrue(source.contains("if (!visible)"));
    }
}
