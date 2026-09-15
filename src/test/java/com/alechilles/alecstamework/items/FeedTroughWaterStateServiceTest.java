package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies feed trough water charge breakpoints and block-id inference logic. */
class FeedTroughWaterStateServiceTest {

    @Test
    void resolvesCanonicalBlockIdsForChargeBreakpoints() {
        assertEquals("Tw_Feed_Trough", FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(0));
        assertEquals("Tw_Feed_Trough_State_Water_State_Full",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(200));
        assertEquals("Tw_Feed_Trough_State_Water_State_90",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(199));
        assertEquals("Tw_Feed_Trough_State_Water_State_90",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(180));
        assertEquals("Tw_Feed_Trough_State_Water_State_80",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(160));
        assertEquals("Tw_Feed_Trough_State_Water_State_10",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(1));
    }

    @Test
    void infersChargesFromWaterBlockIds() {
        assertEquals(200, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough_State_Water"));
        assertEquals(200, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough_State_Water_State_Full"));
        assertEquals(180, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough_State_Water_State_90"));
        assertEquals(160, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough_State_Water_State_80"));
        assertEquals(20, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough_State_Water_State_10"));
        assertEquals(0, FeedTroughWaterStateService.inferChargesFromWaterBlockId("Tw_Feed_Trough"));
    }

    @Test
    void identifiesWaterTroughIds() {
        assertTrue(FeedTroughWaterStateService.isWaterTroughBlockId("Tw_Feed_Trough_State_Water"));
        assertTrue(FeedTroughWaterStateService.isWaterTroughBlockId("Tw_Feed_Trough_State_Water_State_60"));
        assertFalse(FeedTroughWaterStateService.isWaterTroughBlockId("Tw_Feed_Trough"));
        assertFalse(FeedTroughWaterStateService.isWaterTroughBlockId("Tw_Feed_Trough_State_Food_State_60"));
    }

    @Test
    void resolvesLegacyFoodWithoutComponentAndRejectsUnrelatedWaterStates() {
        var empty = FeedTroughWaterStateService.resolveVariant(null, null, 0, 0, 0, "Tw_Feed_Trough");
        var food = FeedTroughWaterStateService.resolveVariant(null, null, 0, 0, 0, "Tw_Feed_Trough_State_Food_State_50");
        assertEquals(empty, food);
        assertEquals("Tw_Feed_Trough_State_Food_State_Full",
                FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(food, 100));
        assertEquals(null, FeedTroughWaterStateService.resolveVariant(null, null, 0, 0, 0, "Other_State_Water"));
        assertFalse(FeedTroughWaterStateService.isWaterStateOf("Other_State_Water_State_Full", "Pack_Trough"));
        assertEquals(0, FeedTroughWaterStateService.inferChargesFromWaterBlockId(
                "Pack_Trough_State_Water_State_Empty", "Pack_Trough", 800));
        assertTrue(FeedTroughWaterStateService.isWaterStateOf("Pack_Trough_State_Water_State_Full", "Pack_Trough"));
    }
    @Test
    void resolvesConfiguredVariantStatesAtThatVariantsCapacity() {
        assertEquals(
                "RH_Feed_Trough_Tier_4_State_Water_State_Full",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(560, "RH_Feed_Trough_Tier_4", 560)
        );
        assertEquals(
                "RH_Feed_Trough_Tier_4_State_Water_State_90",
                FeedTroughWaterStateService.resolveCanonicalWaterBlockIdForCharges(559, "RH_Feed_Trough_Tier_4", 560)
        );
        assertEquals(
                448,
                FeedTroughWaterStateService.inferChargesFromWaterBlockId(
                        "RH_Feed_Trough_Tier_4_State_Water_State_80", "RH_Feed_Trough_Tier_4", 560
                )
        );
        FeedTroughWaterStateService.TroughVariant variant =
                new FeedTroughWaterStateService.TroughVariant("RH_Feed_Trough_Tier_4", 560, 14);
        assertEquals(
                "RH_Feed_Trough_Tier_4_State_Food_State_50",
                FeedTroughWaterStateService.resolveCanonicalFoodBlockIdForPercent(variant, 50)
        );
    }
}
