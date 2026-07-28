package com.alechilles.alecstamework.companion.extension;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Contract tests for explicit extension payload decode failures. */
class ProfileExtensionDataDecoderTest {
    private static final ProfileExtensionKey KEY = new ProfileExtensionKey(
            ProfileId.parse("20000000-0000-0000-0000-000000000001"),
            "example:integration",
            "state"
    );

    @Test
    void distinguishesValidHashVersionAndJsonFailures() {
        String json = "{\"value\":1}";
        assertInstanceOf(
                ProfileExtensionDecodeResult.Decoded.class,
                ProfileExtensionDataDecoder.decode(value(1, json, Sha256Hash.ofUtf8(json)))
        );

        assertFailure(
                ProfileExtensionDecodeResult.Failure.HASH_MISMATCH,
                value(1, json, Sha256Hash.ofUtf8("{}"))
        );
        assertFailure(
                ProfileExtensionDecodeResult.Failure.UNSUPPORTED_VERSION,
                value(2, json, Sha256Hash.ofUtf8(json))
        );
        String invalid = "{";
        assertFailure(
                ProfileExtensionDecodeResult.Failure.INVALID_JSON,
                value(1, invalid, Sha256Hash.ofUtf8(invalid))
        );
    }

    private ProfileExtensionData value(int version, String json, Sha256Hash hash) {
        return new ProfileExtensionData(
                KEY, version, json, hash, 1, -10_000, -9_000, null
        );
    }

    private void assertFailure(
            ProfileExtensionDecodeResult.Failure expected,
            ProfileExtensionData value
    ) {
        ProfileExtensionDecodeResult.Failed failed = assertInstanceOf(
                ProfileExtensionDecodeResult.Failed.class,
                ProfileExtensionDataDecoder.decode(value)
        );
        assertEquals(expected, failed.failure());
    }
}
