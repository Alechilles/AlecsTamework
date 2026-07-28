package com.alechilles.alecstamework.companion.command.timed;

import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycleJsonCodec;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared-envelope definition for all database-only timed lease mutations. */
public final class TimedSummonLeaseMutationDefinition
        implements OperationDefinition<TimedSummonLeaseMutationRequest> {
    public static final TimedSummonLeaseMutationDefinition INSTANCE =
            new TimedSummonLeaseMutationDefinition();
    public static final OperationKind KIND =
            new OperationKind("timed_summon_lease_mutation");

    private TimedSummonLeaseMutationDefinition() {
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
    public Class<TimedSummonLeaseMutationRequest> payloadType() {
        return TimedSummonLeaseMutationRequest.class;
    }

    @Override
    public String encode(TimedSummonLeaseMutationRequest payload) {
        JsonObject json = new JsonObject();
        if (payload.before() == null) {
            json.add("before", com.google.gson.JsonNull.INSTANCE);
        } else {
            json.add(
                    "before",
                    TimedSummonLeaseJsonCodec.encode(payload.before())
            );
        }
        json.add(
                "after",
                TimedSummonLeaseJsonCodec.encode(payload.after())
        );
        json.add(
                "lifecycle",
                CompanionLifecycleJsonCodec.encode(payload.lifecycle())
        );
        json.addProperty("requestedAtMs", payload.requestedAtMs());
        return json.toString();
    }

    @Override
    public TimedSummonLeaseMutationRequest decode(String payloadJson) {
        JsonObject json = JsonParser.parseString(payloadJson)
                .getAsJsonObject();
        return new TimedSummonLeaseMutationRequest(
                json.get("before").isJsonNull()
                        ? null
                        : TimedSummonLeaseJsonCodec.decode(
                                json.getAsJsonObject("before")
                        ),
                TimedSummonLeaseJsonCodec.decode(
                        json.getAsJsonObject("after")
                ),
                CompanionLifecycleJsonCodec.decode(
                        json.getAsJsonObject("lifecycle")
                ),
                json.get("requestedAtMs").getAsLong()
        );
    }
}

