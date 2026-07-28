package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.ConfigReloadedEvent;
import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.NpcCapturedEvent;
import com.alechilles.alecstamework.api.NpcDeathRecordedEvent;
import com.alechilles.alecstamework.api.NpcLostRecordedEvent;
import com.alechilles.alecstamework.api.NpcProfileChangedEvent;
import com.alechilles.alecstamework.api.ProfileChangeType;
import com.alechilles.alecstamework.api.TameworkConfigFamily;
import com.alechilles.alecstamework.api.TameworkEvent;
import com.alechilles.alecstamework.api.Vector3View;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkEventBusTest {
    @Test
    void subscribeDeliversProfileEventsAndCloseStopsDelivery() throws Exception {
        TameworkEventBus bus = new TameworkEventBus(null);
        AtomicInteger deliveries = new AtomicInteger();
        AutoCloseable subscription = bus.subscribe(NpcProfileChangedEvent.class, event -> {
            deliveries.incrementAndGet();
            assertTrue(event.changeTypes().contains(ProfileChangeType.CREATED));
            assertEquals("profile-alpha", event.profileId());
        });

        bus.publishProfileChanged(
                null,
                profile(
                        "profile-alpha",
                        UUID.randomUUID(),
                        "Display A",
                        "Custom A"
                ),
                System.currentTimeMillis()
        );
        assertEquals(1, deliveries.get());

        subscription.close();
        bus.publishProfileChanged(
                profile(
                        "profile-alpha",
                        UUID.randomUUID(),
                        "Display A",
                        "Custom A"
                ),
                profile(
                        "profile-alpha",
                        UUID.randomUUID(),
                        "Display B",
                        "Custom A"
                ),
                System.currentTimeMillis()
        );
        assertEquals(1, deliveries.get());
    }

    @Test
    void listenerExceptionsDoNotBreakOtherSubscribers() {
        TameworkEventBus bus = new TameworkEventBus(null);
        AtomicInteger successfulDeliveries = new AtomicInteger();
        bus.subscribe(TameworkEvent.class, event -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(TameworkEvent.class, event -> successfulDeliveries.incrementAndGet());

        bus.emitConfigReload(TameworkConfigFamily.GLOBAL, Set.of("global/default"));
        assertEquals(1, successfulDeliveries.get());

        TameworkEventBus.DeliveryDiagnostics diagnostics = bus.deliveryDiagnostics();
        assertEquals(1L, diagnostics.dispatchedEvents());
        assertEquals(2L, diagnostics.deliveryAttempts());
        assertEquals(1L, diagnostics.deliveredListeners());
        assertEquals(1L, diagnostics.listenerFailuresSinceBoot());
        assertEquals("ConfigReloadedEvent", diagnostics.lastFailedEventType());
    }

    @Test
    void emitsCompanionXpAwardedEventsWithDefensiveToolIdCopy() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<TameworkEvent> allEvents = new ArrayList<>();
        List<CompanionXpAwardedEvent> xpEvents = new ArrayList<>();
        bus.subscribe(TameworkEvent.class, allEvents::add);
        bus.subscribe(CompanionXpAwardedEvent.class, xpEvents::add);

        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        toolIds.add("tool-a");
        toolIds.add("tool-b");
        CompanionXpAwardedEvent event = new CompanionXpAwardedEvent(
                npcUuid,
                ownerUuid,
                toolIds,
                "Mob_Test",
                "Leveling_Test",
                CompanionXpSource.FEED,
                8.0,
                1,
                2,
                70.0,
                78.0,
                70.0,
                3.0,
                75.0,
                20,
                false,
                true,
                123L,
                456L
        );
        toolIds.add("mutated-after-create");

        bus.emitCompanionXpAwarded(event);

        assertEquals(1, allEvents.size());
        assertEquals(1, xpEvents.size());
        CompanionXpAwardedEvent delivered = xpEvents.get(0);
        assertEquals(npcUuid, delivered.npcUuid());
        assertEquals(ownerUuid, delivered.ownerUuid());
        assertEquals(Set.of("tool-a", "tool-b"), delivered.toolIds());
        assertEquals(CompanionXpSource.FEED, delivered.source());
        assertEquals(8.0, delivered.awardedXp());
        assertEquals(1, delivered.previousLevel());
        assertEquals(2, delivered.currentLevel());
        assertTrue(delivered.leveledUp());
    }

    @Test
    void emitsCaptureDeathLostAndConfigEvents() {
        TameworkEventBus bus = new TameworkEventBus(null);
        List<TameworkEvent> events = new ArrayList<>();
        bus.subscribe(TameworkEvent.class, events::add);

        UUID npcUuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        var profile = profile(
                "profile-beta", npcUuid, "Display B", "Custom B"
        );
        bus.publishCaptureRecorded(new NpcCapturedEvent(
                profile,
                npcUuid,
                ownerUuid,
                Set.of("tool-a", "tool-b"),
                "Mob_Test",
                "Display B",
                new Vector3View(1.0, 2.0, 3.0),
                new Vector3View(4.0, 5.0, 6.0),
                123L,
                124L
        ));
        bus.publishDeathRecorded(new NpcDeathRecordedEvent(
                profile,
                npcUuid,
                ownerUuid,
                "Owner B",
                Set.of("tool-a", "tool-b"),
                "Mob_Test",
                "Display B",
                "Custom B",
                true,
                new Vector3View(1.0, 2.0, 3.0),
                new Vector3View(4.0, 5.0, 6.0),
                234L,
                345L,
                346L
        ));
        bus.publishLostRecorded(new NpcLostRecordedEvent(
                profile,
                npcUuid,
                new Vector3View(7.0, 8.0, 9.0),
                new Vector3View(4.0, 5.0, 6.0),
                456L,
                567L,
                2,
                568L
        ));
        bus.emitConfigReload(TameworkConfigFamily.INTERACTION, Set.of("interaction/farm"));

        assertEquals(4, events.size());
        NpcCapturedEvent capturedEvent = assertInstanceOf(NpcCapturedEvent.class, events.get(0));
        assertEquals(Set.of("tool-a", "tool-b"), capturedEvent.toolIds());
        assertNotNull(capturedEvent.profile());

        NpcDeathRecordedEvent deathEvent = assertInstanceOf(NpcDeathRecordedEvent.class, events.get(1));
        assertEquals("Owner B", deathEvent.ownerName());
        assertTrue(deathEvent.tamed());

        NpcLostRecordedEvent lostEvent = assertInstanceOf(NpcLostRecordedEvent.class, events.get(2));
        assertEquals(2, lostEvent.relocationRetryAttempts());

        ConfigReloadedEvent configEvent = assertInstanceOf(ConfigReloadedEvent.class, events.get(3));
        assertEquals(TameworkConfigFamily.INTERACTION, configEvent.family());
        assertFalse(configEvent.changedIds().isEmpty());
    }

    private com.alechilles.alecstamework.api.NpcProfileView profile(
            String profileId,
            UUID npcUuid,
            String displayName,
            String customName
    ) {
        return new com.alechilles.alecstamework.api.NpcProfileView(
                profileId,
                npcUuid,
                UUID.randomUUID(),
                "Owner",
                "Mob_Test",
                displayName,
                customName,
                true,
                "coop-a",
                1,
                Set.of("tool-a"),
                Set.of("capture"),
                System.currentTimeMillis()
        );
    }
}

