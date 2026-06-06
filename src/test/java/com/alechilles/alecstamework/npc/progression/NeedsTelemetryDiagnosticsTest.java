package com.alechilles.alecstamework.npc.progression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeedsTelemetryDiagnosticsTest {

    @Test
    void exposesStableEventNamesAndFingerprints() {
        assertEquals("needs_seek_failed", NeedsTelemetryDiagnostics.EventNames.NEEDS_SEEK_FAILED);
        assertEquals("needs_consume_failed", NeedsTelemetryDiagnostics.EventNames.NEEDS_CONSUME_FAILED);
        assertEquals("tamework.needs.seek.failed", NeedsTelemetryDiagnostics.Fingerprints.NEEDS_SEEK_FAILED);
        assertEquals("tamework.needs.consume.failed", NeedsTelemetryDiagnostics.Fingerprints.NEEDS_CONSUME_FAILED);
    }

    @Test
    void bucketsNeedRatiosForContextBreakdowns() {
        assertEquals("unknown", NeedsTelemetryDiagnostics.needsBucket(null));
        assertEquals("0-25", NeedsTelemetryDiagnostics.needsBucket(0.12d));
        assertEquals("26-50", NeedsTelemetryDiagnostics.needsBucket(0.50d));
        assertEquals("51-75", NeedsTelemetryDiagnostics.needsBucket(0.75d));
        assertEquals("76-100", NeedsTelemetryDiagnostics.needsBucket(0.90d));
    }

    @Test
    void normalizesResourcesAndReasons() {
        assertEquals("food", NeedsTelemetryDiagnostics.normalizeResource("FOOD"));
        assertEquals("water", NeedsTelemetryDiagnostics.normalizeResource("WATER"));
        assertEquals("both", NeedsTelemetryDiagnostics.normalizeResource("BOTH"));
        assertEquals("both", NeedsTelemetryDiagnostics.normalizeResource("AUTO"));
        assertEquals("both", NeedsTelemetryDiagnostics.normalizeResource("FOOD_AND_WATER"));
        assertEquals("unknown", NeedsTelemetryDiagnostics.normalizeResource("other"));
        assertEquals("no_water_target+cached_miss", NeedsTelemetryDiagnostics.normalizeReason("no_water_target,cached_miss"));
    }

    @Test
    void keepsConsumeReasonLowCardinalityWhenContainerSummaryIsPresent() {
        NeedsTelemetryDiagnostics.ConsumeFailureContext context = NeedsTelemetryDiagnostics.consumeFailureContext(
                "no_container_food_consumed(status=NO_ALLOWED_FOOD_IN_RANGE,containers=2,"
                        + "allowedContainers=0,matchingStacks=0,attempts=0,failures=0,maxItems=1,"
                        + "radius=2.00,vScan=2,scanSource=TARGET,scanBlock=[1,2,3])"
        );

        assertEquals("no_container_food_consumed", context.reason());
        assertEquals("NO_ALLOWED_FOOD_IN_RANGE", context.detail("status"));
        assertEquals("2", context.detail("containers"));
        assertEquals("0", context.detail("matchingStacks"));
        assertEquals("TARGET", context.detail("scanSource"));
    }

    @Test
    void combinesFoodAndWaterConsumeFailureIntoStableReason() {
        NeedsTelemetryDiagnostics.ConsumeFailureContext context = NeedsTelemetryDiagnostics.consumeFailureContext(
                "no_container_food_consumed(status=NO_ALLOWED_FOOD_IN_RANGE,containers=1,"
                        + "allowedContainers=0,matchingStacks=0,attempts=0,failures=0,maxItems=1),not_near_water"
        );

        assertEquals("food_and_water_unavailable", context.reason());
        assertEquals("NO_ALLOWED_FOOD_IN_RANGE", context.detail("status"));
    }

    @Test
    void reportsOnlyActionableSeekFailureReasons() {
        assertFalse(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("food_target_not_found"));
        assertFalse(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("water_target_not_found"));
        assertFalse(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("need_ratio_above_threshold"));
        assertFalse(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("base_sensor_mismatch"));

        assertTrue(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("food_item_ids_empty"));
        assertTrue(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("food_source_found_but_no_stand_target"));
        assertTrue(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("water_source_found_but_no_stand_target"));
        assertTrue(NeedsTelemetryDiagnostics.isReportableSeekFailureReason("needs_config_missing_or_disabled"));
    }
}
