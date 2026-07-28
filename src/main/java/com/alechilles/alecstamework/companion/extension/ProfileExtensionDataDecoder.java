package com.alechilles.alecstamework.companion.extension;

import com.google.gson.JsonParser;
import javax.annotation.Nonnull;

/** Validates the public version-one arbitrary-JSON extension payload contract. */
public final class ProfileExtensionDataDecoder {
    public static final int JSON_VERSION = 1;

    private ProfileExtensionDataDecoder() {
    }

    /** Returns exact JSON text only after version, integrity, and syntax validation. */
    @Nonnull
    public static ProfileExtensionDecodeResult decode(@Nonnull ProfileExtensionData data) {
        if (data == null) {
            throw new IllegalArgumentException("Profile extension data is required");
        }
        if (!data.payloadHash().matchesUtf8(data.jsonPayload())) {
            return failed(
                    ProfileExtensionDecodeResult.Failure.HASH_MISMATCH,
                    "extension_hash_mismatch",
                    null
            );
        }
        if (data.payloadVersion() != JSON_VERSION) {
            return failed(
                    ProfileExtensionDecodeResult.Failure.UNSUPPORTED_VERSION,
                    "extension_payload_version_unsupported",
                    null
            );
        }
        try {
            JsonParser.parseString(data.jsonPayload());
            return new ProfileExtensionDecodeResult.Decoded(data.jsonPayload());
        } catch (RuntimeException failure) {
            return failed(
                    ProfileExtensionDecodeResult.Failure.INVALID_JSON,
                    "extension_json_invalid",
                    failure
            );
        }
    }

    private static ProfileExtensionDecodeResult.Failed failed(
            ProfileExtensionDecodeResult.Failure failure,
            String code,
            Throwable cause
    ) {
        return new ProfileExtensionDecodeResult.Failed(failure, code, cause);
    }
}
