package com.alechilles.alecstamework.npc.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies owner timeline windows used by owner-gated needs progression. */
class OwnerPresenceTimelineServiceTest {
    private static final long HOUR_MS = 3_600_000L;
    private final OwnerPresenceTimelineService service = OwnerPresenceTimelineService.get();

    @BeforeEach
    void setUp() {
        service.clearForTests();
    }

    @AfterEach
    void tearDown() {
        service.clearForTests();
    }

    @Test
    void ownerOnlineCountsFullWindow() {
        UUID ownerId = UUID.randomUUID();
        service.markOnlineForTests(ownerId, 0L);

        long effective = service.resolveEffectiveElapsedMs(ownerId, 10_000L, 70_000L, policy(72.0, 1.0));

        assertEquals(60_000L, effective);
    }

    @Test
    void ownerOfflineWithinGraceCountsZeroWindow() {
        UUID ownerId = UUID.randomUUID();
        service.markOfflineForTests(ownerId, 0L);

        long effective = service.resolveEffectiveElapsedMs(ownerId, HOUR_MS, 2L * HOUR_MS, policy(72.0, 1.0));

        assertEquals(0L, effective);
    }

    @Test
    void ownerOfflinePastGraceUsesConfiguredMultiplier() {
        UUID ownerId = UUID.randomUUID();
        service.markOfflineForTests(ownerId, 0L);
        long start = 80L * HOUR_MS;
        long end = 84L * HOUR_MS;

        long effective = service.resolveEffectiveElapsedMs(ownerId, start, end, policy(72.0, 0.5));

        assertEquals(2L * HOUR_MS, effective);
    }

    @Test
    void reconnectDuringGraceDoesNotBackfillOfflineGap() {
        UUID ownerId = UUID.randomUUID();
        service.markOfflineForTests(ownerId, 0L);
        service.markOnlineForTests(ownerId, 2L * HOUR_MS);

        long effective = service.resolveEffectiveElapsedMs(ownerId, HOUR_MS, 3L * HOUR_MS, policy(72.0, 1.0));

        assertEquals(HOUR_MS, effective);
    }

    private TwNeedsConfig.TickPolicySettings policy(double graceHours, double multiplier) {
        TwNeedsConfig.TickPolicySettings settings = new TwNeedsConfig.TickPolicySettings();
        try {
            setField(settings, "mode", TwNeedsConfig.TickPolicyMode.OWNER_ONLINE_GRACE_THEN_DECAY);
            setField(settings, "ownerOfflineGraceHours", graceHours);
            setField(settings, "ownerOfflineDecayMultiplier", multiplier);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return settings;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
