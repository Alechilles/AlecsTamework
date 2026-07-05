package com.alechilles.alecstamework.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TameworkRuntimePressureServiceTest {
    @Test
    void recordedWorkRaisesBackoffMultiplierForDomain() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        long nowMs = 1_000L;

        assertEquals(1_000L, service.scaleTtlMs(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 1_000L, nowMs));

        for (int i = 0; i < 700; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, nowMs);
        }

        assertTrue(service.scaleTtlMs(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 1_000L, nowMs) > 1_000L);
    }

    @Test
    void pressureDecaysAfterQuietWindows() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        long nowMs = 1_000L;

        for (int i = 0; i < 700; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, nowMs);
        }
        long pressuredTtl = service.scaleTtlMs(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 1_000L, nowMs);

        long quietMs = nowMs + (TameworkRuntimePressureService.WINDOW_MS * 4L) + 1L;
        long recoveredTtl = service.scaleTtlMs(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 1_000L, quietMs);

        assertTrue(pressuredTtl > 1_000L);
        assertTrue(recoveredTtl < pressuredTtl);
    }

    @Test
    void domainPressureDoesNotBleedIntoOtherDomains() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        long nowMs = 1_000L;

        for (int i = 0; i < 700; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, nowMs);
        }

        assertTrue(service.scaleTtlMs(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 1_000L, nowMs) > 1_000L);
        assertEquals(1_000L, service.scaleTtlMs(RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, 1_000L, nowMs));
    }

    @Test
    void exposesCurrentPressureLevel() {
        TameworkRuntimePressureService service = new TameworkRuntimePressureService();
        long nowMs = 10_000L;

        assertEquals(RuntimePressureLevel.NORMAL, service.level(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, nowMs));

        for (int i = 0; i < 512; i++) {
            service.recordWork(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, 100_000L, nowMs);
        }

        assertEquals(RuntimePressureLevel.HOT, service.level(RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, nowMs));
        assertTrue(service.isAtLeast(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH, RuntimePressureLevel.WARM, nowMs));
        assertFalse(service.isAtLeast(
                RuntimePressureDomain.NEEDS_PATH_PREFLIGHT, RuntimePressureLevel.WARM, nowMs));
    }
}
