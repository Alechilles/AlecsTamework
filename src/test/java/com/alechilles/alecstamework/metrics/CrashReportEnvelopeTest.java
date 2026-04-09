package com.alechilles.alecstamework.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportEnvelopeTest {

    @Test
    void serializesExpectedTelemetryFields() {
        RuntimeException throwable = new RuntimeException("test envelope");
        throwable.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.alechilles.alecstamework.SomeSystem", "tick", "SomeSystem.java", 88)
        });

        CrashAttribution.AttributionResult attribution = CrashAttribution.classify(
                throwable,
                new com.hypixel.hytale.common.plugin.PluginIdentifier("Alechilles", "Alec's Tamework!")
        );

        CrashReportEnvelope envelope = CrashReportEnvelope.create(
                "unit_test",
                attribution.fingerprint(),
                "Alechilles:Alec's Tamework!",
                "2.7.3",
                "MainThread",
                "Overworld",
                "EXCEPTIONAL",
                "Alechilles:Alec's Tamework!",
                attribution,
                throwable
        );

        JsonObject json = JsonParser.parseString(envelope.toJson()).getAsJsonObject();
        assertEquals(CrashReportEnvelope.SCHEMA_VERSION, json.get("schemaVersion").getAsInt());
        assertEquals("unit_test", json.get("source").getAsString());
        assertEquals(1, json.get("occurrenceCount").getAsInt());
        assertEquals("Alechilles:Alec's Tamework!", json.get("pluginIdentifier").getAsString());
        assertTrue(json.getAsJsonObject("throwable").has("stack"));
        assertTrue(json.getAsJsonObject("runtime").has("javaVersion"));
        assertTrue(json.getAsJsonObject("runtime").has("hytaleBuild"));
        assertTrue(json.getAsJsonObject("runtime").has("serverVersion"));
        assertTrue(json.getAsJsonObject("runtime").has("loadedMods"));
        assertTrue(json.getAsJsonObject("attribution").has("matchedStackPrefix"));
    }
}
