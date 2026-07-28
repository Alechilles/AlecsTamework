package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Ensures stored-companion summon cooldowns do not retain revive terminology. */
class BondedCompanionSummonCooldownNamingTest {
    private static final Path MAIN = Path.of("src/main/java/com/alechilles/"
            + "alecstamework");
    private static final Path BONDED_SCHEMA = Path.of("src/main/resources/"
            + "persistence/bonded");

    @Test
    void bondedProfileApiAndFreshSchemaUseOnlySummonCooldownNames()
            throws Exception {
        String record = Files.readString(MAIN.resolve(
                "persistence/bonded/BondedCompanionRecord.java"),
                StandardCharsets.UTF_8);
        String row = Files.readString(MAIN.resolve(
                "persistence/adapter/sqlite/SqliteBondedCompanionProfileRow.java"),
                StandardCharsets.UTF_8);
        String v1 = Files.readString(BONDED_SCHEMA.resolve("v1.sql"),
                StandardCharsets.UTF_8);

        assertTrue(record.contains("summonCooldownUntilMs"));
        assertTrue(row.contains("summonCooldownUntilMs"));
        assertTrue(v1.contains("summon_cooldown_until_ms"));
        String legacyJavaName = "revive" + "CooldownUntilMs";
        String legacyColumnName = "revive_" + "cooldown_until_ms";
        assertFalse(record.contains(legacyJavaName));
        assertFalse(row.contains(legacyJavaName));
        assertFalse(v1.contains(legacyColumnName));
    }
}
