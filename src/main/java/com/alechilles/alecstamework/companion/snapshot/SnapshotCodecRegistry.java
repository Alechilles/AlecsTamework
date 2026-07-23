package com.alechilles.alecstamework.companion.snapshot;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/** Immutable registry of exactly one codec per snapshot kind and payload version. */
public final class SnapshotCodecRegistry {
    private final Map<Key, SnapshotCodec<?>> codecs;

    public SnapshotCodecRegistry(@Nonnull Collection<? extends SnapshotCodec<?>> codecs) {
        if (codecs == null) {
            throw new IllegalArgumentException("Snapshot codecs are required");
        }
        HashMap<Key, SnapshotCodec<?>> indexed = new HashMap<>();
        for (SnapshotCodec<?> codec : codecs) {
            validate(codec);
            Key key = new Key(codec.kind(), codec.version());
            if (indexed.putIfAbsent(key, codec) != null) {
                throw new IllegalArgumentException("Duplicate snapshot codec: " + key);
            }
        }
        this.codecs = Map.copyOf(indexed);
    }

    /** Encodes through the exact registered codec and computes integrity evidence once. */
    @Nonnull
    public <T> EncodedSnapshot encode(@Nonnull SnapshotKind kind,
                                      int version,
                                      @Nonnull Class<T> valueType,
                                      @Nonnull T value) {
        SnapshotCodec<T> codec = findCodec(kind, version, valueType);
        try {
            String json = codec.encode(value);
            if (json == null) {
                throw new IllegalStateException("Snapshot codec returned null JSON");
            }
            return new EncodedSnapshot(kind, version, json, Sha256Hash.ofUtf8(json));
        } catch (Exception failure) {
            throw new IllegalArgumentException("snapshot_encode_failed", failure);
        }
    }

    /** Decodes with explicit unsupported, integrity, type, and codec failure outcomes. */
    @Nonnull
    public <T> SnapshotDecodeResult<T> decode(@Nonnull CompanionSnapshot snapshot,
                                              @Nonnull Class<T> valueType) {
        if (snapshot == null || valueType == null) {
            throw new IllegalArgumentException("Snapshot and expected value type are required");
        }
        return decode(
                new EncodedSnapshot(
                        snapshot.kind(),
                        snapshot.payloadVersion(),
                        snapshot.payloadJson(),
                        snapshot.payloadHash()
                ),
                valueType
        );
    }

    /** Decodes a self-contained operation artifact without inventing canonical snapshot identity. */
    @Nonnull
    public <T> SnapshotDecodeResult<T> decode(
            @Nonnull EncodedSnapshot snapshot,
            @Nonnull Class<T> valueType
    ) {
        if (snapshot == null || valueType == null) {
            throw new IllegalArgumentException("Snapshot and expected value type are required");
        }
        if (!snapshot.payloadHash().matchesUtf8(snapshot.payloadJson())) {
            return failed(SnapshotDecodeResult.Failure.HASH_MISMATCH, "snapshot_hash_mismatch", null);
        }
        SnapshotCodec<?> raw = codecs.get(new Key(snapshot.kind(), snapshot.payloadVersion()));
        if (raw == null) {
            return failed(
                    SnapshotDecodeResult.Failure.UNSUPPORTED_CODEC,
                    "snapshot_codec_unsupported",
                    null
            );
        }
        if (!raw.valueType().equals(valueType)) {
            return failed(SnapshotDecodeResult.Failure.TYPE_MISMATCH, "snapshot_type_mismatch", null);
        }
        try {
            Object decoded = raw.decode(snapshot.payloadJson());
            return new SnapshotDecodeResult.Decoded<>(valueType.cast(decoded));
        } catch (Exception failure) {
            return failed(SnapshotDecodeResult.Failure.DECODE_FAILED, "snapshot_decode_failed", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> SnapshotCodec<T> findCodec(SnapshotKind kind, int version, Class<T> type) {
        if (kind == null || type == null || version <= 0) {
            throw new IllegalArgumentException("Valid snapshot codec key and type are required");
        }
        SnapshotCodec<?> codec = codecs.get(new Key(kind, version));
        if (codec == null) {
            throw new IllegalArgumentException("snapshot_codec_unsupported");
        }
        if (!codec.valueType().equals(type)) {
            throw new IllegalArgumentException("snapshot_type_mismatch");
        }
        return (SnapshotCodec<T>) codec;
    }

    private void validate(SnapshotCodec<?> codec) {
        if (codec == null || codec.kind() == null || codec.valueType() == null
                || codec.version() <= 0) {
            throw new IllegalArgumentException("Complete positive-version snapshot codec is required");
        }
    }

    private <T> SnapshotDecodeResult.Failed<T> failed(
            SnapshotDecodeResult.Failure failure,
            String code,
            Throwable cause
    ) {
        return new SnapshotDecodeResult.Failed<>(failure, code, cause);
    }

    /** Exact encoded payload plus its integrity digest. */
    public record EncodedSnapshot(@Nonnull SnapshotKind kind,
                                  int version,
                                  @Nonnull String payloadJson,
                                  @Nonnull Sha256Hash payloadHash) {
        public EncodedSnapshot {
            if (kind == null || version <= 0 || payloadJson == null || payloadHash == null) {
                throw new IllegalArgumentException("Complete encoded snapshot is required");
            }
            if (!payloadHash.matchesUtf8(payloadJson)) {
                throw new IllegalArgumentException(
                        "Encoded snapshot SHA-256 does not match its payload"
                );
            }
        }

        /** Alias matching the canonical snapshot field name at JSON boundaries. */
        public int payloadVersion() {
            return version;
        }
    }

    private record Key(SnapshotKind kind, int version) {
    }
}
