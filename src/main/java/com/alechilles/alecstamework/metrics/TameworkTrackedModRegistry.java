package com.alechilles.alecstamework.metrics;

import java.util.Map;

/**
 * Static mapping between known Alec mod IDs and their HStats plugin UUIDs.
 */
public final class TameworkTrackedModRegistry {

    private static final Map<String, String> HSTATS_UUID_BY_MOD_ID = Map.of(
            "Alechilles:Alec's Cats!",
            "fba66910-eab2-4721-b8e5-a90b6f493887",
            "Alechilles:Alec's Animal Husbandry!",
            "56926736-9f0c-4d23-a671-a3f46a16b621",
            "Alechilles:Alec's Nametags!",
            "4f1d847d-57fe-4aef-8042-2e77690e2a4a"
    );

    private TameworkTrackedModRegistry() {
    }

    public static String getHStatsUuid(String modId) {
        if (modId == null) {
            return null;
        }
        return HSTATS_UUID_BY_MOD_ID.get(modId);
    }
}
