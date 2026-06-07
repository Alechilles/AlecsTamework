package com.alechilles.alecstamework.npc.sensors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests Tamework alarm sensor config parsing. */
class SensorTameworkAlarmTest {

    @Test
    void blankStateDefaultsToPassed() {
        assertEquals(
                SensorTameworkAlarm.AlarmState.PASSED,
                SensorTameworkAlarm.AlarmState.fromConfig("")
        );
    }

    @Test
    void stateParserAcceptsBaseAlarmStyleNames() {
        assertEquals(
                SensorTameworkAlarm.AlarmState.UNSET,
                SensorTameworkAlarm.AlarmState.fromConfig("Unset")
        );
        assertEquals(
                SensorTameworkAlarm.AlarmState.ACTIVE,
                SensorTameworkAlarm.AlarmState.fromConfig("Active")
        );
        assertEquals(
                SensorTameworkAlarm.AlarmState.PASSED,
                SensorTameworkAlarm.AlarmState.fromConfig("Passed")
        );
    }

    @Test
    void unknownStateNeverMatches() {
        assertEquals(
                SensorTameworkAlarm.AlarmState.UNKNOWN,
                SensorTameworkAlarm.AlarmState.fromConfig("Eventually")
        );
    }
}
