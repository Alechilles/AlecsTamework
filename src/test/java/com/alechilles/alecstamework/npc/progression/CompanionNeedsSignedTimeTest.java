package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for needs progression in negative world timelines. */
class CompanionNeedsSignedTimeTest {

    @AfterEach
    void tearDown() {
        OwnerPresenceTimelineService.get().clearForTests();
    }

    /**
     * Protects WORLD_TIME_SCALED needs from treating a valid negative epoch as
     * an unset timestamp.
     */
    @Test
    void negativeWorldTimestampsAccrueElapsedTime() throws Exception {
        long elapsedMs = CompanionNeedsService.resolveEffectiveElapsedMs(
                needsConfig(),
                null,
                -120_000L,
                -60_000L
        );

        assertEquals(60_000L, elapsedMs);
    }

    @Test
    void initializationPreservesValidNegativeTimestamp() {
        assertEquals(
                -120_000L,
                CompanionNeedsService.resolveNeedsTimestamp(
                        -120_000L,
                        -60_000L,
                        TwNeedsConfig.TimerBasis.WORLD_TIME_SCALED
                )
        );
        assertEquals(
                -60_000L,
                CompanionNeedsService.resolveNeedsTimestamp(
                        0L,
                        -60_000L,
                        TwNeedsConfig.TimerBasis.WORLD_TIME_SCALED
                )
        );
    }

    @Test
    void realTimeInitializationRejectsNegativeWorldTimestamp() {
        assertEquals(
                60_000L,
                CompanionNeedsService.resolveNeedsTimestamp(
                        -120_000L,
                        60_000L,
                        TwNeedsConfig.TimerBasis.REAL_TIME
                )
        );
    }

    @Test
    void onlineOwnerAccruesNegativeWorldTime() throws Exception {
        UUID ownerId = UUID.randomUUID();
        OwnerPresenceTimelineService.get().markOnlineForTests(
                ownerId,
                0L
        );
        CompanionRuntimeClock.advanceByDeltaSeconds(1.0f);

        long elapsedMs = CompanionNeedsService.resolveEffectiveElapsedMs(
                needsConfig(),
                ownerId,
                -120_000L,
                -60_000L
        );

        assertEquals(60_000L, elapsedMs);
    }

    private TwNeedsConfig needsConfig() throws Exception {
        Constructor<TwNeedsConfig> constructor =
                TwNeedsConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwNeedsConfig config = constructor.newInstance();
        TwNeedsConfig.TimingSettings timing = new TwNeedsConfig.TimingSettings();
        setField(timing, "timerBasis", TwNeedsConfig.TimerBasis.WORLD_TIME_SCALED);
        setField(config, "timing", timing);
        return config;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
