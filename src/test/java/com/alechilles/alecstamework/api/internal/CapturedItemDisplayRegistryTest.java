package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.CapturedItemDisplayContext;
import com.alechilles.alecstamework.api.CapturedItemDisplayContribution;
import com.alechilles.alecstamework.api.CapturedItemDisplayProvider;
import com.alechilles.alecstamework.api.ProgressionView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the optional, fail-closed captured-item display registry. */
class CapturedItemDisplayRegistryTest {
    @Test
    void resolvesRegisteredContributionAndDetachesTraitValues() throws Exception {
        List<ProgressionView.TraitValueView> values = new ArrayList<>();
        values.add(new ProgressionView.TraitValueView("star", 3.0, null));
        CapturedItemDisplayContext input = new CapturedItemDisplayContext(
                "runeteria:capture_cow",
                "Tamed_Cow",
                new ProgressionView.TraitsView("runeteria:traits", 7L, values)
        );
        AtomicReference<CapturedItemDisplayContext> observed = new AtomicReference<>();
        CapturedItemDisplayContribution expected = new CapturedItemDisplayContribution(null, "Rare");

        try (CapturedItemDisplayRegistry registry = new CapturedItemDisplayRegistry()) {
            registry.register(context -> {
                observed.set(context);
                return expected;
            });

            assertSame(expected, registry.resolve(input));
        }

        values.add(new ProgressionView.TraitValueView("star", 5.0, null));
        assertEquals(1, observed.get().traits().values().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observed.get().traits().values().add(
                        new ProgressionView.TraitValueView("other", 1.0, null))
        );
    }

    @Test
    void providerFailureFallsBackToNoContribution() throws Exception {
        try (CapturedItemDisplayRegistry registry = new CapturedItemDisplayRegistry()) {
            registry.register(ignored -> {
                throw new LinkageError("optional provider is incompatible");
            });

            assertEquals(CapturedItemDisplayContribution.none(), registry.resolve(context()));
        }
    }

    @Test
    void unregisterHandleIsIdempotentAndDoesNotRemoveReplacement() throws Exception {
        try (CapturedItemDisplayRegistry registry = new CapturedItemDisplayRegistry()) {
            AutoCloseable first = registry.register(ignored -> new CapturedItemDisplayContribution(null, "Rare"));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.register(ignored -> CapturedItemDisplayContribution.none())
            );

            first.close();
            first.close();
            AutoCloseable second = registry.register(ignored -> new CapturedItemDisplayContribution(null, "Epic"));
            first.close();
            assertEquals("Epic", registry.resolve(context()).qualityId());
            second.close();
            assertEquals(CapturedItemDisplayContribution.none(), registry.resolve(context()));
        }
    }

    @Test
    void closeClearsProviderAndRejectsNewRegistration() {
        CapturedItemDisplayRegistry registry = new CapturedItemDisplayRegistry();
        registry.register(ignored -> new CapturedItemDisplayContribution(null, "Legendary"));

        registry.close();

        assertFalse(registry.available());
        assertEquals(CapturedItemDisplayContribution.none(), registry.resolve(context()));
        assertThrows(
                IllegalStateException.class,
                () -> registry.register(ignored -> CapturedItemDisplayContribution.none())
        );
        assertFalse(registry.available());
    }

    @Test
    void unavailableFacadeValidatesProviderAndReturnsNoContribution() throws Exception {
        var unavailable = com.alechilles.alecstamework.api.CapturedItemDisplayApi.unavailable();

        assertFalse(unavailable.available());
        assertThrows(NullPointerException.class, () -> unavailable.register(null));
        AutoCloseable handle = unavailable.register(ignored -> new CapturedItemDisplayContribution(null, "Rare"));
        assertTrue(handle != null);
        handle.close();
        assertEquals(CapturedItemDisplayContribution.none(), unavailable.resolve(context()));
    }

    private static CapturedItemDisplayContext context() {
        return new CapturedItemDisplayContext("runeteria:capture_cow", "Tamed_Cow", null);
    }
}
