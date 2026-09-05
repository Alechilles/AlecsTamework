package com.alechilles.alecstamework.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Protects nested debug commands from reading parent command tokens as arguments. */
class TameworkDebugToggleInputTest {
    @Test
    void flyingCompanionReadsExplicitStateAfterNestedCommandPath() {
        assertEquals("on", TameworkDebugFlyingCompanionCommand.getFirstArg(
                "/tw debug log companion flight on"));
        assertEquals("off", TameworkDebugFlyingCompanionCommand.getFirstArg(
                "/tw debug log companion flight off"));
        assertNull(TameworkDebugFlyingCompanionCommand.getFirstArg(
                "/tw debug log companion flight"));
    }

    @Test
    void needsTelemetryReadsExplicitStateAfterNestedCommandPath() {
        assertEquals("on", TameworkDebugNeedsTelemetryCommand.getFirstArg(
                "/tw debug telemetry needs on"));
        assertEquals("off", TameworkDebugNeedsTelemetryCommand.getFirstArg(
                "/tw debug telemetry needs off"));
        assertNull(TameworkDebugNeedsTelemetryCommand.getFirstArg(
                "/tw debug telemetry needs"));
    }

    @Test
    void respawnTraceReadsExplicitStateAfterNestedCommandPath() {
        assertEquals("on", TameworkDebugRespawnTraceCommand.getFirstArg(
                "/tw debug log respawn-trace on"));
        assertEquals("off", TameworkDebugRespawnTraceCommand.getFirstArg(
                "/tw debug log respawn-trace off"));
        assertNull(TameworkDebugRespawnTraceCommand.getFirstArg(
                "/tw debug log respawn-trace"));
    }

    @Test
    void despawnReadsStateAndRoleWithoutTreatingLogAsTheRoleFilter() {
        assertArrayEquals(new String[]{"on", "Tamed_Rat"},
                TameworkDebugDespawnCommand.getArgs("/tw debug log despawn on Tamed_Rat"));
        assertArrayEquals(new String[]{"off"},
                TameworkDebugDespawnCommand.getArgs("/tw debug log despawn off"));
        assertArrayEquals(new String[]{"clear"},
                TameworkDebugDespawnCommand.getArgs("/tw debug log despawn clear"));
        assertArrayEquals(new String[0],
                TameworkDebugDespawnCommand.getArgs("/tw debug log despawn"));
    }
}
