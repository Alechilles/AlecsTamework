package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Validating JSON translation for immutable provisioning provenance. */
public final class ProvisioningRecordJsonCodec {
    private ProvisioningRecordJsonCodec() {
    }

    @Nonnull
    public static JsonObject encode(
            @Nonnull ProvisioningRecord record
    ) {
        if (record == null) {
            throw new IllegalArgumentException(
                    "Provisioning record is required"
            );
        }
        JsonObject json = new JsonObject();
        json.addProperty("profileId", record.profileId().toString());
        json.addProperty(
                "callerNamespace", record.origin().callerNamespace()
        );
        json.addProperty("callerKey", record.origin().callerKey());
        if (record.correlationId() == null) {
            json.add("correlationId", null);
        } else {
            json.addProperty(
                    "correlationId", record.correlationId().toString()
            );
        }
        json.addProperty("policyRevision", record.policyRevision());
        json.addProperty(
                "creationOperationId",
                record.creationOperationId().toString()
        );
        json.addProperty("createdAtMs", record.createdAtMs());
        return json;
    }

    @Nonnull
    public static ProvisioningRecord decode(@Nonnull JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException(
                    "Provisioning record JSON is required"
            );
        }
        JsonElement correlation = json.get("correlationId");
        return new ProvisioningRecord(
                ProfileId.parse(json.get("profileId").getAsString()),
                new ProvisioningOrigin(
                        json.get("callerNamespace").getAsString(),
                        json.get("callerKey").getAsString()
                ),
                correlation == null || correlation.isJsonNull()
                        ? null
                        : UUID.fromString(correlation.getAsString()),
                json.get("policyRevision").getAsLong(),
                OperationId.parse(
                        json.get("creationOperationId").getAsString()
                ),
                json.get("createdAtMs").getAsLong()
        );
    }
}

