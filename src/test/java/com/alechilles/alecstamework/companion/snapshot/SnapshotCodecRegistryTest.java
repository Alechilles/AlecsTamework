package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for immutable versioned snapshot codecs and explicit decode failures. */
class SnapshotCodecRegistryTest {
    private static final SnapshotKind KIND = new SnapshotKind("capture");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

    @Test
    void encodesAndDecodesThroughOneExactCodec() {
        SnapshotCodecRegistry registry = new SnapshotCodecRegistry(List.of(new TextCodec()));
        SnapshotCodecRegistry.EncodedSnapshot encoded =
                registry.encode(KIND, 1, SnapshotValue.class, new SnapshotValue("hello"));
        CompanionSnapshot snapshot = snapshot(1, encoded.payloadJson());

        SnapshotDecodeResult.Decoded<SnapshotValue> decoded = assertInstanceOf(
                SnapshotDecodeResult.Decoded.class,
                registry.decode(snapshot, SnapshotValue.class)
        );
        assertEquals(new SnapshotValue("hello"), decoded.value());
    }

    @Test
    void reportsUnsupportedTypeAndDecodeFailuresWithoutReturningAbsence() {
        SnapshotCodecRegistry registry = new SnapshotCodecRegistry(List.of(new TextCodec()));
        CompanionSnapshot unsupported = snapshot(2, "{\"value\":\"hello\"}");
        CompanionSnapshot invalid = snapshot(1, "{\"wrong\":\"shape\"}");

        assertEquals(
                SnapshotDecodeResult.Failure.UNSUPPORTED_CODEC,
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(unsupported, SnapshotValue.class)
                ).failure()
        );
        assertEquals(
                SnapshotDecodeResult.Failure.TYPE_MISMATCH,
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(snapshot(1, "{\"value\":\"hello\"}"), String.class)
                ).failure()
        );
        assertEquals(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                assertInstanceOf(
                        SnapshotDecodeResult.Failed.class,
                        registry.decode(invalid, SnapshotValue.class)
                ).failure()
        );
    }

    @Test
    void rejectsDuplicateCodecKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotCodecRegistry(List.of(new TextCodec(), new TextCodec())));
    }

    private CompanionSnapshot snapshot(int version, String json) {
        return new CompanionSnapshot(
                SnapshotId.create(), PROFILE, KIND, version, json, Sha256Hash.ofUtf8(json),
                LifecycleRevision.INITIAL, true, -1_000
        );
    }

    private record SnapshotValue(String value) {
    }

    private static final class TextCodec implements SnapshotCodec<SnapshotValue> {
        @Override
        public SnapshotKind kind() {
            return KIND;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Class<SnapshotValue> valueType() {
            return SnapshotValue.class;
        }

        @Override
        public String encode(SnapshotValue value) {
            return "{\"value\":\"" + value.value() + "\"}";
        }

        @Override
        public SnapshotValue decode(String payloadJson) {
            String prefix = "{\"value\":\"";
            if (!payloadJson.startsWith(prefix) || !payloadJson.endsWith("\"}")) {
                throw new IllegalArgumentException("invalid test payload");
            }
            return new SnapshotValue(payloadJson.substring(prefix.length(), payloadJson.length() - 2));
        }
    }
}
