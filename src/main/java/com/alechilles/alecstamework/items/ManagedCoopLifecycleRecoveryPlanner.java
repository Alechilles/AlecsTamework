package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationKind;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationState;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects at most one deterministic interrupted lifecycle operation for a world sweep. */
public final class ManagedCoopLifecycleRecoveryPlanner {
    private static final Comparator<OperationRecord> RECOVERY_ORDER = Comparator
            .comparingInt((OperationRecord operation) -> operation.authorityKey().x())
            .thenComparingInt(operation -> operation.authorityKey().y())
            .thenComparingInt(operation -> operation.authorityKey().z())
            .thenComparingInt(OperationRecord::residentSlot)
            .thenComparing(OperationRecord::profileId)
            .thenComparing(OperationRecord::operationId);

    public enum ActionKind {
        NONE,
        REQUEST_CAPTURE_SOURCE_RETIREMENT,
        RESUME_CAPTURE_SOURCE_RETIREMENT,
        RESUME_RELEASE,
        WAIT_FOR_COOP_CONTEXT,
        BLOCKED_UNSAFE_STATE,
        RESERVED_FOR_IMPORT
    }

    public record RecoveryAction(@Nonnull ActionKind kind,
                                 @Nullable OperationRecord operation,
                                 @Nullable ManagedCoopContext context,
                                 @Nullable String detail) {
        public RecoveryAction {
            Objects.requireNonNull(kind, "kind");
            if ((kind == ActionKind.NONE) != (operation == null)) {
                throw new IllegalArgumentException("recovery action operation shape mismatch");
            }
            if (kind == ActionKind.RESUME_RELEASE && context == null) {
                throw new IllegalArgumentException("release recovery requires a loaded coop context");
            }
        }
    }

    /** Operation input must come from one trusted immutable lifecycle-index snapshot. */
    @Nonnull
    public RecoveryAction plan(@Nonnull String worldName,
                               @Nonnull List<ManagedCoopContext> contexts,
                               @Nonnull List<OperationRecord> operations) {
        String world = normalize(worldName);
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(operations, "operations");
        OperationRecord selected = null;
        for (OperationRecord operation : operations) {
            if (operation == null || !operation.active()
                    || !operation.authorityKey().worldName().equals(world)) {
                continue;
            }
            if (selected == null || RECOVERY_ORDER.compare(operation, selected) < 0) {
                selected = operation;
            }
        }
        if (selected != null) {
            return action(selected, contextFor(selected, contexts));
        }
        return new RecoveryAction(ActionKind.NONE, null, null, null);
    }

    @Nonnull
    private RecoveryAction action(OperationRecord operation,
                                  @Nullable ManagedCoopContext context) {
        if (operation.kind() == OperationKind.CAPTURE) {
            return capture(operation);
        }
        if (operation.kind() == OperationKind.RELEASE) {
            return release(operation, context);
        }
        if (operation.kind() == OperationKind.IMPORT) {
            return new RecoveryAction(
                    ActionKind.RESERVED_FOR_IMPORT, operation, context,
                    "managed_coop_import_recovery_owned_by_import_runtime");
        }
        return new RecoveryAction(
                ActionKind.BLOCKED_UNSAFE_STATE, operation, context,
                "unsupported_managed_coop_lifecycle_kind");
    }

    @Nonnull
    private RecoveryAction capture(OperationRecord operation) {
        return switch (operation.state()) {
            case SLOT_COMMITTED -> new RecoveryAction(
                    ActionKind.REQUEST_CAPTURE_SOURCE_RETIREMENT,
                    operation, null, null);
            case SOURCE_RETIRE_REQUESTED -> new RecoveryAction(
                    ActionKind.RESUME_CAPTURE_SOURCE_RETIREMENT,
                    operation, null, null);
            case PREPARED -> new RecoveryAction(
                    ActionKind.BLOCKED_UNSAFE_STATE,
                    operation, null,
                    "capture_prepared_missing_atomic_slot_claim");
            default -> new RecoveryAction(
                    ActionKind.BLOCKED_UNSAFE_STATE,
                    operation, null,
                    "capture_recovery_state_not_supported");
        };
    }

    @Nonnull
    private RecoveryAction release(OperationRecord operation,
                                   @Nullable ManagedCoopContext context) {
        boolean recoverable = operation.state() == OperationState.PREPARED
                || operation.state() == OperationState.SPAWN_CLAIMED
                || operation.state() == OperationState.PROJECTION_CREATED;
        if (!recoverable) {
            return new RecoveryAction(
                    ActionKind.BLOCKED_UNSAFE_STATE, operation, context,
                    "release_recovery_state_not_supported");
        }
        return context == null
                ? new RecoveryAction(
                        ActionKind.WAIT_FOR_COOP_CONTEXT, operation, null,
                        "release_recovery_waiting_for_loaded_coop")
                : new RecoveryAction(ActionKind.RESUME_RELEASE, operation, context, null);
    }

    @Nullable
    private ManagedCoopContext contextFor(OperationRecord operation,
                                          List<ManagedCoopContext> contexts) {
        for (ManagedCoopContext context : contexts) {
            if (context != null
                    && context.authorityKey().equals(operation.authorityKey())
                    && context.coopId().equalsIgnoreCase(operation.coopId())) {
                return context;
            }
        }
        return null;
    }

    @Nonnull
    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("worldName must not be blank");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
