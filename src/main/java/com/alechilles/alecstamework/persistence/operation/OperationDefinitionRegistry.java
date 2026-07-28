package com.alechilles.alecstamework.persistence.operation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/** Immutable registry of exactly one typed definition per operation kind. */
public final class OperationDefinitionRegistry {
    private final Map<OperationKind, OperationDefinition<?>> definitions;

    public OperationDefinitionRegistry(
            @Nonnull Collection<? extends OperationDefinition<?>> definitions
    ) {
        if (definitions == null) {
            throw new IllegalArgumentException("Operation definitions are required");
        }
        HashMap<OperationKind, OperationDefinition<?>> indexed = new HashMap<>();
        for (OperationDefinition<?> definition : definitions) {
            validate(definition);
            if (indexed.putIfAbsent(definition.kind(), definition) != null) {
                throw new IllegalArgumentException(
                        "Duplicate operation definition: " + definition.kind()
                );
            }
        }
        this.definitions = Map.copyOf(indexed);
    }

    /** Encodes through the registered kind, version, and Java type. */
    @Nonnull
    public <T> EncodedOperation encode(@Nonnull OperationDefinition<T> definition,
                                       @Nonnull T payload) {
        requireRegistered(definition);
        if (payload == null || !definition.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Operation payload type does not match definition");
        }
        try {
            String json = definition.encode(payload);
            if (json == null) {
                throw new IllegalStateException("Operation definition returned null JSON");
            }
            return new EncodedOperation(definition.kind(), definition.payloadVersion(), json);
        } catch (Exception failure) {
            throw new IllegalArgumentException("operation_encode_failed", failure);
        }
    }

    /** Decodes durable recovery evidence with explicit failure outcomes. */
    @Nonnull
    public <T> OperationDecodeResult<T> decode(@Nonnull OperationEnvelope envelope,
                                               @Nonnull Class<T> payloadType) {
        if (envelope == null || payloadType == null) {
            throw new IllegalArgumentException("Operation envelope and payload type are required");
        }
        OperationDefinition<?> raw = definitions.get(envelope.kind());
        if (raw == null) {
            return failed(
                    OperationDecodeResult.Failure.UNKNOWN_DEFINITION,
                    "operation_definition_unknown",
                    null
            );
        }
        if (raw.payloadVersion() != envelope.payloadVersion()) {
            return failed(
                    OperationDecodeResult.Failure.UNSUPPORTED_VERSION,
                    "operation_payload_version_unsupported",
                    null
            );
        }
        if (!raw.payloadType().equals(payloadType)) {
            return failed(
                    OperationDecodeResult.Failure.TYPE_MISMATCH,
                    "operation_payload_type_mismatch",
                    null
            );
        }
        try {
            Object decoded = raw.decode(envelope.payloadJson());
            return new OperationDecodeResult.Decoded<>(payloadType.cast(decoded));
        } catch (Exception failure) {
            return failed(
                    OperationDecodeResult.Failure.DECODE_FAILED,
                    "operation_payload_decode_failed",
                    failure
            );
        }
    }

    /** Decodes a durable payload while retaining its registered definition for recovery routing. */
    @Nonnull
    public OperationDecodeResult<DecodedOperationPayload> decode(
            @Nonnull OperationEnvelope envelope
    ) {
        if (envelope == null) {
            throw new IllegalArgumentException("Operation envelope is required");
        }
        OperationDefinition<?> definition = definitions.get(envelope.kind());
        if (definition == null) {
            return failed(
                    OperationDecodeResult.Failure.UNKNOWN_DEFINITION,
                    "operation_definition_unknown",
                    null
            );
        }
        if (definition.payloadVersion() != envelope.payloadVersion()) {
            return failed(
                    OperationDecodeResult.Failure.UNSUPPORTED_VERSION,
                    "operation_payload_version_unsupported",
                    null
            );
        }
        try {
            Object payload = definition.decode(envelope.payloadJson());
            return new OperationDecodeResult.Decoded<>(
                    new DecodedOperationPayload(definition, payload)
            );
        } catch (Exception failure) {
            return failed(
                    OperationDecodeResult.Failure.DECODE_FAILED,
                    "operation_payload_decode_failed",
                    failure
            );
        }
    }

    /** Returns whether the exact registered definition opts this UNKNOWN into live proof. */
    public boolean allowsUnknownLiveReverification(
            @Nonnull OperationEnvelope envelope
    ) {
        if (envelope == null) {
            throw new IllegalArgumentException(
                    "Operation envelope is required"
            );
        }
        OperationDefinition<?> definition = definitions.get(
                envelope.kind()
        );
        return definition != null
                && definition.payloadVersion() == envelope.payloadVersion()
                && definition.allowsUnknownLiveReverification(envelope);
    }

    private void requireRegistered(OperationDefinition<?> definition) {
        validate(definition);
        if (definitions.get(definition.kind()) != definition) {
            throw new IllegalArgumentException("Operation definition instance is not registered");
        }
    }

    private void validate(OperationDefinition<?> definition) {
        if (definition == null || definition.kind() == null || definition.payloadType() == null
                || definition.payloadVersion() <= 0) {
            throw new IllegalArgumentException("Complete positive-version operation definition is required");
        }
    }

    private <T> OperationDecodeResult.Failed<T> failed(
            OperationDecodeResult.Failure failure,
            String code,
            Throwable cause
    ) {
        return new OperationDecodeResult.Failed<>(failure, code, cause);
    }

    /** Encoded payload ready for durable preparation. */
    public record EncodedOperation(@Nonnull OperationKind kind,
                                   int payloadVersion,
                                   @Nonnull String payloadJson) {
        public EncodedOperation {
            if (kind == null || payloadVersion <= 0 || payloadJson == null) {
                throw new IllegalArgumentException("Complete encoded operation is required");
            }
        }
    }
}
