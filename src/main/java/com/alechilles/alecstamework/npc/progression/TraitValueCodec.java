package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Encodes and decodes trait values for metadata and snapshot persistence layers.
 */
public final class TraitValueCodec {
    private static final Gson GSON = new Gson();
    private static final TraitValuePayload[] EMPTY_PAYLOADS = new TraitValuePayload[0];

    private TraitValueCodec() {
    }

    public static String encode(@Nullable TameworkTraitsComponent.TraitValue[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        List<TraitValuePayload> payloads = new ArrayList<>(values.length);
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (value == null || value.getId() == null || value.getId().isBlank()) {
                continue;
            }
            payloads.add(new TraitValuePayload(value.getId(), value.getValue()));
        }
        if (payloads.isEmpty()) {
            return "";
        }
        return GSON.toJson(payloads.toArray(EMPTY_PAYLOADS));
    }

    public static TameworkTraitsComponent.TraitValue[] decode(@Nullable String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new TameworkTraitsComponent.TraitValue[0];
        }
        try {
            TraitValuePayload[] payloads = GSON.fromJson(encoded, TraitValuePayload[].class);
            if (payloads == null || payloads.length == 0) {
                return new TameworkTraitsComponent.TraitValue[0];
            }
            ArrayList<TameworkTraitsComponent.TraitValue> values = new ArrayList<>(payloads.length);
            for (TraitValuePayload payload : payloads) {
                if (payload == null || payload.id == null || payload.id.isBlank()) {
                    continue;
                }
                values.add(new TameworkTraitsComponent.TraitValue(payload.id, payload.value));
            }
            return values.toArray(new TameworkTraitsComponent.TraitValue[0]);
        } catch (JsonSyntaxException ignored) {
            return new TameworkTraitsComponent.TraitValue[0];
        }
    }

    private static final class TraitValuePayload {
        private final String id;
        private final double value;

        private TraitValuePayload(String id, double value) {
            this.id = id;
            this.value = value;
        }
    }
}
