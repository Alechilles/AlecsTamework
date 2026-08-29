package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.HusbandryOutcomeApi;
import com.alechilles.alecstamework.api.HusbandryOutcomeContext;
import com.alechilles.alecstamework.api.HusbandryOutcomeKind;
import com.alechilles.alecstamework.api.HusbandryOutcomeModifiers;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the synchronous, fail-closed husbandry outcome registry. */
class HusbandryOutcomeRegistryTest {
    private static final UUID OWNER_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final UUID COMPANION_ID = UUID.fromString(
            "20000000-0000-0000-0000-000000000001"
    );

    @Test
    void returnsIdentityWhenNoProviderIsRegistered() {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
        }
    }

    @Test
    void resolvesEveryOutcomeKindWithNormalizedConsumerValues() throws Exception {
        EnumSet<HusbandryOutcomeKind> expectedKinds = EnumSet.of(
                HusbandryOutcomeKind.NEEDS_DECAY,
                HusbandryOutcomeKind.HAPPINESS_DISPOSITION,
                HusbandryOutcomeKind.HARVEST_YIELD,
                HusbandryOutcomeKind.CULL_YIELD,
                HusbandryOutcomeKind.BREEDING_COOLDOWN
        );
        EnumSet<HusbandryOutcomeKind> observedKinds = EnumSet.noneOf(HusbandryOutcomeKind.class);

        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(context -> {
                observedKinds.add(context.kind());
                return switch (context.kind()) {
                    case NEEDS_DECAY -> new HusbandryOutcomeModifiers(
                            -1.0, 1.0, 0.0, 0.0, 1.0);
                    case HAPPINESS_DISPOSITION -> new HusbandryOutcomeModifiers(
                            1.0, 4.0, 0.0, 0.0, 1.0);
                    case HARVEST_YIELD -> new HusbandryOutcomeModifiers(
                            1.0, 1.0, -1.0, 2.0, 1.0);
                    case CULL_YIELD -> new HusbandryOutcomeModifiers(
                            1.0, 1.0, 2.0, -1.0, 1.0);
                    case BREEDING_COOLDOWN -> new HusbandryOutcomeModifiers(
                            1.0, 1.0, 0.0, 0.0, 0.1);
                };
            });

            for (HusbandryOutcomeKind kind : expectedKinds) {
                assertEquals(
                        expectedModifiers(kind),
                        registry.resolve(context(kind))
                );
            }
        }

        assertEquals(expectedKinds, observedKinds);
    }

    @Test
    void clampsProviderModifiersToSafeGameplayRanges() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(ignored -> new HusbandryOutcomeModifiers(
                    4.0, -1.0, 3.0, 4.0, 0.1
            ));

            assertEquals(
                    new HusbandryOutcomeModifiers(1.0, 1.0, 1.0, 1.0, 0.25),
                    registry.resolve(context())
            );
        }
    }

    @Test
    void anyNonFiniteModifierReturnsFullIdentity() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(ignored -> new HusbandryOutcomeModifiers(
                    0.8, Double.NaN, 0.5, 0.7, 0.8
            ));

            assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
        }
    }

    @Test
    void providerFailureReturnsIdentityInsteadOfEscapingIntoAction() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(ignored -> {
                throw new AssertionError("provider failure");
            });

            assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
        }
    }

    @Test
    void nullProviderResultReturnsIdentity() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(ignored -> null);

            assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
        }
    }

    @Test
    void copiesGroupIdsBeforeProviderInvocation() throws Exception {
        HashSet<String> mutableGroups = new HashSet<>();
        mutableGroups.add("runeteria:husbandry");
        HusbandryOutcomeContext input = new HusbandryOutcomeContext(
                HusbandryOutcomeKind.HARVEST_YIELD,
                OWNER_ID,
                COMPANION_ID,
                "Tamed_Cow",
                "runeteria:husbandry",
                mutableGroups,
                "Food_Egg"
        );
        AtomicReference<HusbandryOutcomeContext> observed = new AtomicReference<>();

        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            registry.register(context -> {
                observed.set(context);
                return HusbandryOutcomeModifiers.identity();
            });
            registry.resolve(input);
        }

        mutableGroups.add("runeteria:other");
        assertEquals(Set.of("runeteria:husbandry"), observed.get().groupIds());
        assertThrows(
                UnsupportedOperationException.class,
                () -> observed.get().groupIds().add("runeteria:other")
        );
    }

    @Test
    void allowsOnlyOneActiveProviderAndClosesExactRegistration() throws Exception {
        try (HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry()) {
            AutoCloseable first = registry.register(ignored -> new HusbandryOutcomeModifiers(
                    0.8, 1.2, 0.1, 0.0, 0.8
            ));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.register(ignored -> HusbandryOutcomeModifiers.identity())
            );

            first.close();
            first.close();
            AutoCloseable second = registry.register(ignored -> new HusbandryOutcomeModifiers(
                    0.9, 1.4, 0.2, 0.1, 0.7
            ));
            first.close();
            assertEquals(
                    new HusbandryOutcomeModifiers(0.9, 1.4, 0.2, 0.1, 0.7),
                    registry.resolve(context())
            );
            second.close();
            assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
        }
    }

    @Test
    void closedRegistryRejectsNewRegistrationAndReturnsIdentity() {
        HusbandryOutcomeRegistry registry = new HusbandryOutcomeRegistry();
        registry.close();

        assertFalse(registry.available());
        assertThrows(
                IllegalStateException.class,
                () -> registry.register(ignored -> HusbandryOutcomeModifiers.identity())
        );
        assertEquals(HusbandryOutcomeModifiers.identity(), registry.resolve(context()));
    }

    @Test
    void unavailableFacadeValidatesProviderAndAlwaysReturnsIdentity() throws Exception {
        HusbandryOutcomeApi unavailable = HusbandryOutcomeApi.unavailable();

        assertFalse(unavailable.available());
        assertThrows(
                NullPointerException.class,
                () -> unavailable.register(null)
        );
        AutoCloseable handle = unavailable.register(ignored -> new HusbandryOutcomeModifiers(
                1.0, 2.0, 1.0, 1.0, 0.25
        ));
        assertTrue(handle != null);
        handle.close();
        assertEquals(HusbandryOutcomeModifiers.identity(), unavailable.resolve(context()));
    }

    private static HusbandryOutcomeContext context() {
        return context(HusbandryOutcomeKind.NEEDS_DECAY);
    }

    private static HusbandryOutcomeContext context(HusbandryOutcomeKind kind) {
        return new HusbandryOutcomeContext(
                kind,
                OWNER_ID,
                COMPANION_ID,
                "Tamed_Cow",
                "runeteria:husbandry",
                Set.of("runeteria:husbandry"),
                null
        );
    }

    private static HusbandryOutcomeModifiers expectedModifiers(HusbandryOutcomeKind kind) {
        return switch (kind) {
            case NEEDS_DECAY -> new HusbandryOutcomeModifiers(0.25, 1.0, 0.0, 0.0, 1.0);
            case HAPPINESS_DISPOSITION -> new HusbandryOutcomeModifiers(
                    1.0, 2.0, 0.0, 0.0, 1.0);
            case HARVEST_YIELD -> new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 1.0, 1.0);
            case CULL_YIELD -> new HusbandryOutcomeModifiers(1.0, 1.0, 1.0, 0.0, 1.0);
            case BREEDING_COOLDOWN -> new HusbandryOutcomeModifiers(1.0, 1.0, 0.0, 0.0, 0.25);
        };
    }
}
