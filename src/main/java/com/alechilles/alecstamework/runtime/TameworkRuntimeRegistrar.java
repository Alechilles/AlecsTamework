package com.alechilles.alecstamework.runtime;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Compatibility adapter for registry-owned runtime registration.
 *
 * <p>New startup paths should use {@link TameworkRuntimeParticipantRegistry}
 * directly. This adapter preserves the public context-based contract.</p>
 */
public final class TameworkRuntimeRegistrar {
    /** Constructs every active prepared resource without changing the target. */
    public void preflight(TameworkRuntimeRegistrationContext context) {
        TameworkRuntimeRegistrationContext checked = Objects.requireNonNull(
                context, "Runtime registration context is required"
        );
        TameworkRuntimeParticipantRegistry.preflight(checked.plan(), checked.participants());
    }

    /** Registers the active participants from one frozen context. */
    public TameworkRuntimeHandle register(TameworkRuntimeRegistrationContext context) {
        return register(context, ignored -> { });
    }

    /** Registers active participants and reports each completed installation. */
    public TameworkRuntimeHandle register(
            TameworkRuntimeRegistrationContext context,
            Consumer<TameworkRuntimeRegistrationContext.Participant> installed
    ) {
        TameworkRuntimeRegistrationContext checked = Objects.requireNonNull(
                context, "Runtime registration context is required"
        );
        return TameworkRuntimeParticipantRegistry.register(
                checked.plan(), checked.target(), checked.participants(), installed
        );
    }

}
