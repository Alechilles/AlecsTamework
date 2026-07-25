package com.alechilles.alecstamework.persistence.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for public role labels that previously looked like custom names. */
class PublicImportDisplayNameNormalizerTest {

    @Test
    void genericBaseRoleDisplayIsSuppressed() {
        assertNull(PublicImportDisplayNameNormalizer.normalize(profile(
                "Wolf_Black",
                "Tamed_Wolf_Black",
                "{\"owner_name\":\"Alec1\",\"tamed\":true}"
        )));
        assertNull(PublicImportDisplayNameNormalizer.normalize(profile(
                "Deer_Stag",
                "Tamed_Deer_Stag",
                "{}"
        )));
    }

    @Test
    void explicitLegacyCustomNameWinsOverGenericDisplay() {
        assertEquals(
                "Fenrir",
                PublicImportDisplayNameNormalizer.normalize(profile(
                        "Wolf_Black",
                        "Tamed_Wolf_Black",
                        "{\"custom_name\":\"Fenrir\"}"
                ))
        );
    }

    private LegacyPublicData.Profile profile(
            String displayName,
            String roleId,
            String stateJson
    ) {
        return new LegacyPublicData.Profile(
                "90000000-0000-0000-0000-000000000001",
                "90000000-0000-0000-0000-000000000002",
                null,
                displayName,
                roleId,
                stateJson,
                null,
                null,
                -100L,
                -90L,
                -80L
        );
    }
}
