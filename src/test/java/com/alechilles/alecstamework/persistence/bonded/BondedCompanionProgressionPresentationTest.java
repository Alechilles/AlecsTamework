package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ensures new level-one companions retain their configured talent affordance. */
class BondedCompanionProgressionPresentationTest {
    @Test
    void addsConfiguredDefaultsWhenTheSnapshotPredatesRuntimeBootstrap() {
        Map<String, String> fields = BondedCompanionProgressionPresentation.enrich(
                Map.of(), "Tamed_NordicDrake",
                ignored -> new BondedCompanionProgressionPresentation.RoleConfigs(
                        "HyDragonNordicDrake", "HyDragonNordicDrake"));

        assertEquals("1", fields.get("level"));
        assertEquals("HyDragonNordicDrake", fields.get("levelingConfigId"));
        assertEquals("HyDragonNordicDrake", fields.get("talentConfigId"));
        assertEquals("0", fields.get("talentSpentPoints"));
    }

    @Test
    void preservesDurableProgressionValuesOverRoleDefaults() {
        Map<String, String> fields = BondedCompanionProgressionPresentation.enrich(
                Map.of("level", "12", "levelingConfigId", "saved-leveling",
                        "talentConfigId", "saved-talents", "talentSpentPoints", "5"),
                "Tamed_NordicDrake",
                ignored -> new BondedCompanionProgressionPresentation.RoleConfigs(
                        "HyDragonNordicDrake", "HyDragonNordicDrake"));

        assertEquals("12", fields.get("level"));
        assertEquals("saved-leveling", fields.get("levelingConfigId"));
        assertEquals("saved-talents", fields.get("talentConfigId"));
        assertEquals("5", fields.get("talentSpentPoints"));
    }
}
