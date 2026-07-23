package com.alechilles.alecstamework.companion.placement;

import com.google.gson.JsonObject;
import javax.annotation.Nonnull;

/** Canonical JSON translation for an exact companion spawn placement. */
public final class CompanionSpawnPlacementJsonCodec {
    private CompanionSpawnPlacementJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(@Nonnull CompanionSpawnPlacement placement) {
        if (placement == null) {
            throw new IllegalArgumentException(
                    "Companion spawn placement is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("worldKey", placement.worldKey());
        json.addProperty("x", placement.x());
        json.addProperty("y", placement.y());
        json.addProperty("z", placement.z());
        json.addProperty("pitchRadians", placement.pitchRadians());
        json.addProperty("yawRadians", placement.yawRadians());
        json.addProperty("rollRadians", placement.rollRadians());
        return json;
    }

    @Nonnull
    public static CompanionSpawnPlacement decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Companion spawn placement JSON is required"
            );
        }
        return new CompanionSpawnPlacement(
                json.get("worldKey").getAsString(),
                json.get("x").getAsDouble(),
                json.get("y").getAsDouble(),
                json.get("z").getAsDouble(),
                json.get("pitchRadians").getAsFloat(),
                json.get("yawRadians").getAsFloat(),
                json.get("rollRadians").getAsFloat()
        );
    }
}
