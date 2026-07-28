package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionExtensionData;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataKey;
import com.alechilles.alecstamework.api.BondedCompanionExtensionDataUpdate;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Owns profile-keyed extension lookup, CAS mutation, and request identity. */
final class BondedCompanionExtensionOperations {
    private static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1000L;
    private final BondedCompanionStore store;
    private final LongSupplier clock;

    BondedCompanionExtensionOperations(
            BondedCompanionStore store,
            LongSupplier clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Optional<BondedCompanionExtensionData> find(
            BondedCompanionExtensionDataKey key
    ) {
        BondedCompanionRecord.Profile profile = store.findProfile(
                key.ownerUuid(), key.profileId()).orElse(null);
        if (profile == null) return Optional.empty();
        return store.findExtensionData(
                        key.ownerUuid(), profile.rosterId(),
                        key.profileId(), key.namespace())
                .map(value -> view(value, key.ownerUuid()));
    }

    BondedCompanionStoreResult<BondedCompanionExtensionData> update(
            BondedCompanionExtensionDataUpdate update
    ) {
        JsonParser.parseString(update.jsonPayload());
        BondedCompanionRecord.Profile profile = store.findProfile(
                update.key().ownerUuid(), update.key().profileId()).orElse(null);
        if (profile == null) return new BondedCompanionStoreResult<>(
                BondedCompanionStoreResult.Code.NOT_FOUND, null,
                "bonded-profile-not-found", false);
        long expected = update.expectedRevision();
        long revision = expected == BondedCompanionExtensionDataUpdate.MISSING_REVISION
                ? 0L : Math.addExact(expected, 1L);
        long now = clock.getAsLong();
        BondedCompanionRecord.ExtensionData extension =
                new BondedCompanionRecord.ExtensionData(
                        profile.profileId(), update.key().namespace(),
                        BondedCompanionPayload.of(update.jsonPayload()
                                .getBytes(StandardCharsets.UTF_8)),
                        revision, now);
        BondedCompanionStoreResult<BondedCompanionRecord.ExtensionData> saved =
                store.compareAndSetExtensionData(
                        operation(update, profile, now), extension, expected);
        BondedCompanionExtensionData value = saved.value() == null ? null
                : view(saved.value(), update.key().ownerUuid());
        return new BondedCompanionStoreResult<>(
                saved.code(), value, saved.reason(), saved.replayed());
    }

    private BondedCompanionOperation operation(
            BondedCompanionExtensionDataUpdate update,
            BondedCompanionRecord.Profile profile,
            long now
    ) {
        return new BondedCompanionOperation(
                update.callerNamespace(), update.idempotencyKey(),
                sha256(payload(update)), update.key().ownerUuid(),
                profile.rosterId(), profile.profileId(),
                BondedCompanionOperation.Type.STORE, now,
                safeAdd(now, RETENTION_MS));
    }

    private String payload(BondedCompanionExtensionDataUpdate update) {
        StringBuilder value = new StringBuilder();
        append(value, update.key().ownerUuid().toString());
        append(value, update.key().profileId());
        append(value, update.key().namespace());
        append(value, update.jsonPayload());
        append(value, Long.toString(update.expectedRevision()));
        return value.toString();
    }

    private void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long safeAdd(long value, long increment) {
        try { return Math.addExact(value, increment); }
        catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    private BondedCompanionExtensionData view(
            BondedCompanionRecord.ExtensionData value,
            UUID ownerUuid
    ) {
        return new BondedCompanionExtensionData(
                new BondedCompanionExtensionDataKey(
                        ownerUuid, value.profileId(), value.namespace()),
                new String(value.payload().bytes(), StandardCharsets.UTF_8),
                value.revision(), value.updatedAtMs());
    }
}
