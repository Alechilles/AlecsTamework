package com.alechilles.alecstamework.debug;

import com.alechilles.alecstamework.api.CommandLinksApi;
import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.DiagnosticsApi;
import com.alechilles.alecstamework.api.InteractionExtensionApi;
import com.alechilles.alecstamework.api.NpcProfilesApi;
import com.alechilles.alecstamework.api.PolicyApi;
import com.alechilles.alecstamework.api.ProfileDataApi;
import com.alechilles.alecstamework.api.ProgressionApi;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.api.TameworkConfigReadApi;
import com.alechilles.alecstamework.api.TameworkEventsApi;
import com.alechilles.alecstamework.api.TraitEffectApi;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionXpEventDebugLogServiceTest {
    @Test
    void enabledServiceLogsCompanionXpEventsFromPublicEventBus() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<String> logs = new ArrayList<>();
        CompanionXpEventDebugLogService service = new CompanionXpEventDebugLogService(
                () -> new FakeApi(bus, EnumSet.of(TameworkApiCapability.EVENTS, TameworkApiCapability.COMPANION_XP_EVENTS)),
                logs::add
        );

        assertTrue(service.setEnabled(true));
        bus.emitCompanionXpAwarded(event(CompanionXpSource.FEED, 12.5));

        assertEquals(1L, service.getEventCount());
        assertTrue(logs.stream().anyMatch(line -> line.contains("enabled through TameworkApi.events()")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("[Tamework XP Event Debug] hit=1")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("source=FEED")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("awardedXp=12.500")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("leveledUp=true")));

        service.setEnabled(false);
        bus.emitCompanionXpAwarded(event(CompanionXpSource.HARVEST, 4.0));

        assertEquals(1L, service.getEventCount());
        assertTrue(logs.stream().anyMatch(line -> line.contains("disabled after 1 event(s)")));
    }

    @Test
    void enableFailsWhenCompanionXpEventsCapabilityIsUnavailable() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<String> logs = new ArrayList<>();
        CompanionXpEventDebugLogService service = new CompanionXpEventDebugLogService(
                () -> new FakeApi(bus, EnumSet.of(TameworkApiCapability.EVENTS)),
                logs::add
        );

        assertFalse(service.setEnabled(true));
        bus.emitCompanionXpAwarded(event(CompanionXpSource.FEED, 1.0));

        assertEquals(0L, service.getEventCount());
        assertTrue(logs.stream().anyMatch(line -> line.contains("could not be enabled")));
    }

    @Test
    void harvestDropDiagnosticsOnlyLogWhileEnabled() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<String> logs = new ArrayList<>();
        CompanionXpEventDebugLogService service = new CompanionXpEventDebugLogService(
                () -> new FakeApi(bus, EnumSet.of(TameworkApiCapability.EVENTS, TameworkApiCapability.COMPANION_XP_EVENTS)),
                logs::add
        );

        service.logHarvestDropAttempt("award applied=false reason=not-tamed-or-owned");
        assertTrue(logs.isEmpty(), "Harvest drop diagnostics should stay quiet until the debug toggle is enabled.");

        assertTrue(service.setEnabled(true));
        service.logHarvestDropAttempt("award applied=false reason=not-tamed-or-owned");

        assertTrue(logs.stream().anyMatch(line -> line.contains("[Tamework XP Event Debug] harvestDrop")));
        assertTrue(logs.stream().anyMatch(line -> line.contains("reason=not-tamed-or-owned")));
    }

    @Test
    void enabledServiceLabelsAvatarFlightXpEvents() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<String> logs = new ArrayList<>();
        CompanionXpEventDebugLogService service = new CompanionXpEventDebugLogService(
                () -> new FakeApi(bus, EnumSet.of(TameworkApiCapability.EVENTS, TameworkApiCapability.COMPANION_XP_EVENTS)),
                logs::add
        );

        assertTrue(service.setEnabled(true));
        bus.emitCompanionXpAwarded(event(CompanionXpSource.AVATAR_FLIGHT, 1.5));

        assertEquals(1L, service.getEventCount());
        assertTrue(logs.stream().anyMatch(line -> line.contains("source=AVATAR_FLIGHT")));
    }

    private static CompanionXpAwardedEvent event(CompanionXpSource source, double awardedXp) {
        return new CompanionXpAwardedEvent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Set.of("tool-alpha"),
                "Mob_Tamework_Example",
                "Tamework_Leveling_Example",
                source,
                awardedXp,
                1,
                2,
                70.0,
                82.5,
                70.0,
                7.5,
                75.0,
                20,
                false,
                true,
                123L,
                456L
        );
    }

    private record FakeApi(TameworkEventsApi events,
                           EnumSet<TameworkApiCapability> capabilities) implements TameworkApi {
        @Override
        public String getApiVersion() {
            return "0.6.0";
        }

        @Override
        public EnumSet<TameworkApiCapability> getCapabilities() {
            return capabilities.clone();
        }

        @Override
        public NpcProfilesApi profiles() {
            return null;
        }

        @Override
        public CommandLinksApi commandLinks() {
            return null;
        }

        @Override
        public ProgressionApi progression() {
            return null;
        }

        @Override
        public PolicyApi policies() {
            return null;
        }

        @Override
        public InteractionExtensionApi interactionExtensions() {
            return null;
        }

        @Override
        public TraitEffectApi traitEffects() {
            return null;
        }

        @Override
        public ProfileDataApi profileData() {
            return null;
        }

        @Override
        public TameworkConfigReadApi configs() {
            return null;
        }

        @Override
        public DiagnosticsApi diagnostics() {
            return null;
        }
    }
}
