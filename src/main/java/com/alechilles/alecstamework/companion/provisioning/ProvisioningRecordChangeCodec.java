package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Versioned outbox evidence for immutable provisioning provenance. */
public final class ProvisioningRecordChangeCodec {
    public static final int VERSION = 1;
    public static final ProjectionEventType EVENT_TYPE =
            new ProjectionEventType("provisioning_record_created");

    private ProvisioningRecordChangeCodec() {
    }

    @Nonnull
    public static ProjectionEventDraft draft(
            @Nonnull OperationId operationId,
            @Nonnull ProvisioningRecord record
    ) {
        if (operationId == null || record == null
                || !operationId.equals(record.creationOperationId())) {
            throw new IllegalArgumentException(
                    "Exact provisioning record event is required"
            );
        }
        return new ProjectionEventDraft(
                operationId,
                EVENT_TYPE,
                record.profileId().toString(),
                0,
                VERSION,
                ProvisioningRecordJsonCodec.encode(record).toString(),
                record.createdAtMs()
        );
    }

    @Nonnull
    public static ProvisioningRecord decode(
            int payloadVersion,
            @Nonnull String payloadJson
    ) {
        if (payloadVersion != VERSION || payloadJson == null) {
            throw new IllegalArgumentException(
                    "Unsupported provisioning record payload"
            );
        }
        return ProvisioningRecordJsonCodec.decode(
                JsonParser.parseString(payloadJson).getAsJsonObject()
        );
    }
}

