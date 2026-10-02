package com.alechilles.alecstamework.companion.bonded;

import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.companion.index.StoredReason;
import com.alechilles.alecstamework.localization.LocalizedText;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BondedCompanionNamesTest {
    /** Any key the language files hold stands in for another mod's role name key. */
    private static final String KNOWN_KEY = "tamework.ui.shared.item";
    private static final String UNKNOWN_KEY = "server.npcRoles.Tamed_RockDrakeT1.name";

    private static CompanionRecord record(String displayName, String nameKey) {
        return CompanionRecord.builder(UUID.randomUUID(), "Tamed_RockDrakeT1",
                        CompanionLocation.stored(StoredReason.BONDED))
                .displayName(displayName)
                .summary(new CompanionSummary(null, nameKey, "Tamed_RockDrakeT1", null,
                        0f, 0f, null, 0.0, null, 0.0, 0.0, false, false, 0L, 0L, 0L, 0L,
                        null, 0, 0.0, 0.0, 0, Map.of(), 0L, 0L, 0L, null, null, null))
                .build();
    }

    @Test
    void aGivenNameWinsThenTheRoleNameInTheViewersLanguageThenTheGenericName() {
        assertEquals("Ember", BondedCompanionNames.displayName(record("Ember", KNOWN_KEY), "en-US"));

        for (String language : new String[] {"en-US", "de-DE"}) {
            String roleName = LocalizedText.resolve(language, KNOWN_KEY);
            assertNotEquals(KNOWN_KEY, roleName);
            assertEquals(roleName, BondedCompanionNames.displayName(record(null, KNOWN_KEY), language));
        }

        // A key no language file holds is never shown raw.
        String generic = BondedCompanionNames.displayName(record(null, null), "en-US");
        assertEquals(generic, BondedCompanionNames.displayName(record(null, UNKNOWN_KEY), "en-US"));
        assertNotEquals(UNKNOWN_KEY, generic);
        assertNotEquals(LocalizedText.resolve("en-US", KNOWN_KEY), generic);
    }
}
