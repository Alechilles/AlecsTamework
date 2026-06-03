package com.alechilles.alecstamework.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkEventRegistrationSupportTest {

    @Test
    void detectsShutdownRegistryExceptionExactly() {
        assertTrue(TameworkEventRegistrationSupport.isEventRegistryShutdown(
                new IllegalArgumentException("EventRegistry is shutdown!")
        ));
        assertFalse(TameworkEventRegistrationSupport.isEventRegistryShutdown(
                new IllegalArgumentException("different registration failure")
        ));
        assertFalse(TameworkEventRegistrationSupport.isEventRegistryShutdown(null));
    }
}
