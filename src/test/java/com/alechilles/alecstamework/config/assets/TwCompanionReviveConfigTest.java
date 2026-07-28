package com.alechilles.alecstamework.config.assets;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.exception.CodecException;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for paid-revival config decoding and fallback. */
class TwCompanionReviveConfigTest {
    @Test
    void nestedReviveIsAuthoritativeOverLegacyFields() {
        TwCompanionConfig config = decode("""
                {
                  "Command": {
                    "Revive": {
                      "Enabled": true,
                      "GameplayCooldownMs": 0,
                      "Costs": [
                        { "ItemId": "Life_Essence", "Quantity": 2 },
                        { "ItemId": "Gold_Bar", "Quantity": 7 }
                      ],
                      "InsufficientCostMessage": "  missing.revival.cost  "
                    },
                    "DeadRespawnEnabled": false,
                    "DeadRespawnCooldownMs": 45000
                  }
                }
                """);

        TwCompanionReviveSettings revive =
                config.getCommand().getRevive();
        assertFalse(config.getCommand().isDeadRespawnEnabled());
        assertEquals(45_000, config.getCommand().getDeadRespawnCooldownMs());
        assertTrue(revive.isEnabled());
        assertEquals(0L, revive.getGameplayCooldownMs());
        assertEquals(2, revive.getCosts().length);
        assertEquals("Life_Essence", revive.getCosts()[0].getItemId());
        assertEquals("Gold_Bar", revive.getCosts()[1].getItemId());
        assertEquals(
                "missing.revival.cost",
                revive.getInsufficientCostMessage()
        );
    }

    @Test
    void legacyFieldsSeedReviveWhenNestedSectionIsAbsent() {
        TwCompanionConfig config = decode("""
                {
                  "Command": {
                    "DeadRespawnEnabled": false,
                    "DeadRespawnCooldownMins": 2.5
                  }
                }
                """);

        TwCompanionReviveSettings revive =
                config.getCommand().getRevive();
        assertFalse(revive.isEnabled());
        assertEquals(150_000L, revive.getGameplayCooldownMs());
    }

    @Test
    void explicitEmptyCostRecipeIsValidAndLongCooldownIsNotNarrowed() {
        TwCompanionConfig config = decode("""
                {
                  "Command": {
                    "Revive": {
                      "GameplayCooldownMs": 9223372036854775807,
                      "Costs": []
                    }
                  }
                }
                """);

        TwCompanionReviveSettings revive =
                config.getCommand().getRevive();
        assertEquals(Long.MAX_VALUE, revive.getGameplayCooldownMs());
        assertEquals(0, revive.getCosts().length);
    }

    @Test
    void negativeGameplayDurationIsRejectedWithoutTimestampClamping() {
        assertThrows(
                CodecException.class,
                () -> decode("""
                        {
                          "Command": {
                            "Revive": {
                              "GameplayCooldownMs": -1
                            }
                          }
                        }
                        """)
        );

        TwCompanionReviveSettings revive = decode("""
                {
                  "Command": {
                    "Revive": {
                      "GameplayCooldownMs": 500
                    }
                  }
                }
                """).getCommand().getRevive();
        long signedDeathWorldTimeMs = -2_000L;
        assertEquals(
                -1_500L,
                signedDeathWorldTimeMs + revive.getGameplayCooldownMs()
        );
    }

    @Test
    void explicitNestedCostsReplaceWhileMissingFieldsInherit() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "Revive": {
                      "Enabled": false,
                      "GameplayCooldownMs": 120000,
                      "Costs": [
                        { "ItemId": "Life_Essence", "Quantity": 3 }
                      ],
                      "InsufficientCostMessage": "parent.message"
                    }
                  }
                }
                """);
        TwCompanionConfig child = decode("""
                {
                  "Command": {
                    "Revive": {
                      "Enabled": true,
                      "Costs": []
                    }
                  }
                }
                """);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of(
                        "Command",
                        Set.of(
                                "Revive",
                                "Revive.Enabled",
                                "Revive.Costs"
                        )
                )
        );

        TwCompanionReviveSettings revive =
                child.getCommand().getRevive();
        assertTrue(revive.isEnabled());
        assertEquals(120_000L, revive.getGameplayCooldownMs());
        assertEquals(0, revive.getCosts().length);
        assertEquals("parent.message", revive.getInsufficientCostMessage());
    }

    @Test
    void legacyChildOverridesSeedOnlyWhenReviveSectionIsAbsent() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "Revive": {
                      "Enabled": true,
                      "GameplayCooldownMs": 120000,
                      "Costs": [
                        { "ItemId": "Life_Essence", "Quantity": 3 }
                      ]
                    }
                  }
                }
                """);
        TwCompanionConfig legacyChild = decode("""
                {
                  "Command": {
                    "DeadRespawnEnabled": false,
                    "DeadRespawnCooldownMs": 30000
                  }
                }
                """);

        legacyChild.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of(
                        "Command",
                        Set.of(
                                "DeadRespawnEnabled",
                                "DeadRespawnCooldownMs"
                        )
                )
        );

        TwCompanionReviveSettings revive =
                legacyChild.getCommand().getRevive();
        assertFalse(revive.isEnabled());
        assertEquals(30_000L, revive.getGameplayCooldownMs());
        assertEquals("Life_Essence", revive.getCosts()[0].getItemId());
    }

    @Test
    void explicitReviveSectionIgnoresLegacySiblingSeedsForMissingFields() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "Revive": {
                      "Enabled": true,
                      "GameplayCooldownMs": 120000,
                      "Costs": [
                        { "ItemId": "Life_Essence", "Quantity": 3 }
                      ]
                    }
                  }
                }
                """);
        TwCompanionConfig child = decode("""
                {
                  "Command": {
                    "DeadRespawnEnabled": false,
                    "DeadRespawnCooldownMs": 30000,
                    "Revive": {
                      "Costs": [
                        { "ItemId": "Dragon_Essence", "Quantity": 2 }
                      ]
                    }
                  }
                }
                """);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of(
                        "Command",
                        Set.of(
                                "DeadRespawnEnabled",
                                "DeadRespawnCooldownMs",
                                "Revive",
                                "Revive.Costs"
                        )
                )
        );

        TwCompanionReviveSettings revive =
                child.getCommand().getRevive();
        assertTrue(revive.isEnabled());
        assertEquals(120_000L, revive.getGameplayCooldownMs());
        assertEquals("Dragon_Essence", revive.getCosts()[0].getItemId());
    }

    private static TwCompanionConfig decode(String json) {
        return TwCompanionConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
