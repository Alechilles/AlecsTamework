package com.alechilles.alecstamework.companion.coop;

import com.hypixel.hytale.codec.ExtraInfo;
import java.util.List;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkCoopSlotsComponentTest {

    @Test
    void slotsSurviveASaveAndAnEntryHoldingBothKindsIsDropped() {
        UUID profileId = UUID.randomUUID();
        BsonDocument entity = new BsonDocument("Components", new BsonDocument("Name", new BsonString("Hen")));
        TameworkCoopSlotsComponent.Slot companion = new TameworkCoopSlotsComponent.Slot(0, profileId, 4, null, 9_000L);
        TameworkCoopSlotsComponent.Slot unowned = TameworkCoopSlotsComponent.Slot.unowned(2, entity);
        TameworkCoopSlotsComponent component = new TameworkCoopSlotsComponent().with(unowned).with(companion);

        BsonDocument saved = TameworkCoopSlotsComponent.CODEC.encode(component, new ExtraInfo());
        TameworkCoopSlotsComponent loaded = TameworkCoopSlotsComponent.CODEC.decode(saved, new ExtraInfo());
        assertEquals(List.of(companion, unowned), loaded.slots());

        BsonDocument damaged = saved.clone();
        damaged.getArray("Slots").get(0).asDocument().put("UnownedEntity", entity);
        TameworkCoopSlotsComponent repaired = TameworkCoopSlotsComponent.CODEC.decode(damaged, new ExtraInfo());
        assertEquals(List.of(unowned), repaired.slots());
    }
}
