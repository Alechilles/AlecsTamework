package com.alechilles.alecstamework.metrics;

import java.util.Map;

/**
 * Static mapping between known Alec mod IDs and their HStats plugin UUIDs.
 */
public final class TameworkTrackedModRegistry {

    private static final Map<String, TrackedMod> TRACKED_MODS_BY_MOD_ID = Map.of(
            "Alechilles:Alec's Cats!",
            requiresTamework("fba66910-eab2-4721-b8e5-a90b6f493887"),
            "Alechilles:Alec's Animal Husbandry!",
            requiresTamework("56926736-9f0c-4d23-a671-a3f46a16b621"),
            "Alechilles:Alec's Nametags!",
            requiresTamework("4f1d847d-57fe-4aef-8042-2e77690e2a4a"),
            "Alechilles:Alec's Coops!",
            requiresTamework("3ce09431-552e-4279-90ca-e0735bd9763b"),
            "excellynt:Celly's Baby Animals",
            requiresTamework("a50d1c7e-4970-476e-a3b2-5aa81edbee86"),
            "excellynt:Celly's Wildlife Skins",
            optionalTamework("517fcdc7-b3d2-4b08-9b8a-bcebbe049b4a")
    );

    private TameworkTrackedModRegistry() {
    }

    public static String getHStatsUuid(String modId) {
        TrackedMod trackedMod = getTrackedMod(modId);
        return trackedMod == null ? null : trackedMod.hStatsUuid();
    }

    public static boolean requiresTameworkDependency(String modId) {
        TrackedMod trackedMod = getTrackedMod(modId);
        return trackedMod == null || trackedMod.requiresTameworkDependency();
    }

    private static TrackedMod getTrackedMod(String modId) {
        if (modId == null) {
            return null;
        }
        return TRACKED_MODS_BY_MOD_ID.get(modId);
    }

    private static TrackedMod requiresTamework(String hStatsUuid) {
        return new TrackedMod(hStatsUuid, true);
    }

    private static TrackedMod optionalTamework(String hStatsUuid) {
        return new TrackedMod(hStatsUuid, false);
    }

    private record TrackedMod(String hStatsUuid, boolean requiresTameworkDependency) {
    }
}
