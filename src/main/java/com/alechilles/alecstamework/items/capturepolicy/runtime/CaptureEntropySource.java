package com.alechilles.alecstamework.items.capturepolicy.runtime;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Replay-stable entropy keyed by an unpredictable attempt UUID. */
@FunctionalInterface
public interface CaptureEntropySource {
    double sample(@Nonnull UUID attemptId);

    static CaptureEntropySource sha256() {
        return attemptId -> {
            Objects.requireNonNull(attemptId, "attemptId");
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                        ("tamework:capture:v1:" + attemptId).getBytes(StandardCharsets.UTF_8));
                long bits = ByteBuffer.wrap(digest).getLong() >>> 11;
                return bits * 0x1.0p-53;
            } catch (Exception failure) {
                throw new IllegalStateException("capture_entropy_unavailable", failure);
            }
        };
    }
}
