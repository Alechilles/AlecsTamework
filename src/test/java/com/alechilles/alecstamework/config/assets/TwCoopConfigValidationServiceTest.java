package com.alechilles.alecstamework.config.assets;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for process-local managed-coop validation warning suppression. */
class TwCoopConfigValidationServiceTest {
    @AfterEach
    void clearWarningState() {
        TwCoopConfigValidationService.resetWarnings();
    }

    @Test
    void removedConfigCanWarnAgainWhenReadded() throws Exception {
        Map<String, String> warnings = warningState();
        warnings.put("Coop_Invalid", "PreserveUUID=true");

        TwCoopConfigValidationService.forgetConfigs(List.of(" Coop_Invalid "));

        assertFalse(warnings.containsKey("Coop_Invalid"));
    }

    @Test
    void newPluginLifecycleClearsPriorSuppression() throws Exception {
        Map<String, String> warnings = warningState();
        warnings.put("Coop_Invalid", "PreserveUUID=true");

        TwCoopConfigValidationService.resetWarnings();

        assertTrue(warnings.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> warningState() throws Exception {
        Field field = TwCoopConfigValidationService.class.getDeclaredField("LAST_WARNING_BY_CONFIG");
        field.setAccessible(true);
        return (Map<String, String>) field.get(null);
    }
}
