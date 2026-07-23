package com.alechilles.alecstamework.companion.revival;

import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationKind;

/** Version-one shared operation definition for exact paid revival. */
public final class PaidRevivalDefinition
        implements OperationDefinition<PaidRevivalRequest> {
    public static final PaidRevivalDefinition INSTANCE =
            new PaidRevivalDefinition();
    public static final OperationKind KIND =
            new OperationKind("paid_revival");

    private PaidRevivalDefinition() {
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
    public Class<PaidRevivalRequest> payloadType() {
        return PaidRevivalRequest.class;
    }

    @Override
    public String encode(PaidRevivalRequest payload) {
        return PaidRevivalJsonCodec.encode(payload);
    }

    @Override
    public PaidRevivalRequest decode(String payloadJson) {
        return PaidRevivalJsonCodec.decode(payloadJson);
    }
}
