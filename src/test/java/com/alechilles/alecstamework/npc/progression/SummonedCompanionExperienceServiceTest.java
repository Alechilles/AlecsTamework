package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SummonedCompanionExperienceServiceTest {
    private static final double EPSILON = 0.00001d;

    @Test
    void firstSampleInitializesTimestampWithoutAwardingXp() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();

        SummonedCompanionExperienceService.Result result = service.advance(
                service.reset(1_000L), 1_000L, 0.25d, settings(0.5d, 10.0d, 50.0d), true);

        assertEquals(0.0d, result.awardedXp(), EPSILON);
        assertEquals(0.0d, result.state().activeSeconds(), EPSILON);
        assertEquals(1_000L, result.state().lastSampleAtMs());
    }

    @Test
    void awardsOnlyOnWholeActiveIntervals() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();
        TwLevelingConfig.SummonedXpSourceSettings settings = settings(0.5d, 1.0d, 50.0d);
        SummonedCompanionExperienceService.State state = service.reset(1_000L);

        state = service.advance(state, 1_000L, 0.25d, settings, true).state();
        state = service.advance(state, 1_250L, 0.25d, settings, true).state();
        state = service.advance(state, 1_500L, 0.25d, settings, true).state();
        state = service.advance(state, 1_750L, 0.25d, settings, true).state();
        SummonedCompanionExperienceService.Result result = service.advance(
                state, 2_000L, 0.25d, settings, true);

        assertEquals(0.5d, result.awardedXp(), EPSILON);
        assertEquals(0.0d, result.state().activeSeconds(), EPSILON);
    }

    @Test
    void largeTickContributesAtMostQuarterSecond() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();

        SummonedCompanionExperienceService.Result result = service.advance(
                new SummonedCompanionExperienceService.State(0.0d, 0.0d, 1_000L, 1_000L),
                31_000L, 30.0d, settings(0.5d, 10.0d, 50.0d), true);

        assertEquals(0.0d, result.awardedXp(), EPSILON);
        assertEquals(0.25d, result.state().activeSeconds(), EPSILON);
    }

    @Test
    void disabledSettingsAwardNothingAndDiscardPartialProgress() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();

        SummonedCompanionExperienceService.Result result = service.advance(
                new SummonedCompanionExperienceService.State(0.8d, 2.0d, 1_000L, 1_000L),
                1_250L, 0.25d, settings(false, 0.5d, 1.0d, 50.0d), true);

        assertEquals(0.0d, result.awardedXp(), EPSILON);
        assertEquals(0.0d, result.state().activeSeconds(), EPSILON);
        assertEquals(2.0d, result.state().windowAwardedXp(), EPSILON);
    }

    @Test
    void gappedSampleDiscardsPartialProgressWithoutResettingHourlyCap() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();

        SummonedCompanionExperienceService.Result result = service.advance(
                new SummonedCompanionExperienceService.State(0.8d, 2.0d, 1_000L, 1_000L),
                10_000L, 0.25d, settings(0.5d, 1.0d, 50.0d), true);

        assertEquals(0.0d, result.awardedXp(), EPSILON);
        assertEquals(0.0d, result.state().activeSeconds(), EPSILON);
        assertEquals(2.0d, result.state().windowAwardedXp(), EPSILON);
    }

    @Test
    void eachAwardIsBoundedByHourlyCap() throws Exception {
        SummonedCompanionExperienceService service = new SummonedCompanionExperienceService();

        SummonedCompanionExperienceService.Result result = service.advance(
                new SummonedCompanionExperienceService.State(9.8d, 4.9d, 1_000L, 1_000L),
                1_250L, 0.25d, settings(10.0d, 10.0d, 5.0d), true);

        assertEquals(0.1d, result.awardedXp(), EPSILON);
        assertEquals(5.0d, result.state().windowAwardedXp(), EPSILON);
    }

    private static TwLevelingConfig.SummonedXpSourceSettings settings(double xpPerActiveSecond,
                                                                        double awardIntervalSeconds,
                                                                        double maxXpPerHour) throws Exception {
        return settings(true, xpPerActiveSecond, awardIntervalSeconds, maxXpPerHour);
    }

    private static TwLevelingConfig.SummonedXpSourceSettings settings(boolean enabled,
                                                                        double xpPerActiveSecond,
                                                                        double awardIntervalSeconds,
                                                                        double maxXpPerHour) throws Exception {
        TwLevelingConfig.SummonedXpSourceSettings settings = new TwLevelingConfig.SummonedXpSourceSettings();
        setField(settings, "enabled", enabled);
        setField(settings, "xpPerActiveSecond", xpPerActiveSecond);
        setField(settings, "awardIntervalSeconds", awardIntervalSeconds);
        setField(settings, "maxXpPerHour", maxXpPerHour);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
