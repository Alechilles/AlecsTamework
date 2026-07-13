package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Prevents normal coop work from racing a durable capture, release, or import operation.
 *
 * <p>The whole authority is paused while any active operation owns one of its slots. This is
 * intentionally conservative: a slot-committed capture is already visible as {@code HOUSED}, but
 * releasing it before source retirement would create two live representations of one profile.</p>
 */
public final class ManagedCoopLifecycleAdmissionGuard {
    public enum Status {
        ALLOWED,
        BLOCKED_ACTIVE_OPERATION,
        BLOCKED_RUNTIME_NOT_READY,
        BLOCKED_UNTRUSTED
    }

    public record Decision(@Nonnull Status status,
                           @Nullable String operationId,
                           @Nullable String detail) {
        public boolean allowed() {
            return status == Status.ALLOWED;
        }
    }

    private final ManagedCoopLifecycleOperationIndex operations;
    private final BooleanSupplier compositeTrust;
    private final BooleanSupplier runtimeAuthorityReady;

    public ManagedCoopLifecycleAdmissionGuard(
            @Nonnull ManagedCoopLifecycleOperationIndex operations,
            @Nonnull BooleanSupplier compositeTrust) {
        this(operations, compositeTrust, () -> true);
    }

    public ManagedCoopLifecycleAdmissionGuard(
            @Nonnull ManagedCoopLifecycleOperationIndex operations,
            @Nonnull BooleanSupplier compositeTrust,
            @Nonnull BooleanSupplier runtimeAuthorityReady) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.compositeTrust = Objects.requireNonNull(compositeTrust, "compositeTrust");
        this.runtimeAuthorityReady = Objects.requireNonNull(
                runtimeAuthorityReady, "runtimeAuthorityReady");
    }

    /** Returns one stable admission decision from the currently published operation epoch. */
    @Nonnull
    public Decision inspect(@Nonnull ManagedCoopContext context) {
        Objects.requireNonNull(context, "context");
        if (!trusted()) {
            return untrusted("managed_coop_lifecycle_index_untrusted");
        }
        if (!runtimeReady()) {
            return new Decision(
                    Status.BLOCKED_RUNTIME_NOT_READY,
                    null,
                    "managed_coop_runtime_authority_not_ready");
        }
        ManagedCoopLifecycleOperationIndex.Snapshot snapshot = operations.snapshot();
        if (!snapshot.trusted()) {
            return untrusted("managed_coop_lifecycle_snapshot_untrusted");
        }
        OperationRecord active = activeFor(context, snapshot);
        if (!stable(snapshot)) {
            return untrusted("managed_coop_lifecycle_epoch_changed");
        }
        return active == null
                ? new Decision(Status.ALLOWED, null, null)
                : new Decision(
                        Status.BLOCKED_ACTIVE_OPERATION,
                        active.operationId(),
                        "managed_coop_active_lifecycle_operation");
    }

    @Nullable
    private OperationRecord activeFor(ManagedCoopContext context,
                                      ManagedCoopLifecycleOperationIndex.Snapshot snapshot) {
        for (OperationRecord operation : snapshot.operations()) {
            if (operation.authorityKey().equals(context.authorityKey())
                    && operation.coopId().equalsIgnoreCase(context.coopId())) {
                return operation;
            }
        }
        return null;
    }

    private boolean stable(ManagedCoopLifecycleOperationIndex.Snapshot snapshot) {
        return trusted()
                && operations.snapshot().revision() == snapshot.revision()
                && operations.snapshot().trusted();
    }

    private boolean trusted() {
        try {
            return compositeTrust.getAsBoolean() && operations.isTrusted();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean runtimeReady() {
        try {
            return runtimeAuthorityReady.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Nonnull
    private Decision untrusted(String detail) {
        return new Decision(Status.BLOCKED_UNTRUSTED, null, detail);
    }
}
