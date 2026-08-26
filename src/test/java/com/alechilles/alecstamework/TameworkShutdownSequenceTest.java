package com.alechilles.alecstamework;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards the production-owned runtime and public API teardown boundary. */
class TameworkShutdownSequenceTest {
    @Test
    void closesRuntimeAndItsDependentsBeforeApiRegistries() {
        List<String> closeOrder = new ArrayList<>();

        TameworkShutdownSequence.run(
                () -> closeOrder.add("runtime"),
                () -> closeOrder.add("runtime-dependents"),
                () -> closeOrder.add("api"));

        assertEquals(List.of("runtime", "runtime-dependents", "api"), closeOrder);
    }
}
