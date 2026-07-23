package com.alechilles.alecstamework.persistence.operation;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for shared typed operation definitions and recovery decoding. */
class OperationDefinitionRegistryTest {
    private static final OperationKind KIND = new OperationKind("capture");

    @Test
    void encodesAndDecodesThroughTheRegisteredDefinition() {
        TestDefinition definition = new TestDefinition();
        OperationDefinitionRegistry registry =
                new OperationDefinitionRegistry(List.of(definition));
        OperationDefinitionRegistry.EncodedOperation encoded =
                registry.encode(definition, new Payload("hello"));
        OperationEnvelope envelope = envelope(KIND, 1, encoded.payloadJson());

        OperationDecodeResult.Decoded<Payload> decoded = assertInstanceOf(
                OperationDecodeResult.Decoded.class,
                registry.decode(envelope, Payload.class)
        );
        assertEquals(new Payload("hello"), decoded.value());
    }

    @Test
    void returnsExactRecoveryDecodeFailures() {
        OperationDefinitionRegistry registry =
                new OperationDefinitionRegistry(List.of(new TestDefinition()));

        assertEquals(
                OperationDecodeResult.Failure.UNKNOWN_DEFINITION,
                assertInstanceOf(
                        OperationDecodeResult.Failed.class,
                        registry.decode(envelope(new OperationKind("unknown"), 1, "{}"), Payload.class)
                ).failure()
        );
        assertEquals(
                OperationDecodeResult.Failure.UNSUPPORTED_VERSION,
                assertInstanceOf(
                        OperationDecodeResult.Failed.class,
                        registry.decode(envelope(KIND, 2, "{}"), Payload.class)
                ).failure()
        );
        assertEquals(
                OperationDecodeResult.Failure.TYPE_MISMATCH,
                assertInstanceOf(
                        OperationDecodeResult.Failed.class,
                        registry.decode(envelope(KIND, 1, "{}"), String.class)
                ).failure()
        );
        assertEquals(
                OperationDecodeResult.Failure.DECODE_FAILED,
                assertInstanceOf(
                        OperationDecodeResult.Failed.class,
                        registry.decode(envelope(KIND, 1, "{\"wrong\":true}"), Payload.class)
                ).failure()
        );
    }

    @Test
    void rejectsDuplicateDefinitionsAndUnregisteredInstances() {
        TestDefinition registered = new TestDefinition();
        assertThrows(IllegalArgumentException.class,
                () -> new OperationDefinitionRegistry(List.of(
                        registered, new TestDefinition()
                )));
        OperationDefinitionRegistry registry =
                new OperationDefinitionRegistry(List.of(registered));
        assertThrows(IllegalArgumentException.class,
                () -> registry.encode(new TestDefinition(), new Payload("value")));
    }

    private OperationEnvelope envelope(OperationKind kind, int version, String json) {
        OperationId id = OperationId.create();
        return new OperationEnvelope(
                id, new IdempotencyKey("test"), kind, version, json,
                OperationPhase.PREPARED, "test", null, null, 0, 0,
                null, null, -1_000, -1_000, null, null, null,
                List.of(OperationScope.operation(id))
        );
    }

    private record Payload(String value) {
    }

    private static final class TestDefinition implements OperationDefinition<Payload> {
        @Override
        public OperationKind kind() {
            return KIND;
        }

        @Override
        public int payloadVersion() {
            return 1;
        }

        @Override
        public Class<Payload> payloadType() {
            return Payload.class;
        }

        @Override
        public String encode(Payload payload) {
            return "{\"value\":\"" + payload.value() + "\"}";
        }

        @Override
        public Payload decode(String payloadJson) {
            String prefix = "{\"value\":\"";
            if (!payloadJson.startsWith(prefix) || !payloadJson.endsWith("\"}")) {
                throw new IllegalArgumentException("invalid test payload");
            }
            return new Payload(payloadJson.substring(prefix.length(), payloadJson.length() - 2));
        }
    }
}
