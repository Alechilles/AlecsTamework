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
}
