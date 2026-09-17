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
    @Test
    void debugTargetHudReadsNestedArgument() {
        assertEquals("off", TameworkDebugTargetHudCommand.getFirstArg("/tw debug log target-hud off"));
        assertNull(TameworkDebugTargetHudCommand.getFirstArg("/tw debug log target-hud"));
    }

    @Test
    void debugXpEventsReadsNestedArgument() {
        assertEquals("off", TameworkDebugXpEventsCommand.getFirstArg("/tw debug log xp-events off"));
        assertNull(TameworkDebugXpEventsCommand.getFirstArg("/tw debug log xp-events"));
    }

    @Test
    void debugCrashTelemetryReadsNestedArgument() {
        assertEquals("flush", TameworkDebugCrashTelemetryCommand.getFirstArg("/tw debug telemetry crash flush"));
        assertNull(TameworkDebugCrashTelemetryCommand.getFirstArg("/tw debug telemetry crash"));
    }

    @Test
    void setOwnerReadsNestedArgument() {
        assertEquals("clear", TameworkSetOwnerCommand.getFirstArg("/tw debug set owner clear"));
        assertNull(TameworkSetOwnerCommand.getFirstArg("/tw debug set owner"));
    }

    @Test
    void setTamedReadsNestedArgument() {
        assertEquals("false", TameworkSetTamedCommand.getFirstArg("/tw debug set tamed false"));
        assertNull(TameworkSetTamedCommand.getFirstArg("/tw debug set tamed"));
    }

    @Test
    void playerInputReadsStatusInsteadOfToggling() {
        assertEquals("status", TameworkDebugPlayerInputCommand.getFirstArg("/tw debug avatar input STATUS"));
        assertEquals("", TameworkDebugPlayerInputCommand.getFirstArg("/tw debug avatar input"));
    }

    @Test
    void dragonFlightReadsActionAndConfig() {
        assertArrayEquals(new String[]{"on", "Tw_Test"},
                TameworkDebugDragonFlightCommand.getArgs("/tw debug avatar dragon-flight on Tw_Test"));
        assertArrayEquals(new String[0],
                TameworkDebugDragonFlightCommand.getArgs("/tw debug avatar dragon-flight"));
    }

    @Test
    void playerModelReadsActionModelAndScale() {
        assertArrayEquals(new String[]{"unsafe", "Test_Model", "1.5"},
                TameworkDebugPlayerModelCommand.getArgs("/tw debug avatar player-model unsafe Test_Model 1.5"));
        assertArrayEquals(new String[0],
                TameworkDebugPlayerModelCommand.getArgs("/tw debug avatar player-model"));
    }
    @Test
    void findNpcReadsUuidAndMarkerFlag() {
        String input = "/tw npc find 12345678-1234-1234-1234-123456789abc off";
        assertEquals("12345678-1234-1234-1234-123456789abc", TameworkFindNpcCommand.getArg(input, 0));
        assertEquals("off", TameworkFindNpcCommand.getArg(input, 1));
        assertNull(TameworkFindNpcCommand.getArg("/tw npc find", 0));
    }

    @Test
    void npcCleanReadsRequestedRole() {
        assertEquals("Tamed_Ram", TameworkNpcCleanCommand.getArg("/tw npc clean Tamed_Ram", 0));
        assertNull(TameworkNpcCleanCommand.getArg("/tw npc clean", 0));
    }

    @Test
    void alarmReadsNameAndOptionalNpc() {
        String input = "/tw debug get alarm Tw_Alarm 12345678-1234-1234-1234-123456789abc";
        assertEquals("Tw_Alarm", TameworkGetAlarmCommand.getArg(input, 0));
        assertEquals("12345678-1234-1234-1234-123456789abc", TameworkGetAlarmCommand.getArg(input, 1));
        assertNull(TameworkGetAlarmCommand.getArg("/tw debug get alarm", 0));
    }
    @Test
    void argumentMatchingCommandNameIsPreserved() {
        assertArrayEquals(new String[]{"alarm", "12345678-1234-1234-1234-123456789abc"},
                TameworkCommandInput.argumentsAfter(
                        "/tw debug get alarm alarm 12345678-1234-1234-1234-123456789abc", "alarm"));
    }
    @Test
    void namedWorldOptionIsNotAnAlarmName() {
        String input = "/tw debug get alarm 12345678-1234-1234-1234-123456789abc --world=default";
        assertEquals("12345678-1234-1234-1234-123456789abc", TameworkGetAlarmCommand.getArg(input, 0));
        assertNull(TameworkGetAlarmCommand.getArg(input, 1));
        assertArrayEquals(new String[]{"-25"},
                TameworkCommandInput.argumentsAfter("/tw debug set happiness -25 --help", "happiness"));
    }
    @Test
    void invalidTamedStateIsRejectedInsteadOfToggling() {
        assertNull(TameworkSetTamedCommand.resolveRequestedState("flase", true));
        assertEquals(false, TameworkSetTamedCommand.resolveRequestedState("off", true));
        assertEquals(false, TameworkSetTamedCommand.resolveRequestedState("toggle", true));
        assertEquals(true, TameworkSetTamedCommand.resolveRequestedState(null, false));
    }
    @Test
    void invalidMarkerStateIsRejectedInsteadOfEnabled() {
        assertNull(TameworkFindNpcCommand.parseOptionalMark("of"));
        assertEquals(false, TameworkFindNpcCommand.parseOptionalMark("off"));
        assertEquals(true, TameworkFindNpcCommand.parseOptionalMark(null));
    }
}
