package com.alechilles.alecstamework.avatarflight;

import com.alechilles.alecstamework.config.assets.TwLevelingConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AvatarFlightExperienceServiceTest {
    private static final double EPSILON = 0.00001;

    @Test
    void firstSampleInitializesTimestampWithoutAwardingXp() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();

        AvatarFlightExperienceService.Result result = service.tick(
                service.reset(1_000L), settings(0.15, 10.0, 9.0), true, 1_000L);

        assertEquals(0.0, result.awardedXp(), EPSILON);
        assertEquals(0.0, result.state().qualifiedSeconds(), EPSILON);
        assertEquals(1_000L, result.state().lastSampleAtMs());
    }

    @Test
    void tenQualifiedSecondsAwardOneFullInterval() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();
        AvatarFlightExperienceService.State state = service.reset(1_000L);
        TwLevelingConfig.FlightXpSourceSettings settings = settings(0.15, 10.0, 9.0);

        state = service.tick(state, settings, true, 1_000L).state();
        AvatarFlightExperienceService.Result result = tickQualified(service, state, settings, 1_000L, 40);

        assertEquals(1.5, result.awardedXp(), EPSILON);
        assertEquals(0.0, result.state().qualifiedSeconds(), EPSILON);
    }

    @Test
    void nonQualifyingSamplesPauseAccumulationWithoutDiscardingPartialInterval() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();
        TwLevelingConfig.FlightXpSourceSettings settings = settings(0.15, 10.0, 9.0);
        AvatarFlightExperienceService.State state = new AvatarFlightExperienceService.State(9.8, 0.0, 1_000L, 10_800L);

        AvatarFlightExperienceService.Result paused = service.tick(state, settings, false, 11_000L);
        AvatarFlightExperienceService.Result result = service.tick(paused.state(), settings, true, 11_200L);

        assertEquals(0.0, paused.awardedXp(), EPSILON);
        assertEquals(9.8, paused.state().qualifiedSeconds(), EPSILON);
        assertEquals(1.5, result.awardedXp(), EPSILON);
        assertEquals(0.0, result.state().qualifiedSeconds(), EPSILON);
    }

    @Test
    void disabledOrZeroRateSettingsAwardNoXp() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();
        AvatarFlightExperienceService.State state = new AvatarFlightExperienceService.State(9.8, 0.0, 1_000L, 10_800L);

        AvatarFlightExperienceService.Result disabled = service.tick(
                state, settings(false, 0.15, 10.0, 9.0), true, 11_000L);
        AvatarFlightExperienceService.Result zeroRate = service.tick(
                state, settings(true, 0.0, 10.0, 9.0), true, 11_000L);

        assertEquals(0.0, disabled.awardedXp(), EPSILON);
        assertEquals(0.0, zeroRate.awardedXp(), EPSILON);
    }

    @Test
    void minuteCapLimitsAwardToNineXp() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();

        AvatarFlightExperienceService.Result result = service.tick(
                new AvatarFlightExperienceService.State(59.8, 0.0, 1_000L, 10_800L),
                settings(0.15, 10.0, 9.0), true, 11_000L);

        assertEquals(9.0, result.awardedXp(), EPSILON);
        assertEquals(0.0, result.state().qualifiedSeconds(), EPSILON);
        assertEquals(9.0, result.state().windowAwardedXp(), EPSILON);
    }

    @Test
    void largeClockJumpContributesAtMostOneTickOfQualifiedTime() throws Exception {
        AvatarFlightExperienceService service = new AvatarFlightExperienceService();

        AvatarFlightExperienceService.Result result = service.tick(
                new AvatarFlightExperienceService.State(0.0, 0.0, 1_000L, 1_000L),
                settings(0.15, 10.0, 9.0), true, 31_000L);

        assertEquals(0.0, result.awardedXp(), EPSILON);
        assertEquals(0.25, result.state().qualifiedSeconds(), EPSILON);
    }

    private static AvatarFlightExperienceService.Result tickQualified(AvatarFlightExperienceService service,
                                                                       AvatarFlightExperienceService.State state,
                                                                       TwLevelingConfig.FlightXpSourceSettings settings,
                                                                       long firstSampleAtMs,
                                                                       int ticks) {
        AvatarFlightExperienceService.Result result = null;
        for (int tick = 1; tick <= ticks; tick++) {
            result = service.tick(state, settings, true, firstSampleAtMs + tick * 250L);
            state = result.state();
        }
        return result;
    }

    private static TwLevelingConfig.FlightXpSourceSettings settings(double xpPerQualifiedSecond,
                                                                      double awardIntervalSeconds,
                                                                      double maxXpPerMinute) throws Exception {
        return settings(true, xpPerQualifiedSecond, awardIntervalSeconds, maxXpPerMinute);
    }

    private static TwLevelingConfig.FlightXpSourceSettings settings(boolean enabled,
                                                                      double xpPerQualifiedSecond,
                                                                      double awardIntervalSeconds,
                                                                      double maxXpPerMinute) throws Exception {
        TwLevelingConfig.FlightXpSourceSettings settings = new TwLevelingConfig.FlightXpSourceSettings();
        setField(settings, "enabled", enabled);
        setField(settings, "xpPerQualifiedSecond", xpPerQualifiedSecond);
        setField(settings, "awardIntervalSeconds", awardIntervalSeconds);
        setField(settings, "maxXpPerMinute", maxXpPerMinute);
        return settings;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
