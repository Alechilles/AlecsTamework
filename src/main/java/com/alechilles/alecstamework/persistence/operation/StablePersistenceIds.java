package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Derives stable persistence identities from one caller-owned intent namespace.
 *
 * <p>The namespace is part of the durable contract and should include an operation-specific
 * version, such as {@code capture:v1}. Ordered parts are encoded with their UTF-8 byte lengths so
 * neither delimiters nor Unicode can make two different intents ambiguous.</p>
 */
public final class StablePersistenceIds {
    private static final String FORMAT = "tamework:persistence-intent:v1";

    private StablePersistenceIds() {
    }

    /** Derives the stable durable operation identity for one exact intent. */
    @Nonnull
    public static OperationId operationId(
            @Nonnull String namespace,
            @Nonnull String... orderedParts
    ) {
        return new OperationId(uuidFrom(
                digest("operation_id", namespace, orderedParts)
        ));
    }

    /** Derives the stable operation-kind deduplication key for one exact intent. */
    @Nonnull
    public static IdempotencyKey idempotencyKey(
            @Nonnull String namespace,
            @Nonnull String... orderedParts
    ) {
        return new IdempotencyKey(
                "intent:v1:" + digest(
                        "idempotency_key", namespace, orderedParts
                )
        );
    }

    /** Derives an opaque receipt that can be copied onto an exact live artifact. */
    @Nonnull
    public static String receipt(
            @Nonnull String namespace,
            @Nonnull String... orderedParts
    ) {
        return "receipt:v1:" + digest(
                "receipt", namespace, orderedParts
        );
    }

    /** Derives the pre-leased target NPC alias for one spawn intent. */
    @Nonnull
    public static NpcAlias targetAlias(
            @Nonnull String namespace,
            @Nonnull String... orderedParts
    ) {
        return new NpcAlias(uuidFrom(
                digest("target_npc_alias", namespace, orderedParts)
        ));
    }

    private static Sha256Hash digest(
            String purpose,
            String namespace,
            String[] orderedParts
    ) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException(
                    "Persistence intent namespace is required"
            );
        }
        if (orderedParts == null) {
            throw new IllegalArgumentException(
                    "Persistence intent parts are required"
            );
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, FORMAT);
        append(canonical, purpose);
        append(canonical, namespace);
        append(canonical, Integer.toString(orderedParts.length));
        for (String part : orderedParts) {
            if (part == null) {
                throw new IllegalArgumentException(
                        "Persistence intent parts cannot contain null"
                );
            }
            append(canonical, part);
        }
        return Sha256Hash.ofUtf8(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        target.append(byteLength).append(':').append(value);
    }

    private static UUID uuidFrom(Sha256Hash hash) {
        byte[] bytes = HexFormat.of().parseHex(hash.value());
        long most = longAt(bytes, 0);
        long least = longAt(bytes, Long.BYTES);
        most = (most & 0xffffffffffff0fffL) | 0x0000000000008000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static long longAt(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = offset; index < offset + Long.BYTES; index++) {
            value = (value << Byte.SIZE) | (bytes[index] & 0xffL);
        }
        return value;
    }
}
