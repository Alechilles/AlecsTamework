package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Canonical, scope-bound identity for one bonded-revival payment escrow. */
public final class BondedCompanionPaymentOperationId {
    private static final String PREFIX = "bonded-revive-v2:";

    private BondedCompanionPaymentOperationId() {
    }

    /** Binds payment evidence to the complete request scope without delimiters. */
    @Nonnull
    public static String create(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey,
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision) {
        Identity identity = new Identity(
                callerNamespace, idempotencyKey, ownerUuid, rosterId,
                profileId, expectedRevision);
        String scope = encoded(identity.callerNamespace()) + "."
                + encoded(identity.idempotencyKey()) + "."
                + identity.ownerUuid() + "." + encoded(identity.rosterId())
                + "." + encoded(identity.profileId()) + "."
                + identity.expectedRevision();
        return PREFIX + scope + "." + Sha256Hash.ofUtf8(scope);
    }

    /** Parses and hash-validates a recoverable v2 payment identity. */
    @Nonnull
    public static Optional<Identity> parse(@Nonnull String operationId) {
        String value = text(operationId);
        if (!value.startsWith(PREFIX)) return Optional.empty();
        String[] fields = value.substring(PREFIX.length())
                .split("\\.", -1);
        if (fields.length != 7) return Optional.empty();
        try {
            Identity identity = new Identity(
                    decoded(fields[0]), decoded(fields[1]),
                    UUID.fromString(fields[2]), decoded(fields[3]),
                    decoded(fields[4]), Long.parseLong(fields[5]));
            return value.equals(create(
                    identity.callerNamespace(), identity.idempotencyKey(),
                    identity.ownerUuid(), identity.rosterId(),
                    identity.profileId(), identity.expectedRevision()))
                    ? Optional.of(identity) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /** Recovers the pre-escrow key used only to quarantine legacy receipts. */
    @Nonnull
    public static String legacyOperationKey(@Nonnull String operationId) {
        String value = text(operationId);
        Optional<Identity> identity = parse(value);
        if (identity.isPresent()) {
            return legacyOperationKey(identity.get().callerNamespace(),
                    identity.get().idempotencyKey());
        }
        if (!value.startsWith(PREFIX)) return value;
        int separator = value.indexOf(':', PREFIX.length());
        if (separator <= PREFIX.length()) return value;
        try {
            return new String(Base64.getUrlDecoder().decode(
                    value.substring(PREFIX.length(), separator)),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return value;
        }
    }

    /** Reconstructs the exact flattened key written by pre-escrow builds. */
    @Nonnull
    public static String legacyOperationKey(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey
    ) {
        return text(callerNamespace) + ":" + text(idempotencyKey);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                text(value).getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8);
    }

    private static String text(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Payment identity field is required");
        }
        return normalized;
    }

    /** Complete scope needed to recover a terminal payment after restart. */
    public record Identity(
            @Nonnull String callerNamespace,
            @Nonnull String idempotencyKey,
            @Nonnull UUID ownerUuid,
            @Nonnull String rosterId,
            @Nonnull String profileId,
            long expectedRevision
    ) {
        public Identity {
            callerNamespace = text(callerNamespace);
            idempotencyKey = text(idempotencyKey);
            ownerUuid = Objects.requireNonNull(ownerUuid, "ownerUuid");
            rosterId = text(rosterId);
            profileId = text(profileId);
        }

        /** Returns the exact canonical ID represented by this scope. */
        @Nonnull
        public String operationId() {
            return create(callerNamespace, idempotencyKey, ownerUuid,
                    rosterId, profileId, expectedRevision);
        }
    }
}
