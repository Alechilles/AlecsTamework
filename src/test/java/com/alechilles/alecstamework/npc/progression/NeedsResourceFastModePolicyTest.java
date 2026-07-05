package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.settings.NeedsResourceMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NeedsResourceFastModePolicyTest {
    @BeforeEach
    void clearPressure() {
        TameworkRuntimePressureService.getInstance().clearForTests();
    }

    @Test
    void accurateNeverActivatesEvenUnderHighPressure() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();
        recordHotPressure(service, RuntimePressureDomain.NEEDS_RESOURCE_SEARCH);
        recordHotPressure(service, RuntimePressureDomain.NEEDS_PATH_PREFLIGHT);

        assertFalse(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.ACCURATE, service, 1_000L));
    }

    @Test
    void alwaysFastIgnoresPressure() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();

        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.ALWAYS_FAST, service, 1_000L));
    }

    @Test
    void autoFastActivatesUnderHotSearchPressure() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();
        recordHotPressure(service, RuntimePressureDomain.NEEDS_RESOURCE_SEARCH);

        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, service, 1_000L));
    }

    @Test
    void autoFastActivatesUnderHotPathPreflightPressure() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();
        recordHotPressure(service, RuntimePressureDomain.NEEDS_PATH_PREFLIGHT);

        assertTrue(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, service, 1_000L));
    }

    @Test
    void autoFastRemainsFalseBelowHot() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();
        for (int i = 0; i < 128; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, 1_000L);
        }

        assertFalse(NeedsResourceFastModePolicy.isFastModeActive(NeedsResourceMode.AUTO_FAST, service, 1_000L));
    }

    @Test
    void nullModeRemainsAccurate() {
        TameworkRuntimePressureService service = TameworkRuntimePressureService.getInstance();
        recordHotPressure(service, RuntimePressureDomain.NEEDS_RESOURCE_SEARCH);

        assertFalse(NeedsResourceFastModePolicy.isFastModeActive(null, service, 1_000L));
    }

    private static void recordHotPressure(
            TameworkRuntimePressureService service, RuntimePressureDomain domain) {
        for (int i = 0; i < 512; i++) {
            service.recordWork(domain, 100_000L, 1_000L);
        }
    }
}
