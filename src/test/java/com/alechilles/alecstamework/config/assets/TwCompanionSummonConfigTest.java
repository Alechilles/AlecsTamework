package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonTime;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.exception.CodecException;
import java.util.Map;
import java.util.Set;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for timed-summon config decoding and fallback. */
class TwCompanionSummonConfigTest {
    @Test
    void nestedSummonDecodesCompletePolicyAndDefendsWarningArray() {
        TwCompanionSummonSettings summon = decode("""
                {
                  "Command": {
                    "Summon": {
                      "Enabled": true,
                      "ActiveDurationMs": 600000,
                      "ResummonCooldownMs": 45000,
                      "AutoStoreOnOwnerLogout": false,
                      "ExpiryWarningThresholdsMs": [60000, 10000]
                    }
                  }
                }
                """).getCommand().getSummon();

        assertTrue(summon.isEnabled());
        assertEquals(600_000L, summon.getActiveDurationMs());
        assertEquals(45_000L, summon.getResummonCooldownMs());
        assertFalse(summon.isAutoStoreOnOwnerLogout());
        long[] warnings = summon.getExpiryWarningThresholdsMs();
        assertArrayEquals(new long[] { 60_000L, 10_000L }, warnings);

        warnings[0] = 1L;
        assertArrayEquals(
                new long[] { 60_000L, 10_000L },
                summon.getExpiryWarningThresholdsMs()
        );
    }

    @Test
    void nonnegativeDurationsAreEnforcedWithoutClampingSignedWorldTime() {
        assertThrows(CodecException.class, () -> decode("""
                {
                  "Command": {
                    "Summon": { "ActiveDurationMs": -1 }
                  }
                }
                """));
        assertThrows(CodecException.class, () -> decode("""
                {
                  "Command": {
                    "Summon": { "ResummonCooldownMs": -1 }
                  }
                }
                """));

        TwCompanionSummonSettings summon = decode("""
                {
                  "Command": {
                    "Summon": {
                      "ActiveDurationMs": 1500,
                      "ResummonCooldownMs": 250
                    }
                  }
                }
                """).getCommand().getSummon();
        assertEquals(
                -500L,
                TimedSummonTime.saturatingAdd(
                        -2_000L,
                        summon.getActiveDurationMs()
                )
        );
        assertEquals(
                -1_750L,
                TimedSummonTime.saturatingAdd(
                        -2_000L,
                        summon.getResummonCooldownMs()
                )
        );
    }

    @Test
    void warningThresholdShapeIsStrict() {
        assertDecodeFails("""
                {
                  "Command": {
                    "Summon": {
                      "ActiveDurationMs": 10000,
                      "ExpiryWarningThresholdsMs": [5000, 5000]
                    }
                  }
                }
                """);
        assertDecodeFails("""
                {
                  "Command": {
                    "Summon": {
                      "ActiveDurationMs": 10000,
                      "ExpiryWarningThresholdsMs": [1000, 5000]
                    }
                  }
                }
                """);
        assertDecodeFails("""
                {
                  "Command": {
                    "Summon": {
                      "ActiveDurationMs": 10000,
                      "ExpiryWarningThresholdsMs": [0]
                    }
                  }
                }
                """);
    }

    @Test
    void warningsRequirePositiveDurationAndRemainBelowIt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("""
                        {
                          "Command": {
                            "Summon": {
                              "ActiveDurationMs": 0,
                              "ExpiryWarningThresholdsMs": [1]
                            }
                          }
                        }
                        """).getCommand().getSummon()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> decode("""
                        {
                          "Command": {
                            "Summon": {
                              "ActiveDurationMs": 10000,
                              "ExpiryWarningThresholdsMs": [10000]
                            }
                          }
                        }
                        """).getCommand().getSummon()
        );
    }

    @Test
    void explicitNestedWarningArrayReplacesWhileMissingFieldsInherit() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "Summon": {
                      "Enabled": true,
                      "ActiveDurationMs": 600000,
                      "ResummonCooldownMs": 60000,
                      "AutoStoreOnOwnerLogout": true,
                      "ExpiryWarningThresholdsMs": [60000, 10000]
                    }
                  }
                }
                """);
        TwCompanionConfig child = decode("""
                {
                  "Command": {
                    "Summon": {
                      "ActiveDurationMs": 900000,
                      "AutoStoreOnOwnerLogout": false,
                      "ExpiryWarningThresholdsMs": [30000]
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
                                "Summon",
                                "Summon.ActiveDurationMs",
                                "Summon.AutoStoreOnOwnerLogout",
                                "Summon.ExpiryWarningThresholdsMs"
                        )
                )
        );

        TwCompanionSummonSettings summon =
                child.getCommand().getSummon();
        assertTrue(summon.isEnabled());
        assertEquals(900_000L, summon.getActiveDurationMs());
        assertEquals(60_000L, summon.getResummonCooldownMs());
        assertFalse(summon.isAutoStoreOnOwnerLogout());
        assertArrayEquals(
                new long[] { 30_000L },
                summon.getExpiryWarningThresholdsMs()
        );
    }

    @Test
    void omittedSummonSectionInheritsAnIndependentCopy() {
        TwCompanionConfig parent = decode("""
                {
                  "Command": {
                    "Summon": {
                      "Enabled": true,
                      "ActiveDurationMs": 600000,
                      "ExpiryWarningThresholdsMs": [60000]
                    }
                  }
                }
                """);
        TwCompanionConfig child = decode("""
                {
                  "Command": {
                    "RecallSafeSpawnDistance": 12
                  }
                }
                """);

        child.inheritMissingTopLevelFrom(
                parent,
                Set.of("Command"),
                Map.of("Command", Set.of("RecallSafeSpawnDistance"))
        );
        TwCompanionSummonSettings inherited =
                child.getCommand().getSummon();
        parent.getCommand().getSummon().setActiveDurationMs(700_000L);

        assertEquals(600_000L, inherited.getActiveDurationMs());
        assertArrayEquals(
                new long[] { 60_000L },
                inherited.getExpiryWarningThresholdsMs()
        );
    }

    @Test
    void effectiveSettingsExposeOnlyIndependentSummonCopies() {
        TwCompanionConfig scoped = decode("""
                {
                  "Command": {
                    "Summon": {
                      "Enabled": true,
                      "ActiveDurationMs": 600000,
                      "ExpiryWarningThresholdsMs": [60000]
                    }
                  }
                }
                """);
        TwCompanionConfig.EffectiveSettings effective =
                TwCompanionConfig.EffectiveSettings.from(scoped, null);

        TwCompanionSummonSettings first = effective.getSummon();
        first.setActiveDurationMs(120_000L);
        first.setExpiryWarningThresholdsMs(new Long[] { 10_000L });

        TwCompanionSummonSettings second = effective.getSummon();
        assertEquals(600_000L, second.getActiveDurationMs());
        assertArrayEquals(
                new long[] { 60_000L },
                second.getExpiryWarningThresholdsMs()
        );
    }

    private static void assertDecodeFails(String json) {
        assertThrows(CodecException.class, () -> decode(json));
    }

    private static TwCompanionConfig decode(String json) {
        return TwCompanionConfig.CODEC.decode(
                BsonDocument.parse(json),
                new ExtraInfo()
        );
    }
}
