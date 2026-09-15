package com.alechilles.alecstamework.items.components;

import com.hypixel.hytale.codec.ExtraInfo;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Protects higher-capacity trough saves from truncation and old trough saves from changed defaults. */
class TameworkFeedTroughWaterChargesComponentTest {
    @Test
    void savesConfiguredCapacityAndRemainingWaterRegardlessOfInputFieldOrder() {
        TameworkFeedTroughWaterChargesComponent component = TameworkFeedTroughWaterChargesComponent.CODEC.decode(
                BsonDocument.parse("""
                        {"WaterCharges": 679, "BaseBlockId": "Pack_Trough", "FoodCapacity": 20, "MaxWaterCharges": 800}
                        """), new ExtraInfo());
        TameworkFeedTroughWaterChargesComponent restored = TameworkFeedTroughWaterChargesComponent.CODEC.decode(
                TameworkFeedTroughWaterChargesComponent.CODEC.encode(component.clone(), new ExtraInfo()), new ExtraInfo());
        assertEquals("Pack_Trough", restored.getBaseBlockId());
        assertEquals(800, restored.getMaxWaterCharges());
        assertEquals(20, restored.getFoodCapacity());
        assertEquals(679, restored.getWaterCharges());
    }

    @Test
    void loadsLegacyAndOutOfRangeSavedWaterWithCompatibleBounds() {
        TameworkFeedTroughWaterChargesComponent legacy = TameworkFeedTroughWaterChargesComponent.CODEC.decode(
                BsonDocument.parse("{\"WaterCharges\": 127}"), new ExtraInfo());
        assertEquals("Tw_Feed_Trough", legacy.getBaseBlockId());
        assertEquals(5, legacy.getFoodCapacity());
        assertEquals(200, legacy.getMaxWaterCharges());
        assertEquals(127, legacy.getWaterCharges());
        TameworkFeedTroughWaterChargesComponent overflow = TameworkFeedTroughWaterChargesComponent.CODEC.decode(
                BsonDocument.parse("{\"MaxWaterCharges\": 320, \"WaterCharges\": 900}"), new ExtraInfo());
        assertEquals(320, overflow.getWaterCharges());
        overflow.setWaterCharges(-1);
        assertEquals(0, overflow.getWaterCharges());
    }
}
