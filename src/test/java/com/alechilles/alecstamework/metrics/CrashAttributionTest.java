package com.alechilles.alecstamework.metrics;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashAttributionTest {

    private static final PluginIdentifier TAMEWORK_ID = new PluginIdentifier("Alechilles", "Alec's Tamework!");

    @Test
    void attributesWhenStackContainsTameworkPrefix() {
        RuntimeException throwable = new RuntimeException("boom");
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(
                        "com.alechilles.alecstamework.items.CommandNpcRelocationService",
                        "transferPendingAcrossWorlds",
                        "CommandNpcRelocationService.java",
                        391
                )
        });

        CrashAttribution.AttributionResult result = CrashAttribution.classify(throwable, TAMEWORK_ID);

        assertTrue(result.attributed());
        assertTrue(result.matchedStackPrefix());
        assertNotNull(result.fingerprint());
        assertFalse(result.fingerprint().isBlank());
    }

    @Test
    void doesNotAttributeWithoutPluginOrStackMatch() {
        RuntimeException throwable = new RuntimeException("other");
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.example.plugin.OtherClass", "run", "OtherClass.java", 10)
        });

        CrashAttribution.AttributionResult result = CrashAttribution.classify(throwable, TAMEWORK_ID);

        assertFalse(result.attributed());
        assertFalse(result.matchedStackPrefix());
    }

    @Test
    void fingerprintIsStableForEquivalentThrowables() {
        RuntimeException first = new RuntimeException("same-message");
        first.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.SomeClass", "tick", "SomeClass.java", 45)
        });

        RuntimeException second = new RuntimeException("same-message");
        second.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.SomeClass", "tick", "SomeClass.java", 45)
        });

        String firstFingerprint = CrashAttribution.classify(first, TAMEWORK_ID).fingerprint();
        String secondFingerprint = CrashAttribution.classify(second, TAMEWORK_ID).fingerprint();

        assertEquals(firstFingerprint, secondFingerprint);
    }
}
