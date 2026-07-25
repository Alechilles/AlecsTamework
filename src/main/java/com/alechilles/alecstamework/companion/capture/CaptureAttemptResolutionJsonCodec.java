package com.alechilles.alecstamework.companion.capture;

import com.alechilles.alecstamework.api.CaptureChanceMode;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable JSON codec for frozen terminal capture-attempt evidence. */
public final class CaptureAttemptResolutionJsonCodec {
    private CaptureAttemptResolutionJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull CaptureAttemptResolution resolution
    ) {
        if (resolution == null) {
            throw new IllegalArgumentException(
                    "Capture resolution is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("attemptId", resolution.attemptId().toString());
        json.addProperty("targetRoleId", resolution.targetRoleId());
        json.add("formula", encodeFormula(resolution.formula()));
        json.addProperty(
                "sourceConsumption",
                resolution.sourceConsumption().name()
        );
        json.addProperty(
                "successDisposition",
                resolution.successDisposition().name()
        );
        json.addProperty("outcome", resolution.outcome().name());
        json.addProperty("reason", resolution.reason());
        json.addProperty(
                "effectiveChance", resolution.effectiveChance()
        );
        json.addProperty("guaranteed", resolution.guaranteed());
        json.addProperty(
                "missingHealthFraction",
                resolution.missingHealthFraction()
        );
        nullable(json, "entropy", resolution.entropy());
        nullable(
                json,
                "failureCooldownUntilMs",
                resolution.failureCooldownUntilMs()
        );
        return json;
    }

    @Nonnull
    public static CaptureAttemptResolution decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Capture resolution JSON is required"
            );
        }
        return new CaptureAttemptResolution(
                UUID.fromString(json.get("attemptId").getAsString()),
                json.get("targetRoleId").getAsString(),
                decodeFormula(json.getAsJsonObject("formula")),
                CaptureSourceConsumption.valueOf(
                        json.get("sourceConsumption").getAsString()
                ),
                CaptureSuccessDisposition.valueOf(
                        json.get("successDisposition").getAsString()
                ),
                CaptureAttemptResolution.Outcome.valueOf(
                        json.get("outcome").getAsString()
                ),
                json.get("reason").getAsString(),
                json.get("effectiveChance").getAsDouble(),
                json.get("guaranteed").getAsBoolean(),
                json.get("missingHealthFraction").getAsDouble(),
                nullableDouble(json, "entropy"),
                nullableLong(json, "failureCooldownUntilMs")
        );
    }

    private static JsonObject encodeFormula(CaptureAttemptFormula formula) {
        JsonObject json = new JsonObject();
        json.addProperty("itemConfigId", formula.itemConfigId());
        json.addProperty(
                "itemConfigRevision", formula.itemConfigRevision()
        );
        json.addProperty("chanceMode", formula.chanceMode().name());
        json.addProperty("itemPower", formula.itemPower());
        json.addProperty("baseChance", formula.baseChance());
        json.addProperty("chancePerPower", formula.chancePerPower());
        json.addProperty("minimumChance", formula.minimumChance());
        json.addProperty("maximumChance", formula.maximumChance());
        nullable(json, "policyConfigId", formula.policyConfigId());
        json.addProperty(
                "policyConfigRevision", formula.policyConfigRevision()
        );
        json.addProperty("minimumPower", formula.minimumPower());
        json.addProperty("resistance", formula.resistance());
        json.addProperty(
                "chanceMultiplier", formula.chanceMultiplier()
        );
        json.addProperty(
                "missingHealthBonus", formula.missingHealthBonus()
        );
        nullable(
                json, "guaranteedAtPower", formula.guaranteedAtPower()
        );
        json.addProperty(
                "requirementsHash", formula.requirementsHash().toString()
        );
        json.addProperty(
                "requirementGeneration",
                formula.requirementGeneration()
        );
        return json;
    }

    private static CaptureAttemptFormula decodeFormula(JsonObject json) {
        return new CaptureAttemptFormula(
                json.get("itemConfigId").getAsString(),
                json.get("itemConfigRevision").getAsLong(),
                CaptureChanceMode.valueOf(
                        json.get("chanceMode").getAsString()
                ),
                json.get("itemPower").getAsInt(),
                json.get("baseChance").getAsDouble(),
                json.get("chancePerPower").getAsDouble(),
                json.get("minimumChance").getAsDouble(),
                json.get("maximumChance").getAsDouble(),
                nullableText(json, "policyConfigId"),
                json.get("policyConfigRevision").getAsLong(),
                json.get("minimumPower").getAsInt(),
                json.get("resistance").getAsDouble(),
                json.get("chanceMultiplier").getAsDouble(),
                json.get("missingHealthBonus").getAsDouble(),
                nullableInteger(json, "guaranteedAtPower"),
                Sha256Hash.parse(
                        json.get("requirementsHash").getAsString()
                ),
                json.get("requirementGeneration").getAsLong()
        );
    }

    private static void nullable(
            JsonObject json,
            String name,
            Object value
    ) {
        if (value == null) {
            json.add(name, null);
        } else if (value instanceof Number number) {
            json.addProperty(name, number);
        } else {
            json.addProperty(name, value.toString());
        }
    }

    private static String nullableText(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsString();
    }

    private static Double nullableDouble(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsDouble();
    }

    private static Long nullableLong(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsLong();
    }

    private static Integer nullableInteger(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull()
                ? null
                : value.getAsInt();
    }
}
