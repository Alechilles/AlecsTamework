package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Stable caller origin that deterministically identifies one provisioned profile. */
public record ProvisioningOrigin(
        @Nonnull String callerNamespace,
        @Nonnull String callerKey
) implements Comparable<ProvisioningOrigin> {
    public static final int MAX_NAMESPACE_LENGTH = 128;
    public static final int MAX_KEY_LENGTH = 256;

    public ProvisioningOrigin {
        callerNamespace = text(
                callerNamespace, "Provisioning caller namespace",
                MAX_NAMESPACE_LENGTH
        );
        callerKey = text(
                callerKey, "Provisioning caller key", MAX_KEY_LENGTH
        );
    }

    /** Returns an opaque delimiter-safe identity for lifecycle and operation keys. */
    @Nonnull
    public String stableKey() {
        return Sha256Hash.ofUtf8(encoded()).toString();
    }

    /** Returns the canonical profile identity allocated to this origin. */
    @Nonnull
    public ProfileId profileId() {
        return new ProfileId(namedUuid("profile"));
    }

    /** Returns the deterministic initial command slot for an optional link. */
    @Nonnull
    public CommandRosterSlotId commandSlotId() {
        return new CommandRosterSlotId(namedUuid("command-slot"));
    }

    /** Returns the shared-operation idempotency key for dormant creation. */
    @Nonnull
    public IdempotencyKey operationKey() {
        return new IdempotencyKey("provision:" + stableKey());
    }

    /** Returns the receipt-correlated key for one initial live activation. */
    @Nonnull
    public IdempotencyKey activationKey(@Nonnull String receiptKey) {
        if (receiptKey == null || receiptKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Provisioning activation receipt is required"
            );
        }
        return new IdempotencyKey(
                "provision-activate:" + stableKey() + ":"
                        + Sha256Hash.ofUtf8(receiptKey.trim())
        );
    }

    @Override
    public int compareTo(ProvisioningOrigin other) {
        if (other == null) {
            throw new NullPointerException(
                    "Other provisioning origin is required"
            );
        }
        int namespace = callerNamespace.compareTo(
                other.callerNamespace()
        );
        return namespace != 0
                ? namespace
                : callerKey.compareTo(other.callerKey());
    }

    private UUID namedUuid(String kind) {
        return UUID.nameUUIDFromBytes(
                ("tamework:provisioning:" + kind + ":" + encoded())
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String encoded() {
        return callerNamespace.length() + ":" + callerNamespace
                + callerKey.length() + ":" + callerKey;
    }

    private static String text(
            String value,
            String label,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " exceeds " + maxLength + " characters"
            );
        }
        return normalized;
    }
}

