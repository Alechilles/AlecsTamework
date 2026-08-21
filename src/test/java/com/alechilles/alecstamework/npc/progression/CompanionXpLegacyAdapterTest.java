package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.api.CompanionXpAwardedEvent;
import com.alechilles.alecstamework.api.CompanionXpSource;
import com.alechilles.alecstamework.api.internal.TameworkEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanionXpLegacyAdapterTest {
    private static final UUID NPC = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void mapsEveryLegacyFieldForEachExistingXpSource() throws Exception {
        TameworkEventBus legacyBus = new TameworkEventBus(null);
        List<CompanionXpAwardedEvent> events = new ArrayList<>();
        legacyBus.subscribe(CompanionXpAwardedEvent.class, events::add);
        CompanionProgressionSignalBus signals = new CompanionProgressionSignalBus();

        try (CompanionXpLegacyAdapter ignored = new CompanionXpLegacyAdapter(signals, legacyBus)) {
            int index = 0;
            for (CompanionXpSource source : List.of(
                    CompanionXpSource.FEED,
                    CompanionXpSource.HARVEST,
                    CompanionXpSource.BREEDING,
                    CompanionXpSource.COMBAT_DAMAGE_DEALT,
                    CompanionXpSource.COMBAT_DAMAGE_TAKEN,
                    CompanionXpSource.CUSTOM,
                    CompanionXpSource.AVATAR_FLIGHT,
                    CompanionXpSource.SUMMONED)) {
                signals.publish(transition(source, index++));
            }
        }

        assertEquals(8, events.size());
        for (int index = 0; index < events.size(); index++) {
            CompanionXpAwardedEvent event = events.get(index);
            CompanionXpSource source = List.of(
                    CompanionXpSource.FEED,
                    CompanionXpSource.HARVEST,
                    CompanionXpSource.BREEDING,
                    CompanionXpSource.COMBAT_DAMAGE_DEALT,
                    CompanionXpSource.COMBAT_DAMAGE_TAKEN,
                    CompanionXpSource.CUSTOM,
                    CompanionXpSource.AVATAR_FLIGHT,
                    CompanionXpSource.SUMMONED).get(index);
            assertEquals(NPC, event.npcUuid());
            assertEquals(OWNER, event.ownerUuid());
            assertEquals(Set.of("tool-a", "tool-b"), event.toolIds());
            assertEquals("Mob_Test", event.roleId());
            assertEquals("Leveling_Test", event.levelingConfigId());
            assertEquals(source, event.source());
            assertEquals(10.5 + index, event.awardedXp());
            assertEquals(2 + index, event.previousLevel());
            assertEquals(3 + index, event.currentLevel());
            assertEquals(20.5 + index, event.previousTotalXp());
            assertEquals(31.0 + index, event.currentTotalXp());
            assertEquals(4.5 + index, event.previousCurrentXp());
            assertEquals(6.0 + index, event.currentXp());
            assertEquals(44.0 + index, event.nextLevelXp());
            assertEquals(20 + index, event.maxLevel());
            assertEquals(index % 2 == 0, event.atMaxLevel());
            assertEquals(index % 2 != 0, event.leveledUp());
            assertEquals(1000L + index, event.occurredAtMs());
            assertEquals(2000L + index, event.emittedAtMs());
        }
    }

    @Test
    void doesNotMapTransitionsWithoutLegacyInterest() throws Exception {
        TameworkEventBus legacyBus = new TameworkEventBus(null);
        CompanionProgressionSignalBus signals = new CompanionProgressionSignalBus();
        try (CompanionXpLegacyAdapter ignored = new CompanionXpLegacyAdapter(signals, legacyBus)) {
            signals.publish(transition(CompanionXpSource.CUSTOM, 0));
        }
        assertFalse(legacyBus.hasCompanionXpSubscribers());
        assertEquals(0L, legacyBus.deliveryDiagnostics().dispatchedEvents());
    }

    private static CompanionXpTransition transition(CompanionXpSource source, int index) {
        return new CompanionXpTransition(
                NPC,
                OWNER,
                Set.of("tool-a", "tool-b"),
                "Mob_Test",
                "Leveling_Test",
                source,
                10.5 + index,
                2 + index,
                3 + index,
                20.5 + index,
                31.0 + index,
                4.5 + index,
                6.0 + index,
                44.0 + index,
                20 + index,
                index % 2 == 0,
                index % 2 != 0,
                1000L + index,
                2000L + index
        );
    }
}
