package com.alechilles.alecstamework.companion.coop;

import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Version-one typed operation definition for structural coop slot registration. */
public final class CoopSlotRegistrationDefinition
        implements OperationDefinition<CoopSlotRegistration> {
    public static final CoopSlotRegistrationDefinition INSTANCE =
            new CoopSlotRegistrationDefinition();
    public static final OperationKind KIND =
            new OperationKind("coop_slot_registration");

    private CoopSlotRegistrationDefinition() {
    }

    @Override
    public OperationKind kind() {
        return KIND;
    }

    @Override
    public int payloadVersion() {
        return 1;
    }

    @Override
    public Class<CoopSlotRegistration> payloadType() {
        return CoopSlotRegistration.class;
    }

    @Override
    public String encode(CoopSlotRegistration payload) {
        JsonObject json = new JsonObject();
        json.addProperty("slotKey", payload.slot().key().toString());
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public CoopSlotRegistration decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson).getAsJsonObject();
        return new CoopSlotRegistration(
                CoopSlot.unoccupied(
                        CoopSlotKey.parse(json.get("slotKey").getAsString())
                ),
                json.get("requestedAtMs").getAsLong()
        );
    }
}
