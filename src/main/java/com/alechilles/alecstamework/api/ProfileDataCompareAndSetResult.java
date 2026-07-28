package com.alechilles.alecstamework.api;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable result of one revision-fenced profile-data mutation or replay. */
public record ProfileDataCompareAndSetResult(@Nonnull Status status,
                                             @Nonnull String reason,
                                             @Nullable ProfileDataOperationView operation,
                                             @Nullable ProfileDataEntryView entry) {
    public ProfileDataCompareAndSetResult {
        status = Objects.requireNonNull(status, "status");
        reason = ProfileDataValidation.requireText(reason, "reason", 512);
        switch (status) {
            case COMMITTED -> {
                if (operation == null || entry == null
                        || operation.status() != ProfileDataOperationStatus.COMMITTED
                        || operation.resultingRevision() != entry.revision()
                        || !operation.profileId().equals(entry.profileId())
                        || !operation.namespace().equals(entry.namespace())
                        || !operation.key().equals(entry.key())) {
                    throw new IllegalArgumentException(
                            "COMMITTED results require one matching committed operation and entry.");
                }
            }
            case TERMINAL_DENIED -> {
                if (operation == null
                        || operation.status() != ProfileDataOperationStatus.TERMINAL_DENIED
                        || entry != null) {
                    throw new IllegalArgumentException(
                            "TERMINAL_DENIED results require matching durable denial and no entry.");
                }
            }
            case QUARANTINED -> {
                if (operation == null
                        || operation.status() != ProfileDataOperationStatus.QUARANTINED
                        || entry != null) {
                    throw new IllegalArgumentException(
                            "QUARANTINED results require matching durable quarantine and no entry.");
                }
            }
            case UNAVAILABLE -> {
                if (operation != null || entry != null) {
                    throw new IllegalArgumentException("UNAVAILABLE results cannot claim durable state.");
                }
            }
        }
    }

    @Nonnull
    public Optional<ProfileDataOperationView> durableOperation() {
        return Optional.ofNullable(operation);
    }

    @Nonnull
    public Optional<ProfileDataEntryView> committedEntry() {
        return Optional.ofNullable(entry);
    }

    public boolean committed() {
        return status == Status.COMMITTED;
    }

    @Nonnull
    public static ProfileDataCompareAndSetResult unavailable() {
        return new ProfileDataCompareAndSetResult(
                Status.UNAVAILABLE,
                "profile-data-transaction-authority-unavailable",
                null,
                null
        );
    }

    public enum Status {
        COMMITTED,
        TERMINAL_DENIED,
        QUARANTINED,
        UNAVAILABLE
    }
}
